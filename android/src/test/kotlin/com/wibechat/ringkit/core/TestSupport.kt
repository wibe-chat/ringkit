package com.wibechat.ringkit.core

import java.io.File
import org.json.JSONObject

/** Deterministic, reboot-simulating TimeSource for the generated suites. */
class FakeTime(
    var elapsed: Long = 100_000L,
    var wall: Long = 1_751_700_000_000L, // 2025-07-05T…Z — display only
    var boot: Int = 7,
) : TimeSource {
    override fun elapsedMs(): Long = elapsed

    override fun wallMs(): Long = wall

    override fun bootCount(): Int = boot

    fun advanceMs(deltaMs: Long) {
        elapsed += deltaMs
        wall += deltaMs
    }

    /** Reboot: bootCount bumps, elapsedRealtime restarts, wall clock keeps going. */
    fun reboot(times: Int = 1) {
        boot += times
        elapsed = 1_000L
    }
}

object TestSupport {
    fun newTempDir(): File =
        File.createTempFile("ringkit-test", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }

    fun loadSpec(name: String): JSONObject {
        val stream =
            checkNotNull(TestSupport::class.java.classLoader.getResourceAsStream("spec/$name")) {
                "spec/$name not on test classpath"
            }
        return JSONObject(stream.reader(Charsets.UTF_8).readText())
    }

    fun incomingCall(
        id: String = "call-1",
        name: String = "Alice",
        sentAtWallMs: Long? = null,
        timeoutSeconds: Int? = null,
        avatarUrl: String? = null,
        extra: Map<String, String> = emptyMap(),
        missedCallShow: Boolean = true,
    ): CoreIncomingCall =
        CoreIncomingCall(
            id = id,
            callerName = name,
            avatarUrl = avatarUrl,
            timeoutSeconds = timeoutSeconds,
            sentAtWallMs = sentAtWallMs,
            extra = extra,
            options = CoreCallOptions(missedCall = CoreMissedCallOptions(show = missedCallShow)),
        )
}
