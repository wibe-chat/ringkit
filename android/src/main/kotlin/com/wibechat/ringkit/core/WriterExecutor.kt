package com.wibechat.ringkit.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The single writer (PLAN §8.9): one background executor owns every
 * CallRepository/EventLog mutation; all persisted-state reads route through it
 * (no main-thread disk I/O, no read-modify-write races). Two concurrent
 * showIncomingCall calls for the same id behave as if sequential (§5.2).
 */
class WriterExecutor internal constructor(private val executor: ExecutorService) {

    constructor() : this(
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ringkit-writer").apply {
                priority = Thread.NORM_PRIORITY
                isDaemon = false
            }
        },
    )

    /** §11.5 catch-log-degrade hook — RingKitCore points this at the RingLog sink. */
    @Volatile
    var onUncaught: ((Throwable) -> Unit)? = null

    /** Fire-and-forget enqueue; exceptions are contained (never kill the writer thread). */
    fun post(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                onUncaught?.invoke(t)
            }
        }
    }

    /** Enqueue and deliver the result via [onResult] (still on the writer thread). */
    fun <T> submit(block: () -> T, onResult: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(block)
            onResult(result)
        }
    }

    /** Blocking helper for tests and synchronous native entry points. */
    fun <T> await(timeoutMs: Long = 5_000L, block: () -> T): T {
        val latch = CountDownLatch(1)
        var out: Result<T>? = null
        executor.execute {
            out = runCatching(block)
            latch.countDown()
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "writer executor timed out" }
        return out!!.getOrThrow()
    }

    /**
     * Like [await] but reports the three outcomes distinctly instead of collapsing
     * a writer-busy timeout into the same failure as a block that threw: callers
     * that must NOT treat "the writer was too busy to answer in time" as an
     * authoritative result (e.g. the FSI bind — a telecom `addCall` can legally
     * hold the writer up to its own budget) can fail-open on [AwaitOutcome.TimedOut]
     * rather than acting on a phantom empty (R45).
     */
    fun <T> awaitCatching(timeoutMs: Long = 5_000L, block: () -> T): AwaitOutcome<T> {
        val latch = CountDownLatch(1)
        var out: Result<T>? = null
        executor.execute {
            out = runCatching(block)
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return AwaitOutcome.TimedOut
        return out!!.fold(
            onSuccess = { AwaitOutcome.Completed(it) },
            onFailure = { AwaitOutcome.Failed(it) },
        )
    }

    fun shutdown() {
        executor.shutdown()
    }
}

/** Three distinct outcomes of [WriterExecutor.awaitCatching]. */
sealed interface AwaitOutcome<out T> {
    /** The block ran and produced [value] (which may itself be null). */
    data class Completed<out T>(val value: T) : AwaitOutcome<T>

    /** The writer did not answer within the budget — no authoritative result. */
    object TimedOut : AwaitOutcome<Nothing>

    /** The block ran but threw [error]. */
    data class Failed(val error: Throwable) : AwaitOutcome<Nothing>
}
