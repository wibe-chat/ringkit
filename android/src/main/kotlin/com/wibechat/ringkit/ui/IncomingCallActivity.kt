package com.wibechat.ringkit.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.wibechat.ringkit.R
import com.wibechat.ringkit.core.AwaitOutcome
import com.wibechat.ringkit.core.CallRecord
import com.wibechat.ringkit.core.CoreCallState
import com.wibechat.ringkit.core.CoreCallType
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.IncomingCallOrchestrator
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.RingKitIntents
import com.wibechat.ringkit.services.RingingService

/**
 * The full-screen / lock-screen call UI (PLAN §8.5): classic Views, edge-to-edge,
 * responsive; renders purely from repository lookup and OBSERVES it — the bound
 * call's terminal transition (watch decline, remote cancel, timeout) re-binds
 * to the promoted ringer-owner when other RINGING calls survive (§5.2),
 * otherwise finishes within 250 ms via the in-process CallBus (never
 * component-addressed broadcasts, #215 class).
 *
 * Binding always resolves to the CURRENT ringer-owner — the intent's call id is
 * advisory: a waiting call's FSI launch re-binds the existing instance via
 * onNewIntent and renders as the banner, never a second activity instance
 * (#550 black-screen class). Back is suppressed while ringing (predictive-back
 * compliant via OnBackInvokedCallback no-op on 33+). Volume keys silence the
 * ring — the ONLY volume handling in the plugin (§8.4).
 */
class IncomingCallActivity : Activity() {

    private lateinit var core: RingKitCore.Instance
    // Main-thread confined: written at the end of bind() and read by bind()/key +
    // action handlers, all of which run on main — the bus listener reads it inside
    // runOnUiThread, never off the writer thread. No cross-thread access ⇒ no
    // @Volatile (§8.5).
    private var boundCallId: String? = null
    private var renderedCallId: String? = null
    private var busSubscription: AutoCloseable? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null

    private lateinit var callerName: TextView
    private lateinit var callerHandle: TextView
    private lateinit var callType: TextView
    private lateinit var waitingBanner: TextView
    private lateinit var avatarView: ImageView
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        core = RingKitCore.get(applicationContext)
        RingKitCore.sweepOnEntry(applicationContext)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        // Edge-to-edge (mandatory on 16): draw behind bars; content pads itself.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        buildViews()
        setContentView(root)

