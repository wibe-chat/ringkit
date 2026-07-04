package com.wibechat.ringkit.adapter

import com.wibechat.ringkit.core.CallRecord
import com.wibechat.ringkit.core.CoreAccentColors
import com.wibechat.ringkit.core.CoreCallInfo
import com.wibechat.ringkit.core.CoreCallOptions
import com.wibechat.ringkit.core.CoreCallType
import com.wibechat.ringkit.core.CoreCancelReason
import com.wibechat.ringkit.core.CoreConfig
import com.wibechat.ringkit.core.CoreDeliveryPath
import com.wibechat.ringkit.core.CoreEndReason
import com.wibechat.ringkit.core.CoreEvent
import com.wibechat.ringkit.core.CoreForegroundBehavior
import com.wibechat.ringkit.core.CoreIncomingCall
import com.wibechat.ringkit.core.CoreCallInfoUpdate
import com.wibechat.ringkit.core.CoreLockScreenBehavior
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.CoreMissedCallOptions
import com.wibechat.ringkit.core.CoreNotificationVisibility
import com.wibechat.ringkit.core.CoreOutgoingCall
import com.wibechat.ringkit.core.LaunchAction
import com.wibechat.ringkit.core.TransitionOutcome

/**
 * Pigeon ↔ core mapping. Core deliberately owns its own model types (zero
 * Flutter imports, PLAN §4.1); this file is the single translation point.
 * Like-named enums map by name so a rename on either side fails loudly here.
 */
internal object PigeonMapper {

    // ── Pigeon → core ────────────────────────────────────────────────────

    fun toCore(call: IncomingCall): CoreIncomingCall =
        CoreIncomingCall(
            id = call.id,
            callerName = call.callerName,
            callerHandle = call.callerHandle,
            avatarUrl = call.avatarUrl,
            type = CoreCallType.valueOf(call.type.name),
            timeoutSeconds = call.timeoutSeconds?.toInt(),
            sentAtWallMs = call.sentAtEpochMs,
            extra = call.extra,
            options = toCore(call.android, call.avatarHeaders),
        )

    fun toCore(call: OutgoingCall): CoreOutgoingCall =
        CoreOutgoingCall(
            id = call.id,
            calleeName = call.calleeName,
            calleeHandle = call.calleeHandle,
            avatarUrl = call.avatarUrl,
            type = CoreCallType.valueOf(call.type.name),
            extra = call.extra,
            options = toCore(call.android, call.avatarHeaders),
        )

    private fun toCore(options: AndroidCallOptions, avatarHeaders: Map<String, String>?): CoreCallOptions =
        CoreCallOptions(
            ringtone = options.ringtone,
            vibrate = options.vibrate,
            missedCall =
                options.missedCall?.let {
                    CoreMissedCallOptions(
                        show = it.show,
                        title = it.title,
                        subtitle = it.subtitle,
                        showCallbackAction = it.showCallbackAction,
                        callbackText = it.callbackText,
                    )
                } ?: CoreMissedCallOptions(),
            colors =
                options.colors?.let {
                    CoreAccentColors(
                        acceptArgb = it.acceptArgb?.toInt(),
                        declineArgb = it.declineArgb?.toInt(),
                        accentArgb = it.accentArgb?.toInt(),
                    )
                } ?: CoreAccentColors(),
            fullScreenBackground = options.fullScreenBackground,
            lockScreenVisibility = CoreNotificationVisibility.valueOf(options.lockScreenVisibility.name),
            lockScreenBehavior = CoreLockScreenBehavior.valueOf(options.lockScreenBehavior.name),
            useTelecom = options.useTelecom,
            avatarHeaders = avatarHeaders ?: emptyMap(),
        )

    fun toCore(update: CallInfoUpdate): CoreCallInfoUpdate =
        CoreCallInfoUpdate(
            callerName = update.callerName,
            callerHandle = update.callerHandle,
            avatarUrl = update.avatarUrl,
            avatarHeaders = update.avatarHeaders,
        )

    fun toCore(config: RingKitConfig): CoreConfig =
        CoreConfig(
            incomingChannelName = config.incomingChannelName,
            missedChannelName = config.missedChannelName,
            ongoingChannelName = config.ongoingChannelName,
            audibleChannelName = config.audibleChannelName,
            defaultTimeoutSeconds = config.defaultTimeoutSeconds.toInt(),
            connectionTimeoutSeconds = config.connectionTimeoutSeconds.toInt(),
            maxConcurrentRinging = config.maxConcurrentRinging.toInt(),
            telecomIntegration = config.telecomIntegration,
            manageOngoingCall = config.manageOngoingCall,
            foregroundBehavior = CoreForegroundBehavior.valueOf(config.foregroundBehavior.name),
            notificationIcon = config.notificationIcon,
            defaultRingtone = config.defaultRingtone,
            notificationIdBase = config.notificationIdBase.toInt(),
            logLevel = CoreLogLevel.valueOf(config.logLevel.name),
            channelSuffix = config.channelSuffix,
            // config.telemetry is SCOPE-2; the telemetry/ module reads it there.
        )

    // ── core → Pigeon ────────────────────────────────────────────────────

