package com.wibechat.ringkit.adapter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.core.CoreCallDirection
import com.wibechat.ringkit.core.CoreCallInfo
import com.wibechat.ringkit.core.CoreCallType
import com.wibechat.ringkit.core.CoreEvent
import com.wibechat.ringkit.core.CoreEventType
import com.wibechat.ringkit.core.RingKitCore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §10.2 rung 2 — the runner's writer-thread decision + lease. The engine boot is
 * device-only (a Dart VM), seamed out here; these cover the branch that gates
 * whether a headless run even happens (unregistered/nothing/stale/leased) and
 * the lease that fences the batch from the main engine.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundEngineRunnerTest {

    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance
    private val bootCalls = mutableListOf<Unit>()

    private val fakeHost =
        object : BackgroundEngineHost {
            override fun boot(context: Context) {
                bootCalls.add(Unit)
            }

            override fun scheduleTeardown() = Unit
        }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RingKitCore.resetForTests()
        core = RingKitCore.get(context)
    }

    @After
    fun tearDown() {
        RingKitCore.resetForTests()
    }

    private fun runner(resolvable: Boolean) =
        BackgroundEngineRunner(context, core, engineHost = fakeHost, callbackResolvable = { resolvable })

    private fun append(type: CoreEventType): Long {
        val seq = core.repository.eventLog.drawSeq()
        val id = "c-$seq"
        core.repository.eventLog.append(
            CoreEvent(
                seq = seq,
                callId = id,
                type = type,
                callInfo = CoreCallInfo(id, "Alice", null, null, CoreCallType.AUDIO, CoreCallDirection.INCOMING, emptyMap()),
                wallMs = core.time.wallMs(),
                elapsedMs = core.time.elapsedMs(),
                bootCount = core.time.bootCount(),
            ),
        )
        return seq
    }

    @Test
    fun unregistered_when_no_handler_is_registered() {
        append(CoreEventType.DECLINED)
        assertEquals(BackgroundEngineRunner.Prep.UNREGISTERED, runner(resolvable = true).prepareOnWriter())
    }

    @Test
    fun nothing_pending_when_no_actionable_events() {
        core.store.writeBackgroundHandles(1L, 2L)
        append(CoreEventType.ACCEPTED) // launches the app, never headless-delivered
        assertEquals(BackgroundEngineRunner.Prep.NOTHING_PENDING, runner(resolvable = true).prepareOnWriter())
    }

    @Test
    fun stale_handle_skips_without_leasing_events_stay_for_replay() {
        core.store.writeBackgroundHandles(1L, 2L)
        val seq = append(CoreEventType.DECLINED)

        assertEquals(BackgroundEngineRunner.Prep.STALE, runner(resolvable = false).prepareOnWriter())
        assertEquals("un-leased ⇒ still replayable", listOf(seq), core.repository.eventLog.takePending().map { it.seq })
        assertEquals(0, core.repository.eventLog.activeLeaseCount())
    }

    @Test
    fun leases_actionable_batch_when_handler_resolves() {
        core.store.writeBackgroundHandles(1L, 2L)
        val seq = append(CoreEventType.DECLINED)

        assertEquals(BackgroundEngineRunner.Prep.LEASED, runner(resolvable = true).prepareOnWriter())
        assertTrue("leased ⇒ fenced from the main engine", core.repository.eventLog.takePending().isEmpty())
        assertEquals(1, core.repository.eventLog.activeLeaseCount())
    }

    @Test
    fun deliverPending_boots_the_host_only_after_a_lease() {
        core.store.writeBackgroundHandles(1L, 2L)
        append(CoreEventType.DECLINED)

        val leased = awaitDeliver(runner(resolvable = true))
        assertEquals(BackgroundEngineRunner.Prep.LEASED, leased)
        assertEquals(1, bootCalls.size)

        // Stale ⇒ never boots.
        RingKitCore.resetForTests()
        core = RingKitCore.get(context)
        bootCalls.clear()
        core.store.writeBackgroundHandles(1L, 2L)
        append(CoreEventType.DECLINED)
        val stale = awaitDeliver(runner(resolvable = false))
        assertEquals(BackgroundEngineRunner.Prep.STALE, stale)
        assertEquals(0, bootCalls.size)
    }

    private fun awaitDeliver(runner: BackgroundEngineRunner): BackgroundEngineRunner.Prep {
        val latch = CountDownLatch(1)
        var out: BackgroundEngineRunner.Prep? = null
        runner.deliverPending { prep ->
            out = prep
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return out!!
    }
}