        // Predictive back (36): suppression via a registered no-op —
        // onBackPressed/KEYCODE_BACK are not delivered under targetSdk 36 (§8.5).
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback { /* suppressed while ringing */ }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback,
            )
        }

        busSubscription = core.bus.subscribe { _ ->
            // Any repository mutation re-resolves the binding (§8.5): the bound
            // call's terminal transition re-binds to the promoted owner when
            // other RINGING calls survive (§5.2) — otherwise finishes ≤250 ms;
            // waiting-call arrivals/terminations refresh the banner.
            //
            // NEVER short-circuit on a null boundCallId. This listener fires on
            // the writer thread, and in the killed-state FSI cold-start a remote
            // cancel can publish here BEFORE onCreate's initial bind has committed
            // boundCallId on main (writer.await releases main, then the writer
            // runs the very next queued transition). The old null-guard dropped
            // that terminal signal and left the FSI ringing forever. Re-resolve
            // from the intent when still unbound; bind() finishes on a null owner.
            runOnUiThread { if (!isFinishing) bind(boundCallId ?: RingKitIntents.callIdOf(intent)) }
        }

        bind(RingKitIntents.callIdOf(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(RingKitIntents.callIdOf(intent))
    }

    override fun onDestroy() {
        busSubscription?.let { runCatching { it.close() } }
        busSubscription = null
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    /** §8.4: volume keys silence the ring; power-button silencing is deliberately not offered. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            val callId = boundCallId
            runCatching {
                startService(
                    RingKitIntents.withCall(
                        Intent(this, RingingService::class.java),
                        RingKitIntents.ACTION_SILENCE,
                        callId ?: "",
                    ),
                )
            }
            return true
        }
        @Suppress("DEPRECATION")
        if (keyCode == KeyEvent.KEYCODE_BACK) return true // legacy back suppression (<33 dispatch)
        return super.onKeyDown(keyCode, event)
    }

    // ── binding & rendering (repository lookup on the writer, render on main) ──

    private fun bind(intentCallId: String?) {
        if (isFinishing) return // a terminal signal already won the teardown
        if (intentCallId == null && boundCallId == null) {
            // Process-death restore with a stale/null intent: finish quietly (§8.5).
            finish()
            return
        }
        // §8.5: binding always resolves to the CURRENT ringer-owner — the
        // intent's call id is advisory (a waiting call's FSI launch renders the
        // owner + banner; a terminal signal re-binds to the promoted owner).
        //
        // R45: distinguish a writer-busy TIMEOUT from a genuine empty. With telecom
        // on, `addCall` can legally hold the writer up to READY_TIMEOUT_MS (2 s) —
        // exactly this budget. Collapsing that timeout into "no owner" self-destructs
        // the FSI answer UI mid-ring. Fail-open on timeout/failure: keep the UI
        // alive; the next repository mutation re-fires the bus and re-binds once the
        // writer is free. Only a COMPLETED lookup is authoritative enough to finish.
        val snapshot =
            when (val outcome =
                core.writer.awaitCatching(timeoutMs = BIND_TIMEOUT_MS) {
                    val ringing = core.repository.activeCalls().filter { it.state == CoreCallState.RINGING }
                    ringing.firstOrNull() to (ringing.size - 1)
                }
            ) {
                is AwaitOutcome.Completed -> outcome.value
                is AwaitOutcome.TimedOut -> {
                    core.ringLog.log(
                        CoreLogLevel.INFO, "fsi", "IncomingCallActivity.bind", intentCallId ?: boundCallId,
                        "writer busy — bind timed out, keeping UI alive (fail-open, R45)",
                    )
                    return
                }
                is AwaitOutcome.Failed -> {
                    core.ringLog.log(
                        CoreLogLevel.ERROR, "fsi", "IncomingCallActivity.bind", intentCallId ?: boundCallId,
                        "owner lookup failed — keeping UI alive (fail-open)",
                        mapOf("error" to (outcome.error.message ?: outcome.error.javaClass.simpleName)),
                    )
                    return
                }
            }

        val owner = snapshot.first
        if (owner == null) {
            core.ringLog.log(
                CoreLogLevel.INFO, "fsi", "IncomingCallActivity.bind", intentCallId ?: boundCallId,
                "no live RINGING owner — finishing quietly",
            )
            finish()
            return
        }
        if (intentCallId != null && owner.callId != intentCallId) {
            core.ringLog.log(
                CoreLogLevel.INFO, "fsi", "IncomingCallActivity.bind", intentCallId,
                "re-bound to ringer-owner", mapOf("owner" to owner.callId),
            )
        }
        boundCallId = owner.callId
        render(owner, waitingCount = (snapshot.second).coerceAtLeast(0))
    }

    private fun render(record: CallRecord, waitingCount: Int) {
        waitingBanner.visibility = if (waitingCount > 0) View.VISIBLE else View.GONE
        if (waitingCount > 0) {
            waitingBanner.text = "$waitingCount more call${if (waitingCount > 1) "s" else ""} waiting"
        }
        // Same owner ⇒ banner-only refresh (waiting arrivals/evictions must not
        // re-announce or re-fetch the avatar).
        if (renderedCallId == record.callId) return
        renderedCallId = record.callId

        val info = record.info
        callerName.text = info.name
        callerHandle.text = info.handle ?: ""
        callerHandle.visibility = if (info.handle == null) View.GONE else View.VISIBLE
        callType.text =
            getString(
                if (info.type == CoreCallType.VIDEO) R.string.ringkit_incoming_video
                else R.string.ringkit_incoming_audio,
            )

        // Background: #RRGGBB / #AARRGGBB parse, else the default dark scrim.
        val background = record.options.fullScreenBackground
        val parsed = background?.takeIf { it.startsWith("#") }?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        root.setBackgroundColor(parsed ?: 0xFF102027.toInt())

        // Avatar via the shared loader (https/flutterAsset; per-call headers).
        avatarView.setImageResource(R.drawable.ringkit_ic_call)
        val url = info.avatarUrl
        if (url != null) {
            core.avatarLoader.load("${record.callId}#fsi", url, record.options.avatarHeaders) { bitmap ->
                runOnUiThread {
                    if (!isFinishing && renderedCallId == record.callId) avatarView.setImageBitmap(bitmap)
                }
            }
        }

        root.announceForAccessibility("${callType.text}: ${info.name}")
    }

    // ── actions (one code path, §4.3) ────────────────────────────────────

    private fun onAccept() {
        val callId = boundCallId ?: return
        // FSI button → the SAME trampoline as the notification action.
        startActivity(
            RingKitIntents.withCall(
                Intent(this, AcceptTrampolineActivity::class.java),
                RingKitIntents.ACTION_ACCEPT,
                callId,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    private fun onDecline() {
        val callId = boundCallId ?: return
        core.writer.post {
            IncomingCallOrchestrator(applicationContext, core).declineOnWriter(callId)
        }
        finish()
    }

    // ── view construction (classic Views, no XML/appcompat dependency) ───

    private fun buildViews() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        root = FrameLayout(this)

        waitingBanner = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        avatarView = ImageView(this).apply {
            contentDescription = getString(R.string.ringkit_a11y_caller_avatar)
            setImageResource(R.drawable.ringkit_ic_call)
        }
        callerName = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
        }
        callerHandle = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
        }
        callType = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Banner lives IN the column: a top-anchored banner sits behind the
            // waiting call's own pinned CallStyle heads-up on One UI
            // (device-found, SM-G781B) — here it is always visible.
            addView(
                waitingBanner,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(16) },
            )
            addView(avatarView, LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(20) })
            addView(callType)
            addView(callerName)
            addView(callerHandle)
        }

        fun circleButton(label: String, color: Int, a11y: String, onTap: () -> Unit): LinearLayout {
            val button = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                contentDescription = a11y
                isClickable = true
                isFocusable = true
                setOnClickListener { onTap() }
                // §8.5 a11y: announce as a Button with a labelled click action
                // (TalkBack then reads "<a11y>, button — double-tap to <a11y>").
                androidx.core.view.ViewCompat.setAccessibilityDelegate(
                    this,
                    object : androidx.core.view.AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: android.view.View,
                            info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat,
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = android.widget.Button::class.java.name
                            info.addAction(
                                androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                                    .AccessibilityActionCompat(
                                        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK,
                                        a11y,
                                    ),
                            )
                        }
                    },
                )
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // ≥48 dp touch targets (§8.5 a11y skeleton); 76 dp actual.
                addView(button, LinearLayout.LayoutParams(dp(76), dp(76)))
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                circleButton(
                    getString(R.string.ringkit_action_decline), 0xFFD32F2F.toInt(),
                    getString(R.string.ringkit_a11y_decline_call),
                ) { onDecline() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                circleButton(
                    getString(R.string.ringkit_action_accept), 0xFF2E7D32.toInt(),
                    getString(R.string.ringkit_a11y_accept_call),
                ) { onAccept() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        root.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        root.addView(
            buttons,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(64) },
        )

        // Inset padding (edge-to-edge): keep the banner + buttons off the bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /**
     * Test seam (avoids reflecting on a private field): reproduce the killed-state
     * cold-start race where a terminal signal arrives before onCreate's initial
     * bind has committed `boundCallId`.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setBoundCallIdForTest(value: String?) {
        boundCallId = value
    }

    companion object {
        /**
         * Owner-lookup budget (R45). Matches the telecom `addCall` writer-hold
         * ceiling; a timeout here means "writer busy", not "no owner" — bind
         * fail-opens rather than tearing down the ring UI.
         */
        private const val BIND_TIMEOUT_MS = 2_000L
    }
}
