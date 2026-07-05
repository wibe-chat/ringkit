package com.wibechat.ringkit.services

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wibechat.ringkit.R
import com.wibechat.ringkit.core.CallRecord
import com.wibechat.ringkit.core.CoreCallState
import com.wibechat.ringkit.core.CoreConfig
import com.wibechat.ringkit.core.CoreLockScreenBehavior
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.IncomingCallOrchestrator
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.RingKitIntents
import com.wibechat.ringkit.core.RingLog
import com.wibechat.ringkit.core.CallActionReceiver
import com.wibechat.ringkit.ui.CallNotificationFactory
import com.wibechat.ringkit.ui.RingKitChannels
import com.wibechat.ringkit.ui.RingtonePlayer

/**
 * The ringing FGS (PLAN §8.3): owns the RingtonePlayer, the vibrator, every
 * RINGING deadline, the 60 s hard-stop watchdog and the no-audio AlarmManager
 * backstop. Lives while ANY call is RINGING; re-points its foreground
 * notification as owners change; stops when none remain. START_NOT_STICKY —
 * OS-kill mid-ring is an accepted, sweep-covered risk (§5.2).
 *
 * Threading: all repository reads + ring decisions run on the single writer
 * (§8.9); only Service lifecycle calls (stopSelf) hop to main. The one
 * exception is onStartCommand's mandatory synchronous startForeground, which
 * takes a bounded-writer snapshot (R16/R40: startForeground is the
 * UNCONDITIONAL FIRST statement, rebuilding from the repository on null
 * intent — a restarted service never runs un-foregrounded, never crashes on a
 * null action).
 */
class RingingService : Service() {

    companion object {
        const val HARD_STOP_GRACE_MS = 60_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * §8.11 fgsDenied path: in-process best-effort ring WITHOUT the service
         * (honest lifetime: survives only while the OS keeps the process alive;
         * the notification + FSI/sticky-HUN + audible channel are the floor).
         * Writer-confined like every ring decision.
         */
        fun bestEffortInProcessRing(context: Context, core: RingKitCore.Instance, callId: String) {
            val record = core.repository.callFor(callId) ?: return
            val player = sharedPlayer(context, core)
            val anyInCall = core.repository.activeCalls().any { it.state != CoreCallState.RINGING }
            val outcome = player.start(record, core.repository.currentConfig().defaultRingtone, anyInCall)
            core.ringLog.log(
                CoreLogLevel.INFO, "svc", "RingingService.bestEffortInProcessRing", callId,
                "in_process_ring", mapOf("audible" to outcome.audible.toString(), "engine" to (outcome.engine ?: "none")),
            )
        }

        /** §5.2 waiting-call attention cue — writer-confined like every ring decision. */
        fun waitingCue(context: Context, core: RingKitCore.Instance, callId: String) {
            val record = core.repository.callFor(callId) ?: return
            sharedPlayer(context, core).waitingCue(record)
        }

        // One player per process: the fgsDenied path and the service must never
        // double-play; whoever stops next stops this instance.
        @Volatile
        private var processPlayer: RingtonePlayer? = null

        internal fun sharedPlayer(context: Context, core: RingKitCore.Instance): RingtonePlayer {
            processPlayer?.let { return it }
            synchronized(this) {
                processPlayer?.let { return it }
                val created = RingtonePlayer(context.applicationContext, core.ringLog)
                processPlayer = created
                return created
            }
        }
    }

    private lateinit var core: RingKitCore.Instance
    private lateinit var orchestrator: IncomingCallOrchestrator
    private val main = Handler(Looper.getMainLooper())
    private var busSubscription: AutoCloseable? = null