    fun showResult(outcome: TransitionOutcome): ShowCallResult =
        when (outcome) {
            TransitionOutcome.SHOWN -> ShowCallResult(code = ShowCallResultCode.SHOWN)
            TransitionOutcome.ALREADY_SHOWN -> ShowCallResult(code = ShowCallResultCode.ALREADY_SHOWN)
            TransitionOutcome.ALREADY_ACTIVE -> ShowCallResult(code = ShowCallResultCode.ALREADY_ACTIVE)
            TransitionOutcome.ALREADY_TERMINAL -> ShowCallResult(code = ShowCallResultCode.ALREADY_TERMINAL)
            TransitionOutcome.EXPIRED -> ShowCallResult(code = ShowCallResultCode.EXPIRED)
            TransitionOutcome.INVALID_PARAMS ->
                ShowCallResult(code = ShowCallResultCode.FAILED, failReason = FailReason.INVALID_PARAMS)
            // §8.11 degraded/failed evaluation is the M2 orchestrator's job.
            else -> ShowCallResult(code = ShowCallResultCode.FAILED, failReason = FailReason.STORE_CORRUPT)
        }

    fun commandResult(outcome: TransitionOutcome): CallCommandResult =
        when (outcome) {
            TransitionOutcome.OK, TransitionOutcome.SHOWN -> CallCommandResult.OK
            TransitionOutcome.NOT_FOUND -> CallCommandResult.NOT_FOUND
            TransitionOutcome.WRONG_STATE -> CallCommandResult.WRONG_STATE
            TransitionOutcome.ALREADY_TERMINAL -> CallCommandResult.ALREADY_TERMINAL
            TransitionOutcome.INVALID_PARAMS -> CallCommandResult.INVALID_PARAMS
            TransitionOutcome.ALREADY_SHOWN, TransitionOutcome.ALREADY_ACTIVE ->
                CallCommandResult.WRONG_STATE
            TransitionOutcome.EXPIRED -> CallCommandResult.ALREADY_TERMINAL
            TransitionOutcome.NO_OP -> CallCommandResult.OK
        }

    fun callInfo(info: CoreCallInfo): CallInfo =
        CallInfo(
            id = info.id,
            name = info.name,
            handle = info.handle,
            avatarUrl = info.avatarUrl,
            type = CallType.valueOf(info.type.name),
            direction = CallDirection.valueOf(info.direction.name),
            extra = info.extra,
        )

    fun eventRecord(event: CoreEvent, path: CoreDeliveryPath): CallEventRecord =
        CallEventRecord(
            type = eventType(event.type),
            call = callInfo(event.callInfo),
            sequence = event.seq,
            occurredAtEpochMs = event.wallMs,
            deliveryPath = DeliveryPath.valueOf(path.name),
            persisted = event.type.isPersisted(),
            endReason = event.endReason?.let { EndReason.valueOf(it.name) },
            cancelReason = event.cancelReason?.let { cancelReason(it) },
        )

    fun activeCall(record: CallRecord): ActiveCall =
        ActiveCall(
            id = record.callId,
            state = CallState.valueOf(record.state.name),
            shownAtEpochMs = record.shownAtWallMs,
            info = callInfo(record.info),
        )

    fun launchAction(action: LaunchAction): CallLaunchAction =
        CallLaunchAction(
            type = eventType(action.type),
            call = callInfo(action.callInfo),
            sequence = action.sequence,
            alreadyEnded = action.alreadyEnded,
        )

    private fun eventType(type: com.wibechat.ringkit.core.CoreEventType): CallEventType =
        when (type) {
            com.wibechat.ringkit.core.CoreEventType.RINGING -> CallEventType.RINGING
            com.wibechat.ringkit.core.CoreEventType.ACCEPTED -> CallEventType.ACCEPTED
            com.wibechat.ringkit.core.CoreEventType.DECLINED -> CallEventType.DECLINED
            com.wibechat.ringkit.core.CoreEventType.TIMED_OUT -> CallEventType.TIMED_OUT
            com.wibechat.ringkit.core.CoreEventType.ENDED -> CallEventType.ENDED
            com.wibechat.ringkit.core.CoreEventType.CANCELLED -> CallEventType.CANCELLED
            com.wibechat.ringkit.core.CoreEventType.MISSED_CALL_TAPPED -> CallEventType.MISSED_CALL_TAPPED
            com.wibechat.ringkit.core.CoreEventType.AUDIO_ROUTE_CHANGED -> CallEventType.AUDIO_ROUTE_CHANGED
        }

    private fun cancelReason(reason: CoreCancelReason): CancelReason =
        when (reason) {
            CoreCancelReason.CALLER_CANCELLED -> CancelReason.CALLER_CANCELLED
            CoreCancelReason.ANSWERED_ELSEWHERE -> CancelReason.ANSWERED_ELSEWHERE
            CoreCancelReason.DECLINED_ELSEWHERE -> CancelReason.DECLINED_ELSEWHERE
            CoreCancelReason.LOCAL_DISMISS -> CancelReason.LOCAL_DISMISS
        }

    fun endReasonToCore(reason: EndReason): CoreEndReason = CoreEndReason.valueOf(reason.name)

    fun cancelReasonToCore(reason: CancelReason): CoreCancelReason = CoreCancelReason.valueOf(reason.name)

    private fun com.wibechat.ringkit.core.CoreEventType.isPersisted(): Boolean =
        this != com.wibechat.ringkit.core.CoreEventType.RINGING &&
            this != com.wibechat.ringkit.core.CoreEventType.AUDIO_ROUTE_CHANGED
}
