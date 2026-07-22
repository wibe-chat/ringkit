package com.wibechat.ringkit.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Test

/**
 * §11.5 config plumbing (M2 first move): the telemetry block persists with the
 * §8.10 lenient rules — absent/blank ⇒ null ⇒ module inert (rule 14) — and its
 * toString never carries the apiKey.
 */
class TelemetryConfigJsonTest {

    private val telemetry =
        CoreTelemetryConfig(
            endpoint = "https://fleet.example",
            apiKey = "key-secret",
            deviceLabel = "bench-A",
            uploadLevel = CoreLogLevel.VERBOSE,
            dailyUploadBudgetKb = 64,
        )

    @Test
    fun `telemetry config round-trips through the persisted envelope`() {
        val json = CoreJson.config(CoreConfig(telemetry = telemetry))
        val back = CoreJson.config(json)
        assertEquals(telemetry, back.telemetry)
    }

    @Test
    fun `absent telemetry block reads as null — pre-M2 configs stay valid`() {
        val json = CoreJson.config(CoreConfig())
        assertNull(CoreJson.config(json).telemetry)
    }

    @Test
    fun `blank endpoint or apiKey collapses to null — inert, never a broken uploader`() {
        val noEndpoint = CoreJson.config(CoreConfig(telemetry = telemetry.copy(endpoint = "")))
        assertNull(CoreJson.config(noEndpoint).telemetry)
        val noKey = CoreJson.config(CoreConfig(telemetry = telemetry.copy(apiKey = " ".trim())))
        assertNull(CoreJson.config(noKey).telemetry)
    }

    @Test
    fun `invalid budget falls back to the default`() {
        val json = CoreJson.config(CoreConfig(telemetry = telemetry))
        json.getJSONObject("telemetry").put("dailyUploadBudgetKb", -5)
        assertEquals(200, CoreJson.config(json).telemetry?.dailyUploadBudgetKb)
    }

    @Test
    fun `toString never exposes the apiKey`() {
        assertFalse("key-secret" in telemetry.toString())
        assertFalse("key-secret" in CoreConfig(telemetry = telemetry).toString())
    }
}
