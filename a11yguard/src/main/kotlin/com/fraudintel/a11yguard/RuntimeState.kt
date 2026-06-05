package com.fraudintel.a11yguard

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide, thread-safe evidence store written by the collectors and read by [RiskEngine].
 *
 * Process scope is deliberate: a11y automation frequently spans windows/activities, and the
 * engine reasons over short *time windows* rather than per-screen state. Everything is bounded
 * and self-expiring so memory stays flat and stale evidence never leaks into a later decision.
 */
internal object RuntimeState {

    /** Last input we positively attributed to a real human, in our own window. */
    private val lastGenuineHumanInputAtMs = AtomicLong(0L)

    /** Last time we saw a window-obscured touch (overlay present). */
    private val lastObscuredAtMs = AtomicLong(0L)

    /** Bounded ring of recent classified touches (newest first). */
    private val touches = ConcurrentLinkedDeque<TouchSample>()

    /** Bounded ring of a11y node actions observed on guarded views (newest first). */
    private val nodeActions = ConcurrentLinkedDeque<NodeActionEvent>()

    /** Bounded ring of timestamps when a guarded view's a11y node info was read (scrape proxy). */
    private val nodeReads = ConcurrentLinkedDeque<Long>()

    /** Count of currently-RESUMED activities, for the overlay-concealment heuristic. */
    private val resumed = AtomicInteger(0)

    /** Last time our window lost focus while resumed with no recent human input. */
    private val lastConcealmentAtMs = AtomicLong(0L)

    private const val MAX_TOUCHES = 64
    private const val MAX_NODE_ACTIONS = 64
    private const val MAX_NODE_READS = 128

    fun recordHumanInput(atMs: Long) {
        // monotonic max - never move the clock backwards
        var prev = lastGenuineHumanInputAtMs.get()
        while (atMs > prev && !lastGenuineHumanInputAtMs.compareAndSet(prev, atMs)) {
            prev = lastGenuineHumanInputAtMs.get()
        }
    }

    fun recordObscured(atMs: Long) {
        var prev = lastObscuredAtMs.get()
        while (atMs > prev && !lastObscuredAtMs.compareAndSet(prev, atMs)) {
            prev = lastObscuredAtMs.get()
        }
    }

    fun recordTouch(sample: TouchSample) {
        touches.addFirst(sample)
        while (touches.size > MAX_TOUCHES) touches.pollLast()
        if (sample.classification == TouchClassification.GENUINE_HUMAN) recordHumanInput(sample.atMs)
        if (sample.obscured) recordObscured(sample.atMs)
    }

    fun recordNodeAction(event: NodeActionEvent) {
        nodeActions.addFirst(event)
        while (nodeActions.size > MAX_NODE_ACTIONS) nodeActions.pollLast()
    }

    fun lastHumanInputAtMs(): Long = lastGenuineHumanInputAtMs.get()
    fun lastObscuredAtMs(): Long = lastObscuredAtMs.get()

    /** Node actions observed within [windowMs] of [nowMs], newest first. */
    fun nodeActionsWithin(nowMs: Long, windowMs: Long): List<NodeActionEvent> =
        nodeActions.filter { nowMs - it.atMs in 0..windowMs }

    /** All touch samples within [windowMs] of [nowMs], newest first. */
    fun touchesWithin(nowMs: Long, windowMs: Long): List<TouchSample> =
        touches.filter { nowMs - it.atMs in 0..windowMs }

    /** Genuine human touches within [windowMs] of [nowMs]. */
    fun genuineHumanTouchesWithin(nowMs: Long, windowMs: Long): List<TouchSample> =
        touches.filter { it.classification == TouchClassification.GENUINE_HUMAN && nowMs - it.atMs in 0..windowMs }

    /** Suspected-injected touches within [windowMs] of [nowMs]. */
    fun injectedTouchesWithin(nowMs: Long, windowMs: Long): List<TouchSample> =
        touches.filter { it.classification == TouchClassification.SUSPECT_INJECTED && nowMs - it.atMs in 0..windowMs }

    fun recordNodeRead(atMs: Long) {
        nodeReads.addFirst(atMs)
        while (nodeReads.size > MAX_NODE_READS) nodeReads.pollLast()
    }

    /** Count of guarded-node reads within [windowMs] of [nowMs]. */
    fun nodeReadsWithin(nowMs: Long, windowMs: Long): Int =
        nodeReads.count { nowMs - it in 0..windowMs }

    fun incrementResumed() { resumed.incrementAndGet() }
    fun decrementResumed() { resumed.updateAndGet { if (it > 0) it - 1 else 0 } }
    fun isAnyResumed(): Boolean = resumed.get() > 0

    fun recordConcealment(atMs: Long) {
        var prev = lastConcealmentAtMs.get()
        while (atMs > prev && !lastConcealmentAtMs.compareAndSet(prev, atMs)) prev = lastConcealmentAtMs.get()
    }
    fun lastConcealmentAtMs(): Long = lastConcealmentAtMs.get()

    /** Test/diagnostic hook only. */
    fun reset() {
        lastGenuineHumanInputAtMs.set(0)
        lastObscuredAtMs.set(0)
        touches.clear()
        nodeActions.clear()
        nodeReads.clear()
        resumed.set(0)
        lastConcealmentAtMs.set(0)
    }
}
