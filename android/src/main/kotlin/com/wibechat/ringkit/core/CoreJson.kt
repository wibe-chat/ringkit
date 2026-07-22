package com.wibechat.ringkit.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON (de)serialization for every persisted artifact (PLAN §8.10): versioned
 * envelopes, lenient reads (unknown fields ignored, invalid values fall back,
 * never throw on read — corrupt entries are quarantined by the store, R23).
 *
 * org.json ships in the Android SDK — zero third-party deps in core (D9).
 */
internal object CoreJson {
    const val SCHEMA_VERSION = 1

    // ── generic helpers ──────────────────────────────────────────────────

    fun envelope(data: JSONObject): JSONObject =
        JSONObject().put("v", SCHEMA_VERSION).put("data", data)

    /** Returns the data payload, or null if the envelope is unreadable. */
    fun openEnvelope(raw: String): JSONObject? =
        try {
            val o = JSONObject(raw)
            // Lenient: future versions are read best-effort; unknown fields ignore.
            o.optJSONObject("data")
        } catch (_: Exception) {
            null
        }

    private fun JSONObject.putMap(key: String, map: Map<String, String>): JSONObject {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return put(key, o)
    }

    private fun JSONObject.optMap(key: String): Map<String, String> {
        val o = optJSONObject(key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k) }
        return out
    }

    private inline fun <reified E : Enum<E>> JSONObject.optEnum(key: String, default: E): E {
        val raw = optString(key, "")
        return try {
            if (raw.isEmpty()) default else enumValueOf<E>(raw)
        } catch (_: IllegalArgumentException) {
            default
        }
    }

    private inline fun <reified E : Enum<E>> JSONObject.optEnumOrNull(key: String): E? {
        val raw = optString(key, "")
        return try {
            if (raw.isEmpty()) null else enumValueOf<E>(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    // ── CoreCallInfo ─────────────────────────────────────────────────────

    fun callInfo(v: CoreCallInfo): JSONObject =
        JSONObject()
            .put("id", v.id)
            .put("name", v.name)
            .put("handle", v.handle)
            .put("avatarUrl", v.avatarUrl)
            .put("type", v.type.name)
            .put("direction", v.direction.name)
            .putMap("extra", v.extra)

    fun callInfo(o: JSONObject): CoreCallInfo? {
        val id = o.optStringOrNull("id") ?: return null
        return CoreCallInfo(
            id = id,
            name = o.optString("name", ""),
            handle = o.optStringOrNull("handle"),
            avatarUrl = o.optStringOrNull("avatarUrl"),
            type = o.optEnum("type", CoreCallType.AUDIO),
            direction = o.optEnum("direction", CoreCallDirection.INCOMING),
            extra = o.optMap("extra"),
        )
    }

    // ── CoreCallOptions ──────────────────────────────────────────────────

    fun callOptions(v: CoreCallOptions): JSONObject =
        JSONObject()
            .put("ringtone", v.ringtone)
            .put("vibrate", v.vibrate)
            .put(
                "missedCall",
                JSONObject()
                    .put("show", v.missedCall.show)
                    .put("title", v.missedCall.title)
                    .put("subtitle", v.missedCall.subtitle)
                    .put("showCallbackAction", v.missedCall.showCallbackAction)
                    .put("callbackText", v.missedCall.callbackText),
            )
            .put(
                "colors",
                JSONObject()
                    .put("accept", v.colors.acceptArgb)
                    .put("decline", v.colors.declineArgb)
                    .put("accent", v.colors.accentArgb),
            )
            .put("fullScreenBackground", v.fullScreenBackground)
            .put("lockScreenVisibility", v.lockScreenVisibility.name)
            .put("lockScreenBehavior", v.lockScreenBehavior.name)
            .put("useTelecom", v.useTelecom)
            .putMap("avatarHeaders", v.avatarHeaders)

    fun callOptions(o: JSONObject?): CoreCallOptions {
        if (o == null) return CoreCallOptions()
        val missed = o.optJSONObject("missedCall")
        val colors = o.optJSONObject("colors")
        return CoreCallOptions(
            ringtone = o.optStringOrNull("ringtone"),
            vibrate = o.optBoolean("vibrate", true),
            missedCall =
                CoreMissedCallOptions(
                    show = missed?.optBoolean("show", true) ?: true,
                    title = missed?.optStringOrNull("title"),
                    subtitle = missed?.optStringOrNull("subtitle"),
                    showCallbackAction = missed?.optBoolean("showCallbackAction", true) ?: true,
                    callbackText = missed?.optStringOrNull("callbackText"),
                ),
            colors =
                CoreAccentColors(
                    acceptArgb = colors?.optIntOrNull("accept"),
                    declineArgb = colors?.optIntOrNull("decline"),
                    accentArgb = colors?.optIntOrNull("accent"),
                ),
            fullScreenBackground = o.optStringOrNull("fullScreenBackground"),
            lockScreenVisibility = o.optEnum("lockScreenVisibility", CoreNotificationVisibility.PUBLIC),
            lockScreenBehavior = o.optEnum("lockScreenBehavior", CoreLockScreenBehavior.FULL_SCREEN),
            useTelecom = if (o.has("useTelecom") && !o.isNull("useTelecom")) o.optBoolean("useTelecom") else null,
            avatarHeaders = o.optMap("avatarHeaders"),
        )
    }

    // ── CallRecord ───────────────────────────────────────────────────────

    fun callRecord(v: CallRecord): JSONObject =
        JSONObject()
            .put("info", callInfo(v.info))
            .put("state", v.state.name)
            .put("options", callOptions(v.options))
            .put("timeoutSeconds", v.timeoutSeconds)
            .put("shownAtWallMs", v.shownAtWallMs)
            .put("shownAtElapsedMs", v.shownAtElapsedMs)
            .put("bootCount", v.bootCount)
            .put("ringDeadlineElapsedMs", v.ringDeadlineElapsedMs)
            .put("connectionDeadlineElapsedMs", v.connectionDeadlineElapsedMs)
            .put("surfacePosted", v.surfacePosted)
            .put("connectedAtElapsedMs", v.connectedAtElapsedMs)
            .put("connectedAtWallMs", v.connectedAtWallMs)

    fun callRecord(o: JSONObject): CallRecord? {
        val info = o.optJSONObject("info")?.let { callInfo(it) } ?: return null
        val state = o.optEnumOrNull<CoreCallState>("state") ?: return null
        return CallRecord(
            info = info,
            state = state,
            options = callOptions(o.optJSONObject("options")),
            timeoutSeconds = o.optInt("timeoutSeconds", 45),
            shownAtWallMs = o.optLong("shownAtWallMs", 0L),
            shownAtElapsedMs = o.optLong("shownAtElapsedMs", 0L),
            bootCount = o.optInt("bootCount", 0),
            ringDeadlineElapsedMs = o.optLongOrNull("ringDeadlineElapsedMs"),
            connectionDeadlineElapsedMs = o.optLongOrNull("connectionDeadlineElapsedMs"),
            surfacePosted = o.optBoolean("surfacePosted", false),
            connectedAtElapsedMs = o.optLongOrNull("connectedAtElapsedMs"),
            connectedAtWallMs = o.optLongOrNull("connectedAtWallMs"),
        )
    }

    // ── Tombstones ───────────────────────────────────────────────────────

    fun tombstone(v: Tombstone): JSONObject =
        JSONObject()
            .put("callId", v.callId)
            .put("terminalType", v.terminalType.name)
            .put("endReason", v.endReason?.name)
            .put("cancelReason", v.cancelReason?.name)
            .put("terminalEventSeq", v.terminalEventSeq)
            .put("callInfo", v.callInfo?.let { callInfo(it) })
            .put("atElapsedMs", v.atElapsedMs)
            .put("atWallMs", v.atWallMs)
            .put("bootCount", v.bootCount)

    fun tombstone(o: JSONObject): Tombstone? {
        val callId = o.optStringOrNull("callId") ?: return null
        val terminal = o.optEnumOrNull<TerminalType>("terminalType") ?: return null
        return Tombstone(
            callId = callId,
            terminalType = terminal,
            endReason = o.optEnumOrNull<CoreEndReason>("endReason"),
            cancelReason = o.optEnumOrNull<CoreCancelReason>("cancelReason"),
            terminalEventSeq = o.optLongOrNull("terminalEventSeq"),
            callInfo = o.optJSONObject("callInfo")?.let { callInfo(it) },
            atElapsedMs = o.optLong("atElapsedMs", 0L),
            atWallMs = o.optLong("atWallMs", 0L),
            bootCount = o.optInt("bootCount", 0),
        )
    }

    fun preTombstone(v: PreTombstone): JSONObject =
        JSONObject()
            .put("callId", v.callId)
            .put("reason", v.reason.name)
            .put("atElapsedMs", v.atElapsedMs)
            .put("atWallMs", v.atWallMs)
            .put("bootCount", v.bootCount)

    fun preTombstone(o: JSONObject): PreTombstone? {
        val callId = o.optStringOrNull("callId") ?: return null
        return PreTombstone(
            callId = callId,
            reason = o.optEnum("reason", CoreCancelReason.CALLER_CANCELLED),
            atElapsedMs = o.optLong("atElapsedMs", 0L),
            atWallMs = o.optLong("atWallMs", 0L),
            bootCount = o.optInt("bootCount", 0),
        )
    }

    // ── Events ───────────────────────────────────────────────────────────

    fun event(v: CoreEvent): JSONObject =
        JSONObject()
            .put("seq", v.seq)
            .put("callId", v.callId)
            .put("type", v.type.name)
            .put("callInfo", callInfo(v.callInfo))
            .put("endReason", v.endReason?.name)
            .put("cancelReason", v.cancelReason?.name)
            .put("wallMs", v.wallMs)
            .put("elapsedMs", v.elapsedMs)
            .put("bootCount", v.bootCount)
            .put("consumed", v.consumed)
            .put("leaseUntilElapsedMs", v.leaseUntilElapsedMs)

    fun event(o: JSONObject): CoreEvent? {
        if (!o.has("seq")) return null
        val callId = o.optStringOrNull("callId") ?: return null
        val type = o.optEnumOrNull<CoreEventType>("type") ?: return null
        val info = o.optJSONObject("callInfo")?.let { callInfo(it) } ?: return null
        return CoreEvent(
            seq = o.optLong("seq"),
            callId = callId,
            type = type,
            callInfo = info,
            endReason = o.optEnumOrNull<CoreEndReason>("endReason"),
            cancelReason = o.optEnumOrNull<CoreCancelReason>("cancelReason"),
            wallMs = o.optLong("wallMs", 0L),
            elapsedMs = o.optLong("elapsedMs", 0L),
            bootCount = o.optInt("bootCount", 0),
            consumed = o.optBoolean("consumed", false),
            leaseUntilElapsedMs = o.optLongOrNull("leaseUntilElapsedMs"),
        )
    }

    // ── Launch action ────────────────────────────────────────────────────

    fun launchAction(v: LaunchAction): JSONObject =
        JSONObject()
            .put("type", v.type.name)
            .put("callInfo", callInfo(v.callInfo))
            .put("sequence", v.sequence)
            .put("alreadyEnded", v.alreadyEnded)

    fun launchAction(o: JSONObject): LaunchAction? {
        val type = o.optEnumOrNull<CoreEventType>("type") ?: return null
        val info = o.optJSONObject("callInfo")?.let { callInfo(it) } ?: return null
        return LaunchAction(
            type = type,
            callInfo = info,
            sequence = o.optLong("sequence", -1L),
            alreadyEnded = o.optBoolean("alreadyEnded", false),
        )
    }

    // ── Config (§8.10 — invalid values clamp/fall back, never throw) ─────

    fun config(v: CoreConfig): JSONObject =
        JSONObject()
            .put("incomingChannelName", v.incomingChannelName)
            .put("missedChannelName", v.missedChannelName)
            .put("ongoingChannelName", v.ongoingChannelName)
            .put("audibleChannelName", v.audibleChannelName)
            .put("defaultTimeoutSeconds", v.defaultTimeoutSeconds)
            .put("connectionTimeoutSeconds", v.connectionTimeoutSeconds)
            .put("maxConcurrentRinging", v.maxConcurrentRinging)
            .put("telecomIntegration", v.telecomIntegration)
            .put("manageOngoingCall", v.manageOngoingCall)
            .put("foregroundBehavior", v.foregroundBehavior.name)
            .put("notificationIcon", v.notificationIcon)
            .put("defaultRingtone", v.defaultRingtone)
            .put("notificationIdBase", v.notificationIdBase)
            .put("logLevel", v.logLevel.name)
            .put("channelSuffix", v.channelSuffix)
            .apply {
                v.telemetry?.let { t ->
                    put(
                        "telemetry",
                        JSONObject()
                            .put("endpoint", t.endpoint)
                            .put("apiKey", t.apiKey)
                            .put("deviceLabel", t.deviceLabel)
                            .put("uploadLevel", t.uploadLevel.name)
                            .put("dailyUploadBudgetKb", t.dailyUploadBudgetKb),
                    )
                }
            }

    /** §11.5: absent/invalid telemetry block ⇒ null ⇒ module inert (rule 14). */
    private fun telemetryConfig(o: JSONObject?): CoreTelemetryConfig? {
        if (o == null) return null
        val endpoint = o.optString("endpoint", "")
        val apiKey = o.optString("apiKey", "")
        if (endpoint.isBlank() || apiKey.isBlank()) return null
        val d = CoreTelemetryConfig(endpoint = endpoint, apiKey = apiKey, deviceLabel = "")
        return CoreTelemetryConfig(
            endpoint = endpoint,
            apiKey = apiKey,
            deviceLabel = o.optString("deviceLabel", ""),
            uploadLevel = o.optEnum("uploadLevel", d.uploadLevel),
            dailyUploadBudgetKb =
                o.optInt("dailyUploadBudgetKb", d.dailyUploadBudgetKb)
                    .let { if (it <= 0) d.dailyUploadBudgetKb else it },
        )
    }

    fun config(o: JSONObject?): CoreConfig {
        if (o == null) return CoreConfig()
        val d = CoreConfig()
        fun str(key: String, default: String): String {
            val s = o.optString(key, default)
            return if (s.isBlank()) default else s
        }
        return CoreConfig(
            incomingChannelName = str("incomingChannelName", d.incomingChannelName),
            missedChannelName = str("missedChannelName", d.missedChannelName),
            ongoingChannelName = str("ongoingChannelName", d.ongoingChannelName),
            audibleChannelName = str("audibleChannelName", d.audibleChannelName),
            defaultTimeoutSeconds =
                CoreConfig.clampTimeout(o.optInt("defaultTimeoutSeconds", d.defaultTimeoutSeconds)),
            connectionTimeoutSeconds =
                o.optInt("connectionTimeoutSeconds", d.connectionTimeoutSeconds)
                    .let { if (it <= 0) d.connectionTimeoutSeconds else it },
            maxConcurrentRinging =
                CoreConfig.clampMaxRinging(o.optInt("maxConcurrentRinging", d.maxConcurrentRinging)),
            telecomIntegration = o.optBoolean("telecomIntegration", d.telecomIntegration),
            manageOngoingCall = o.optBoolean("manageOngoingCall", d.manageOngoingCall),
            foregroundBehavior = o.optEnum("foregroundBehavior", d.foregroundBehavior),
            notificationIcon = o.optStringOrNull("notificationIcon"),
            defaultRingtone = o.optStringOrNull("defaultRingtone"),
            notificationIdBase = o.optInt("notificationIdBase", d.notificationIdBase),
            logLevel = o.optEnum("logLevel", d.logLevel),
            channelSuffix = o.optString("channelSuffix", d.channelSuffix),
            telemetry = telemetryConfig(o.optJSONObject("telemetry")),
        )
    }

    // ── Registry ─────────────────────────────────────────────────────────

    fun registry(map: Map<String, Int>): JSONObject {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    fun registry(o: JSONObject?): Map<String, Int> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        o.keys().forEach { k ->
            val v = o.optInt(k, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) out[k] = v
        }
        return out
    }

    // ── list helpers ─────────────────────────────────────────────────────

    fun <T> array(items: List<T>, write: (T) -> JSONObject): JSONArray {
        val arr = JSONArray()
        items.forEach { arr.put(write(it)) }
        return arr
    }

    /** Lenient list read: unparseable entries are skipped and counted. */
    fun <T> list(arr: JSONArray?, read: (JSONObject) -> T?): Pair<List<T>, Int> {
        if (arr == null) return emptyList<T>() to 0
        val out = ArrayList<T>(arr.length())
        var skipped = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            val parsed = obj?.let { runCatching { read(it) }.getOrNull() }
            if (parsed != null) out.add(parsed) else skipped++
        }
        return out to skipped
    }
}
