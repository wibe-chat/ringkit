package com.wibechat.ringkit.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GENERATED SUITE — one test per §7.5 wire-table row/rule, driven by
 * `spec/wire_table.json` (normative). Parser behavior changes require updating
 * the table in the same commit (CLAUDE.md).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [26, 35])
class WireTableTest(private val case: WireCase) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val spec = TestSupport.loadSpec("wire_table.json")
            val base = spec.getJSONObject("base")
            val rows = spec.getJSONArray("cases")
            val out = ArrayList<Array<Any>>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                out.add(
                    arrayOf(
                        WireCase(
                            name = row.getString("name"),
                            payload = buildPayload(base, row),
                            serverSentAtMs =
                                if (row.has("serverSentAtMs")) row.getLong("serverSentAtMs") else null,
                            expect = row.getJSONObject("expect"),
                        ),
                    ),
                )
            }
            return out
        }

        private fun buildPayload(base: JSONObject, row: JSONObject): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            if (!row.optBoolean("noBase")) {
                base.keys().forEach { k -> out[k] = base.getString(k) }
            }
            val payload = row.getJSONObject("payload")
            payload.keys().forEach { k ->
                if (payload.isNull(k)) {
                    out.remove(k)
                } else {
                    out[k] = expand(payload.getString(k))
                }
            }
            return out
        }

        /** "x*129" → x repeated 129; OVERSIZE_EXTRA → >1 KB JSON object. */
        private fun expand(value: String): String {
            val starMatch = Regex("^(.)\\*(\\d+)$").find(value)
            if (starMatch != null) {
                return starMatch.groupValues[1].repeat(starMatch.groupValues[2].toInt())
            }
            if (value == "OVERSIZE_EXTRA") {
                val o = JSONObject()
                for (i in 0 until 40) o.put("key$i", "v".repeat(40))
                return o.toString()
            }
            return value
        }
    }

    data class WireCase(
        val name: String,
        val payload: Map<String, String>,
        val serverSentAtMs: Long?,
        val expect: JSONObject,
    ) {
        override fun toString(): String = name
    }

    @Test
    fun row() {
        val result = RkWireParser.parse(case.payload, case.serverSentAtMs, RecordingTrace())
        val expect = case.expect

        when (expect.getString("kind")) {
            "notRingKit" -> assertTrue("not-ringkit", result is RkWireParser.ParseResult.NotRingKit)
            "rejected" -> assertTrue("rejected, got $result", result is RkWireParser.ParseResult.Rejected)
            "cancel" -> {
                val cancel = result as RkWireParser.ParseResult.Cancel
                if (expect.has("callId")) assertEquals(expand(expect.getString("callId")), cancel.callId)
                if (expect.has("cancelReason")) {
                    assertEquals(expect.getString("cancelReason"), cancel.reason.name)
                }
            }
            "incoming" -> {
                val call = (result as RkWireParser.ParseResult.Incoming).call
                if (expect.has("callId")) assertEquals(expand(expect.getString("callId")), call.id)
                if (expect.has("callerName")) assertEquals(expect.getString("callerName"), call.callerName)
                if (expect.has("callerHandle")) assertEquals(expect.getString("callerHandle"), call.callerHandle)
                if (expect.has("avatarUrl")) {
                    if (expect.isNull("avatarUrl")) assertNull(call.avatarUrl)
                    else assertEquals(expect.getString("avatarUrl"), call.avatarUrl)
                }
                if (expect.has("callType")) assertEquals(expect.getString("callType"), call.type.name)
                if (expect.has("timeoutSeconds")) {
                    if (expect.isNull("timeoutSeconds")) assertNull(call.timeoutSeconds)
                    else assertEquals(expect.getInt("timeoutSeconds"), call.timeoutSeconds)
                }
                if (expect.has("sentAt")) {
                    if (expect.isNull("sentAt")) assertNull(call.sentAtWallMs)
                    else assertEquals(expect.getLong("sentAt"), call.sentAtWallMs)
                }
                if (expect.has("vibrate")) assertEquals(expect.getBoolean("vibrate"), call.options.vibrate)
                if (expect.has("visibility")) {
                    assertEquals(expect.getString("visibility"), call.options.lockScreenVisibility.name)
                }
                if (expect.has("behavior")) {
                    assertEquals(expect.getString("behavior"), call.options.lockScreenBehavior.name)
                }
                if (expect.has("ringtone")) {
                    if (expect.isNull("ringtone")) assertNull(call.options.ringtone)
                    else assertEquals(expect.getString("ringtone"), call.options.ringtone)
                }
                if (expect.has("background")) {
                    if (expect.isNull("background")) assertNull(call.options.fullScreenBackground)
                    else assertEquals(expect.getString("background"), call.options.fullScreenBackground)
                }
                if (expect.has("acceptColor")) {
                    if (expect.isNull("acceptColor")) assertNull(call.options.colors.acceptArgb)
                    else assertEquals(expect.getInt("acceptColor"), call.options.colors.acceptArgb)
                }
                if (expect.has("declineColor")) assertEquals(expect.getInt("declineColor"), call.options.colors.declineArgb)
                if (expect.has("accentColor")) assertEquals(expect.getInt("accentColor"), call.options.colors.accentArgb)
                if (expect.has("useTelecom")) {
                    if (expect.isNull("useTelecom")) assertNull(call.options.useTelecom)
                    else assertEquals(expect.getBoolean("useTelecom"), call.options.useTelecom)
                }
                if (expect.has("avatarHeader")) {
                    val pair = expect.getJSONArray("avatarHeader")
                    assertEquals(pair.getString(1), call.options.avatarHeaders[pair.getString(0)])
                }
                if (expect.optBoolean("avatarHeadersEmpty")) assertTrue(call.options.avatarHeaders.isEmpty())
                if (expect.has("extraEntry")) {
                    val pair = expect.getJSONArray("extraEntry")
                    assertEquals(pair.getString(1), call.extra[pair.getString(0)])
                }
                if (expect.optBoolean("extraEmpty")) assertTrue("extra empty", call.extra.isEmpty())
                if (expect.optBoolean("extraUnderLimit")) {
                    assertTrue("extra truncated but non-empty", call.extra.isNotEmpty())
                    var bytes = 2
                    call.extra.forEach { (k, v) ->
                        bytes += k.toByteArray(Charsets.UTF_8).size + v.toByteArray(Charsets.UTF_8).size + 6
                    }
                    assertTrue("under 1 KB after truncation", bytes <= CallRepository.MAX_EXTRA_BYTES)
                }
                if (expect.has("missedShow")) assertEquals(expect.getBoolean("missedShow"), call.options.missedCall.show)
                if (expect.has("missedTitle")) assertEquals(expect.getString("missedTitle"), call.options.missedCall.title)
                if (expect.has("missedSubtitle")) {
                    assertEquals(expect.getString("missedSubtitle"), call.options.missedCall.subtitle)
                }
                if (expect.has("missedShowCallback")) {
                    assertEquals(expect.getBoolean("missedShowCallback"), call.options.missedCall.showCallbackAction)
                }
                if (expect.has("missedCallbackText")) {
                    assertEquals(expect.getString("missedCallbackText"), call.options.missedCall.callbackText)
                }
            }
            else -> error("unknown expect kind")
        }
    }
}
