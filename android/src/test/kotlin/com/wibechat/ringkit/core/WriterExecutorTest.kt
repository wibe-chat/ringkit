package com.wibechat.ringkit.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WriterExecutor.awaitCatching] must report its three outcomes distinctly — the
 * R45 fix depends on a writer-busy TIMEOUT being separable from a block that ran
 * (whether it returned a value, including null, or threw). Collapsing timeout into
 * failure is exactly the bug that self-destructed the FSI bind mid-ring.
 */
class WriterExecutorTest {

    @Test
    fun completed_carries_the_block_value_including_null() {
        val writer = WriterExecutor()
        try {
            val out = writer.awaitCatching(timeoutMs = 1_000L) { 42 }
            assertTrue(out is AwaitOutcome.Completed)
            assertEquals(42, (out as AwaitOutcome.Completed).value)

            val nullOut = writer.awaitCatching<String?>(timeoutMs = 1_000L) { null }
            assertTrue("a null return is Completed(null), NOT a timeout", nullOut is AwaitOutcome.Completed)
            assertEquals(null, (nullOut as AwaitOutcome.Completed).value)
        } finally {
            writer.shutdown()
        }
    }

    @Test
    fun busy_writer_reports_TimedOut_not_Failed() {
        val writer = WriterExecutor()
        val hold = CountDownLatch(1)
        try {
            // Occupy the single writer thread so the lookup can never run in time.
            writer.post { hold.await(5, TimeUnit.SECONDS) }
            val out = writer.awaitCatching(timeoutMs = 100L) { "unreachable" }
            assertSame(AwaitOutcome.TimedOut, out)
        } finally {
            hold.countDown()
            writer.shutdown()
        }
    }

    @Test
    fun block_that_throws_reports_Failed_with_the_error() {
        val writer = WriterExecutor()
        try {
            val boom = IllegalStateException("boom")
            val out = writer.awaitCatching(timeoutMs = 1_000L) { throw boom }
            assertTrue(out is AwaitOutcome.Failed)
            assertSame(boom, (out as AwaitOutcome.Failed).error)
        } finally {
            writer.shutdown()
        }
    }
}
