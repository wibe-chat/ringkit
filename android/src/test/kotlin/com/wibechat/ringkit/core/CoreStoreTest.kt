package com.wibechat.ringkit.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** §8.10 storage rules: noBackupFilesDir, install-ID, quarantine, config defaults. */
@RunWith(RobolectricTestRunner::class)
class CoreStoreTest {

    private val dir = TestSupport.newTempDir()

    @Test
    fun production_store_lives_under_no_backup_files_dir() {
        val context = RuntimeEnvironment.getApplication()
        CoreStore.open(context).installId // force creation
        val expected = File(context.noBackupFilesDir, CoreStore.DIR_NAME)
        assertTrue(
            "store must live in noBackupFilesDir (R31), found: ${expected.absolutePath}",
            File(expected, "install.json").exists(),
        )
    }

    @Test
    fun install_id_is_stable_within_install_and_fresh_after_wipe() {
        val first = CoreStore.openAt(dir).installId
        val second = CoreStore.openAt(dir).installId
        assertEquals("stable across reopen", first, second)

        // A restore lands in an EMPTY noBackup dir (the OS never restores it):
        // wiping simulates that ⇒ fresh install ID ⇒ install fencing (§8.10).
        dir.deleteRecursively()
        val afterRestore = CoreStore.openAt(dir).installId
        assertNotEquals("fresh id after restore-equivalent wipe", first, afterRestore)
    }

    @Test
    fun cold_install_config_matches_8_10_defaults_table() {
        val config = CoreStore.openAt(dir).readConfig()
        assertEquals("Incoming calls", config.incomingChannelName)
        assertEquals("Missed calls", config.missedChannelName)
        assertEquals("Ongoing call", config.ongoingChannelName)
        assertEquals("Incoming calls (fallback)", config.audibleChannelName)
        assertEquals(45, config.defaultTimeoutSeconds)
        assertEquals(120, config.connectionTimeoutSeconds)
        assertEquals(3, config.maxConcurrentRinging)
        assertEquals(false, config.telecomIntegration)
        assertEquals(true, config.manageOngoingCall)
        assertEquals(CoreForegroundBehavior.HEADS_UP, config.foregroundBehavior)
        assertEquals(424200, config.notificationIdBase)
        assertEquals(CoreLogLevel.ERROR, config.logLevel)
        assertEquals("", config.channelSuffix)
        assertNull(config.notificationIcon)
        assertNull(config.defaultRingtone)
    }

    @Test
    fun config_is_last_writer_wins_and_survives_reopen() {
        val store = CoreStore.openAt(dir)
        store.writeConfig(CoreConfig(defaultTimeoutSeconds = 60, manageOngoingCall = false))
        store.writeConfig(CoreConfig(defaultTimeoutSeconds = 30))

        val reread = CoreStore.openAt(dir).readConfig()
        assertEquals(30, reread.defaultTimeoutSeconds)
        assertEquals("last writer wins wholesale", true, reread.manageOngoingCall)
    }

    @Test
    fun invalid_config_values_clamp_on_read_never_throw() {
        val store = CoreStore.openAt(dir)
        // Simulate an older/corrupted writer: out-of-range values on disk.
        File(dir, "config.json").writeText(
            """{"v":1,"data":{"defaultTimeoutSeconds":999,"maxConcurrentRinging":99,"connectionTimeoutSeconds":-5,"logLevel":"chatty"}}""",
        )
        val config = store.readConfig()
        assertEquals(CoreConfig.MAX_TIMEOUT_SECONDS, config.defaultTimeoutSeconds)
        assertEquals(CoreConfig.MAX_CONCURRENT_RINGING, config.maxConcurrentRinging)
        assertEquals(120, config.connectionTimeoutSeconds)
        assertEquals("unknown enum falls back", CoreLogLevel.ERROR, config.logLevel)
    }

    @Test
    fun corrupt_file_is_quarantined_not_fatal() {
        val store = CoreStore.openAt(dir)
        store.writeCalls(emptyList())
        File(dir, "calls.json").writeText("PK this is not json")

        val reopened = CoreStore.openAt(dir)
        val calls = reopened.readCalls()
        assertTrue("read never throws (R23)", calls.isEmpty())
        assertEquals(1, reopened.quarantinedCount)
        assertTrue(
            "corrupt file kept aside for diagnosis",
            File(dir, "calls.json.quarantined").exists(),
        )
    }

    @Test
    fun corrupt_entries_are_skipped_leniently() {
        val store = CoreStore.openAt(dir)
        File(dir, "calls.json").writeText(
            """{"v":1,"data":{"calls":[{"garbage":true},""" +
                CoreJson.callRecord(
                    CallRecord(
                        info =
                            CoreCallInfo(
                                "ok-1", "Alice", null, null,
                                CoreCallType.AUDIO, CoreCallDirection.INCOMING, emptyMap(),
                            ),
                        state = CoreCallState.RINGING,
                        options = CoreCallOptions(),
                        timeoutSeconds = 45,
                        shownAtWallMs = 1L,
                        shownAtElapsedMs = 1L,
                        bootCount = 1,
                        ringDeadlineElapsedMs = 10L,
                        connectionDeadlineElapsedMs = null,
                        surfacePosted = false,
                    ),
                ).toString() + "]}}",
        )
        val calls = store.readCalls()
        assertEquals(listOf("ok-1"), calls.map { it.callId })
        assertEquals(1, store.quarantinedCount)
    }

    @Test
    fun unknown_fields_are_ignored_forward_compat() {
        val store = CoreStore.openAt(dir)
        File(dir, "config.json").writeText(
            """{"v":99,"data":{"defaultTimeoutSeconds":50,"futureKnob":"on"}}""",
        )
        assertEquals(50, store.readConfig().defaultTimeoutSeconds)
    }

    @Test
    fun ring_trace_buffer_is_bounded_at_50() {
        val traceBuffer = RecordingTrace()
        repeat(60) { traceBuffer.trace("t", null, "entry $it") }
        assertEquals(CoreDurations.MAX_RING_TRACES, traceBuffer.entries.size)
        assertEquals("entry 10", traceBuffer.entries.first().message)
    }
}
