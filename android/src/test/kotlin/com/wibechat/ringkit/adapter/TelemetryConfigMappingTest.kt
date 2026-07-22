package com.wibechat.ringkit.adapter

import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.CoreTelemetryConfig
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** §11.5: PigeonMapper carries the telemetry block (it used to DROP it). */
class TelemetryConfigMappingTest {

    private fun pigeonConfig(telemetry: RingKitTelemetryConfig?) =
        RingKitConfig(
            incomingChannelName = "Incoming calls",
            missedChannelName = "Missed calls",
            ongoingChannelName = "Ongoing call",
            audibleChannelName = "Incoming calls (fallback)",
            defaultTimeoutSeconds = 45L,
            connectionTimeoutSeconds = 120L,
            maxConcurrentRinging = 3L,
            telecomIntegration = false,
            manageOngoingCall = true,
            foregroundBehavior = ForegroundBehavior.HEADS_UP,
            notificationIdBase = 424200L,
            logLevel = RingKitLogLevel.ERROR,
            channelSuffix = "",
            telemetry = telemetry,
        )

    @Test
    fun `telemetry maps field-for-field into core`() {
        val core =
            PigeonMapper.toCore(
                pigeonConfig(
                    RingKitTelemetryConfig(
                        endpoint = "https://fleet.example",
                        apiKey = "key-secret",
                        deviceLabel = "bench-A",
                        uploadLevel = RingKitLogLevel.VERBOSE,
                        dailyUploadBudgetKb = 64L,
                    ),
                ),
            )
        assertEquals(
            CoreTelemetryConfig(
                endpoint = "https://fleet.example",
                apiKey = "key-secret",
                deviceLabel = "bench-A",
                uploadLevel = CoreLogLevel.VERBOSE,
                dailyUploadBudgetKb = 64,
            ),
            core.telemetry,
        )
    }

    @Test
    fun `null telemetry stays null — rule 14 inert`() {
        assertNull(PigeonMapper.toCore(pigeonConfig(null)).telemetry)
    }
}
