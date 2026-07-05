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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import com.wibechat.ringkit.R
import com.wibechat.ringkit.core.CallBus
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
 * responsive; renders purely from intent payload + repository lookup and
 * OBSERVES the repository — any terminal transition (watch decline, remote
 * cancel, timeout) finishes it within 250 ms via the in-process CallBus (never
 * component-addressed broadcasts, #215 class).
 *
 * Binds to the current ringer-owner; a second FSI launch re-binds the existing
 * instance via onNewIntent (#550 black-screen class). Back is suppressed while
 * ringing (predictive-back compliant via OnBackInvokedCallback no-op on 33+).
 * Volume keys silence the ring — the ONLY volume handling in the plugin (§8.4).
 */
class IncomingCallActivity : Activity() {

    private lateinit var core: RingKitCore.Instance
    private var boundCallId: String? = null
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

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Edge-to-edge (mandatory on 16): draw behind bars; content pads itself.
        window.setDecorFitsSystemWindows(false)

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

        busSubscription = core.bus.subscribe { signal ->
            val bound = boundCallId ?: return@subscribe
            if (signal.callId == bound && (signal.terminal || signal.liveState != CoreCallState.RINGING)) {
                // ≤250 ms teardown (§8.5): signal fires as the transition
                // persists; hop straight to main.
                runOnUiThread { if (!isFinishing) finish() }
            }
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

    private fun bind(callId: String?) {
        if (callId == null) {
            // Process-death restore with a stale/null intent: finish quietly (§8.5).
            finish()
            return
        }
        boundCallId = callId
        val snapshot =
            runCatching {
                core.writer.await(timeoutMs = 2_000L) {
                    val record = core.repository.callFor(callId)
                    val waiting =
                        core.repository.activeCalls().count { it.state == CoreCallState.RINGING } - 1
                    record to waiting
                }
            }.getOrNull()

        val record = snapshot?.first
        if (record == null || record.state != CoreCallState.RINGING) {
            core.ringLog.log(
                CoreLogLevel.INFO, "fsi", "IncomingCallActivity.bind", callId,
                "no live RINGING record — finishing quietly",
            )
            finish()
            return
        }
        render(record, waitingCount = (snapshot.second).coerceAtLeast(0))
    }

    private fun render(record: CallRecord, waitingCount: Int) {
        val info = record.info
        callerName.text = info.name
        callerHandle.text = info.handle ?: ""
        callerHandle.visibility = if (info.handle == null) View.GONE else View.VISIBLE
        callType.text =
            getString(
                if (info.type == CoreCallType.VIDEO) R.string.ringkit_incoming_video
                else R.string.ringkit_incoming_audio,
            )
        waitingBanner.visibility = if (waitingCount > 0) View.VISIBLE else View.GONE
        if (waitingCount > 0) {
            waitingBanner.text = "$waitingCount more call${if (waitingCount > 1) "s" else ""} waiting"
        }

        // Background: #RRGGBB / #AARRGGBB parse, else the default dark scrim.
        val background = record.options.fullScreenBackground
        val parsed = background?.takeIf { it.startsWith("#") }?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        root.setBackgroundColor(parsed ?: 0xFF102027.toInt())

        // Avatar via the shared loader (https/flutterAsset; per-call headers).
        val url = info.avatarUrl
        if (url != null) {
            core.avatarLoader.load("${record.callId}#fsi", url, record.options.avatarHeaders) { bitmap ->
                runOnUiThread { if (!isFinishing) avatarView.setImageBitmap(bitmap) }
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
            waitingBanner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
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
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
