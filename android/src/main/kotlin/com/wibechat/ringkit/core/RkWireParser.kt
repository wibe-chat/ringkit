package com.wibechat.ringkit.core

import org.json.JSONObject

/**
 * §7.5 `rk.*` wire contract parser (normative table: PLAN §7.5; the same table
 * lives as data at `android/src/test/resources/spec/wire_table.json` and the
 * parser suite is generated from it — never change parsing behavior without
 * updating both in the same commit).
 *
 * Rules (§7.5): unknown `rk.*` keys are ignored (forward compat); an invalid
 * value for an OPTIONAL key falls back to its column default + trace; invalid
 * REQUIRED keys reject the whole message + trace. Dispatch is message-level: a
 * message containing any `rk.*` key matches and is never forwarded (§7.3).
 */
object RkWireParser {

    const val PREFIX = "rk."

    sealed class ParseResult {
        /** No rk.* keys — forward untouched to the app's other receiver (§7.3). */
        object NotRingKit : ParseResult()

        /** Matched but invalid required data — rejected + traced, never forwarded. */
        data class Rejected(val reason: String) : ParseResult()

        data class Incoming(val call: CoreIncomingCall) : ParseResult()

        data class Cancel(val callId: String, val reason: CoreCancelReason) : ParseResult()
    }

    /**
     * [serverSentAtMs] is FCM's RemoteMessage.getSentTime() when available —
     * preferred over rk.sentAt (§5.2); pass null/0 outside Mode 2.
     */
    fun parse(
        data: Map<String, String>,
        serverSentAtMs: Long? = null,
        trace: CoreTrace = CoreTrace.NoOp,
    ): ParseResult {
        if (data.keys.none { it.startsWith(PREFIX) }) return ParseResult.NotRingKit

        fun traced(key: String, why: String) = trace.trace("wire", data["rk.callId"], "$key: $why")

        // ── required keys ────────────────────────────────────────────────
        val type = data["rk.type"]
        if (type != "incoming" && type != "cancel") {
            traced("rk.type", "missing or invalid ($type) — message rejected")
            return ParseResult.Rejected("rk.type missing/invalid")
        }
        val callId = data["rk.callId"] ?: ""
        if (callId.isBlank()) {
            traced("rk.callId", "missing — message rejected")
            return ParseResult.Rejected("rk.callId missing")
        }
        if (callId.length > CallRepository.MAX_CALL_ID_LENGTH) {
            // Never truncated (§7.5) — an idempotency key must match exactly.
            traced("rk.callId", "over ${CallRepository.MAX_CALL_ID_LENGTH} chars — message rejected")
            return ParseResult.Rejected("rk.callId over-length")
        }

        if (type == "cancel") {
            val reason =
                when (val raw = data["rk.reason"]) {
                    null, "" -> CoreCancelReason.CALLER_CANCELLED
                    "callerCancelled" -> CoreCancelReason.CALLER_CANCELLED
                    "answeredElsewhere" -> CoreCancelReason.ANSWERED_ELSEWHERE
                    "declinedElsewhere" -> CoreCancelReason.DECLINED_ELSEWHERE
                    else -> {
                        traced("rk.reason", "invalid ($raw) — default callerCancelled")
                        CoreCancelReason.CALLER_CANCELLED
                    }
                }
            return ParseResult.Cancel(callId, reason)
        }

        // ── incoming ─────────────────────────────────────────────────────
        var callerName = data["rk.callerName"] ?: ""
        if (callerName.isBlank()) {
            // Required-with-placeholder per §7.5: blank → "Unknown caller" + warning.
            traced("rk.callerName", "blank — placeholder")
            callerName = CallRepository.UNKNOWN_CALLER_PLACEHOLDER
        }

        val avatarUrl =
            data["rk.avatarUrl"]?.takeIf { it.isNotBlank() }?.let { url ->
                if (url.startsWith("https://")) {
                    url
                } else {
                    traced("rk.avatarUrl", "non-https — ignored")
                    null
                }
            }

        val avatarHeaders =
            data["rk.avatarHeaders"]?.let { raw ->
                runCatching {
                    val o = JSONObject(raw)
                    val out = LinkedHashMap<String, String>()
                    o.keys().forEach { k -> out[k] = o.optString(k, "") }
                    out as Map<String, String>
                }.getOrElse {
                    traced("rk.avatarHeaders", "unparseable JSON — ignored")
                    emptyMap()
                }
            } ?: emptyMap()

        val callType =
            when (val raw = data["rk.callType"]) {
                null, "" -> CoreCallType.AUDIO
                "audio" -> CoreCallType.AUDIO
                "video" -> CoreCallType.VIDEO
                else -> {
                    traced("rk.callType", "invalid ($raw) — default audio")
                    CoreCallType.AUDIO
                }
            }

        val timeoutSeconds =
            data["rk.timeoutSeconds"]?.let { raw ->
                raw.toIntOrNull() ?: run {
                    traced("rk.timeoutSeconds", "invalid ($raw) — config default")
                    null
                }
            }

        val sentAt =
            when {
                serverSentAtMs != null && serverSentAtMs > 0L -> serverSentAtMs
                else ->
                    data["rk.sentAt"]?.let { raw ->
                        raw.toLongOrNull() ?: run {
                            traced("rk.sentAt", "invalid ($raw) — ignored")
                            null
                        }
                    }
            }

        val extra =
            data["rk.extra"]?.let { raw ->
                if (raw.toByteArray(Charsets.UTF_8).size > CallRepository.MAX_EXTRA_BYTES) {
                    // Over-limit Mode-2 payloads truncate + trace (§6.1/§7.5).
                    traced("rk.extra", "over ${CallRepository.MAX_EXTRA_BYTES} B — truncated")
                    truncateExtra(raw)
                } else {
                    runCatching { jsonToMap(raw) }.getOrElse {
                        traced("rk.extra", "unparseable JSON — ignored")
                        emptyMap()
                    }
                }
            } ?: emptyMap()

        val ringtone =
            data["rk.ringtone"]?.takeIf { it.isNotBlank() }?.let { raw ->
                // No flutterAsset: on the wire — Dart may never run (§7.5).
                if (raw == "default" || raw.startsWith("raw:") || raw.startsWith("content://")) {
                    raw
                } else {
                    traced("rk.ringtone", "invalid grammar ($raw) — config default")
                    null
                }
            }

        val vibrate = parseBool(data["rk.vibrate"], default = true) { traced("rk.vibrate", it) }

        val visibility =
            when (val raw = data["rk.lockScreenVisibility"]) {
                null, "" -> CoreNotificationVisibility.PUBLIC
                "public" -> CoreNotificationVisibility.PUBLIC
                "private" -> CoreNotificationVisibility.PRIVATE
                "secret" -> CoreNotificationVisibility.SECRET
                else -> {
                    traced("rk.lockScreenVisibility", "invalid ($raw) — public")
                    CoreNotificationVisibility.PUBLIC
                }
            }

        val behavior =
            when (val raw = data["rk.lockScreenBehavior"]) {
                null, "" -> CoreLockScreenBehavior.FULL_SCREEN
                "fullScreen" -> CoreLockScreenBehavior.FULL_SCREEN
                "notificationOnly" -> CoreLockScreenBehavior.NOTIFICATION_ONLY
                else -> {
                    traced("rk.lockScreenBehavior", "invalid ($raw) — fullScreen")
                    CoreLockScreenBehavior.FULL_SCREEN
                }
            }

        val colors =
            CoreAccentColors(
                acceptArgb = parseColor(data["rk.colors.accept"]) { traced("rk.colors.accept", it) },
                declineArgb = parseColor(data["rk.colors.decline"]) { traced("rk.colors.decline", it) },
                accentArgb = parseColor(data["rk.colors.accent"]) { traced("rk.colors.accent", it) },
            )

        val background =
            data["rk.background"]?.takeIf { it.isNotBlank() }?.let { raw ->
                val isColor = raw.length == 7 && raw.startsWith("#") && parseColor(raw) { } != null
                if (isColor || raw.startsWith("https://")) {
                    raw
                } else {
                    traced("rk.background", "invalid ($raw) — plugin scrim")
                    null
                }
            }

        val missedCall =
            CoreMissedCallOptions(
                show = parseBool(data["rk.missedCall.show"], default = true) { traced("rk.missedCall.show", it) },
                title = data["rk.missedCall.title"]?.takeIf { it.isNotBlank() },
                subtitle = data["rk.missedCall.subtitle"]?.takeIf { it.isNotBlank() },
                showCallbackAction =
                    parseBool(data["rk.missedCall.showCallback"], default = true) {
                        traced("rk.missedCall.showCallback", it)
                    },
                callbackText = data["rk.missedCall.callbackText"]?.takeIf { it.isNotBlank() },
            )

        val useTelecom =
            data["rk.useTelecom"]?.let { raw ->
                when (raw) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> {
                        traced("rk.useTelecom", "invalid ($raw) — config default")
                        null
                    }
                }
            }