    /** Writer-confined state. */
    private var audioOwnerCallId: String? = null
    private var audioOwnerAudible = false
    private var foregroundCallId: String? = null
    private val timerTokens = HashMap<String, Any>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        core = RingKitCore.get(applicationContext)
        orchestrator = IncomingCallOrchestrator(applicationContext, core)
        isRunning = true
        // §8.6: react to every repository mutation — terminal transitions stop
        // audio/timers; new RINGING calls get deadlines; owner changes re-point.
        // Signals fire ON the writer already; re-entrance is safe (same thread).
        busSubscription = core.bus.subscribe { signal ->
            reevaluateOnWriter("bus:${signal.eventType}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // R16/R40: unconditional first statement — bounded snapshot, then
        // startForeground before ANYTHING else can throw or return.
        startForegroundFromRepository()

        when (intent?.action) {
            RingKitIntents.ACTION_SILENCE -> {
                // Player state is writer-confined like every ring decision.
                val callId = RingKitIntents.callIdOf(intent)
                core.writer.post { sharedPlayer(this, core).silence(callId) }
            }
            else ->
                core.writer.post { reevaluateOnWriter(intent?.action ?: "null-intent-restart") }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        busSubscription?.let { runCatching { it.close() } }
        busSubscription = null
        core.writer.post { sharedPlayer(this, core).stop() }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── foreground discipline (R16/R40) ──────────────────────────────────

    private class ForegroundSnapshot(
        val owner: CallRecord?,
        val ownerNotifId: Int?,
        val config: CoreConfig,
    )

    private fun startForegroundFromRepository() {
        val snapshot =
            runCatching {
                core.writer.await(timeoutMs = 3_000L) {
                    val owner =
                        core.repository.activeCalls().firstOrNull { it.state == CoreCallState.RINGING }
                    ForegroundSnapshot(
                        owner = owner,
                        ownerNotifId = owner?.let { core.repository.notificationIdFor(it.callId) },
                        config = core.repository.currentConfig(),
                    )
                }
            }.getOrElse { ForegroundSnapshot(null, null, CoreConfig()) }

        val owner = snapshot.owner
        val notifId = snapshot.ownerNotifId ?: (snapshot.config.notificationIdBase - 1)
        val notification: Notification =
            if (owner != null) {
                foregroundCallId = owner.callId
                buildOwnerNotification(owner, snapshot.config) { snapshot.ownerNotifId }
            } else {
                placeholderNotification(snapshot.config)
            }

        try {
            doStartForeground(notifId, notification)
        } catch (e: Exception) {
            core.ringLog.incident(
                RingLog.IncidentType.FGS_DENIED, "RingingService.startForegroundFromRepository",
                owner?.callId, throwable = e,
            )
            stopSelf()
            return
        }

        if (owner == null) {
            // Foreground-first satisfied; nothing to ring (restart race).
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun doStartForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(id, notification)
        }
    }

    private fun buildOwnerNotification(
        owner: CallRecord,
        config: CoreConfig,
        idLookup: (String) -> Int?,
    ): Notification {
        val factory = CallNotificationFactory(applicationContext, config, core.time, core.ringLog, idLookup)
        return try {
            factory.buildIncoming(
                owner,
                // §8.2 validity invariant: FSI omitted ONLY here — the FGS-posted
                // owner notification in notificationOnly mode.
                attachFsi = owner.options.lockScreenBehavior == CoreLockScreenBehavior.FULL_SCREEN,
            )
        } catch (e: IllegalArgumentException) {
            core.ringLog.incident(
                RingLog.IncidentType.CALLSTYLE_REJECTED, "RingingService.buildOwnerNotification",
                owner.callId, setOf("callStyleRejected"), throwable = e,
            )
            factory.buildIncomingFallback(owner, attachFsi = true)
        }
    }

    private fun placeholderNotification(config: CoreConfig): Notification =
        NotificationCompat.Builder(this, RingKitChannels.ongoing(config.channelSuffix))
            .setSmallIcon(R.drawable.ringkit_ic_call)
            .setContentTitle(getString(R.string.ringkit_channel_ongoing))
            .setOngoing(true)
            .build()

    // ── ring/timer ownership (writer-confined) ───────────────────────────

    private fun reevaluateOnWriter(why: String) {
        val all = core.repository.activeCalls()
        val ringing = all.filter { it.state == CoreCallState.RINGING }
        val anyInCall = all.any { it.state != CoreCallState.RINGING }
        val owner = ringing.firstOrNull()
        val config = core.repository.currentConfig()

        core.ringLog.log(
            CoreLogLevel.VERBOSE, "svc", "RingingService.reevaluate", owner?.callId,
            "reevaluate", mapOf("why" to why, "ringing" to ringing.size.toString(), "inCall" to anyInCall.toString()),
        )

        if (ringing.isEmpty()) {
            audioOwnerCallId = null
            sharedPlayer(this, core).stop()
            timerTokens.values.forEach { main.removeCallbacksAndMessages(it) }
            timerTokens.clear()
            // Remove-vs-detach (both found on-device, SM-G781B): while the
            // notification is FGS-attached the orchestrator's cancel() is a
            // silent no-op, so a TERMINAL call's incoming notification must be
            // REMOVEd here — but an ACCEPTED call just re-used its id for the
            // "Connecting…" surface, which REMOVE would destroy → DETACH.
            val foregroundCallLive =
                foregroundCallId?.let { core.repository.callFor(it) }?.state
                    ?.let { it != CoreCallState.RINGING } == true
            main.post {
                ServiceCompat.stopForeground(
                    this,
                    if (foregroundCallLive) ServiceCompat.STOP_FOREGROUND_DETACH
                    else ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                stopSelf()
            }
            return
        }

        // Deadlines: one primary timer + one hard-stop per RINGING call (§8.3).
        val live = ringing.map { it.callId }.toSet()
        timerTokens.keys.filterNot { it in live }.toList().forEach { stale ->
            timerTokens.remove(stale)?.let { main.removeCallbacksAndMessages(it) }
        }
        ringing.forEach { record -> armTimersFor(record) }

        // Audio: exactly one ringer-owner; §5.2 etiquette — while any call is
        // in-call, incoming calls never grab focus or play the full ringtone.
        val player = sharedPlayer(this, core)
        if (owner == null || anyInCall) {
            if (audioOwnerCallId != null) {
                audioOwnerCallId = null
                audioOwnerAudible = false
                player.stop()
            }
        } else if (audioOwnerCallId != owner.callId) {
            audioOwnerCallId = owner.callId
            player.stop()
            val outcome = player.start(owner, config.defaultRingtone, anyInCallState = false)
            audioOwnerAudible = outcome.audible
            core.trace.trace(
                "ring", owner.callId,
                "audio start: engine=${outcome.engine} audible=${outcome.audible} policy=${outcome.resolution}",
            )
        }

        // §4.3: every RINGING call without an audible ring gets the Doze-safe
        // AlarmManager backstop — resolver-muted owners AND §5.2 waiting-silence
        // calls (Handler timers can be Doze-deferred; acceptable lateness = one
        // maintenance window). Re-arming replaces the same PI — idempotent.
        ringing.forEach { record ->
            val audiblyRinging = audioOwnerAudible && record.callId == audioOwnerCallId
            if (!audiblyRinging) armNoAudioBackstop(record)
        }

        // Re-point the foreground notification when the owner changed (§5.2) —
        // subsequent startForeground calls update in place, retaining validity.
        if (owner != null && owner.callId != foregroundCallId) {
            foregroundCallId = owner.callId
            val notifId = core.repository.notificationIdFor(owner.callId)
            val notification = buildOwnerNotification(owner, config, core.repository::notificationIdFor)
            runCatching { doStartForeground(notifId ?: (config.notificationIdBase - 1), notification) }
                .onFailure {
                    core.ringLog.log(
                        CoreLogLevel.ERROR, "svc", "RingingService.reevaluate", owner.callId,
                        "re-point failed", throwable = it,
                    )
                }
        }
    }

    /**
     * Primary timer + 60 s hard-stop. Handler time is uptime-based — deadlines
     * are COMPUTED from elapsedRealtime each (re)arm, so a Doze-delayed timer
     * fires late but never early; the sweep + no-audio alarm are the nets.
     */
    private fun armTimersFor(record: CallRecord) {
        val callId = record.callId
        if (timerTokens.containsKey(callId)) return
        val deadline = record.ringDeadlineElapsedMs ?: return
        val token = Any()
        timerTokens[callId] = token
        val delay = (deadline - core.time.elapsedMs()).coerceAtLeast(0L)

        main.postAtTime(
            { core.writer.post { orchestrator.timeoutOnWriter(callId) } },
            token,
            SystemClock.uptimeMillis() + delay,
        )
        // Hard-stop: if anything wedged past timeout, silence + force the
        // terminal transition (idempotent) — never an immortal ring (§8.3).
        main.postAtTime(
            {
                core.writer.post {
                    val still = core.repository.callFor(callId)
                    if (still?.state == CoreCallState.RINGING) {
                        core.ringLog.incident(RingLog.IncidentType.WATCHDOG_FIRED, "RingingService.hardStop", callId)
                        orchestrator.timeoutOnWriter(callId)
                        sharedPlayer(this, core).stop()
                    }
                }
            },
            token,
            SystemClock.uptimeMillis() + delay + HARD_STOP_GRACE_MS,
        )
    }

    /** §4.3/§8.3: no-audio rings get `setAndAllowWhileIdle` as the Doze-safe backstop. */
    private fun armNoAudioBackstop(record: CallRecord) {
        val deadline = record.ringDeadlineElapsedMs ?: return
        runCatching {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadline,
                timeoutPendingIntent(record.callId),
            )
        }.onFailure {
            core.ringLog.log(
                CoreLogLevel.ERROR, "svc", "RingingService.armNoAudioBackstop", record.callId,
                "alarm arm failed", throwable = it,
            )
        }
    }

    private fun timeoutPendingIntent(callId: String): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            (core.repository.notificationIdFor(callId) ?: 0) * 10 + 9,
            RingKitIntents.withCall(Intent(this, CallActionReceiver::class.java), RingKitIntents.ACTION_TIMEOUT, callId)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