        return ParseResult.Incoming(
            CoreIncomingCall(
                id = callId,
                callerName = callerName,
                callerHandle = data["rk.callerHandle"]?.takeIf { it.isNotBlank() },
                avatarUrl = avatarUrl,
                type = callType,
                timeoutSeconds = timeoutSeconds,
                sentAtWallMs = sentAt,
                extra = extra,
                options =
                    CoreCallOptions(
                        ringtone = ringtone,
                        vibrate = vibrate,
                        missedCall = missedCall,
                        colors = colors,
                        fullScreenBackground = background,
                        lockScreenVisibility = visibility,
                        lockScreenBehavior = behavior,
                        useTelecom = useTelecom,
                        avatarHeaders = avatarHeaders,
                    ),
            ),
        )
    }

    private inline fun parseBool(raw: String?, default: Boolean, onInvalid: (String) -> Unit): Boolean =
        when (raw) {
            null, "" -> default
            "true", "1" -> true
            "false", "0" -> false
            else -> {
                onInvalid("invalid bool ($raw) — default $default")
                default
            }
        }

    /** #RRGGBB → opaque ARGB int; anything else → null (+trace via callback). */
    private inline fun parseColor(raw: String?, onInvalid: (String) -> Unit): Int? {
        if (raw.isNullOrBlank()) return null
        if (raw.length != 7 || !raw.startsWith("#")) {
            onInvalid("invalid color ($raw)")
            return null
        }
        return try {
            0xFF000000.toInt() or raw.substring(1).toInt(16)
        } catch (_: NumberFormatException) {
            onInvalid("invalid color ($raw)")
            null
        }
    }

    private fun jsonToMap(raw: String): Map<String, String> {
        val o = JSONObject(raw)
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k, "") }
        return out
    }

    /** Deterministic truncation: keep entries in order while under the budget. */
    private fun truncateExtra(raw: String): Map<String, String> {
        val full = runCatching { jsonToMap(raw) }.getOrElse { return emptyMap() }
        val out = LinkedHashMap<String, String>()
        var bytes = 2
        for ((k, v) in full) {
            val cost = k.toByteArray(Charsets.UTF_8).size + v.toByteArray(Charsets.UTF_8).size + 6
            if (bytes + cost > CallRepository.MAX_EXTRA_BYTES) break
            out[k] = v
            bytes += cost
        }
        return out
    }
}
