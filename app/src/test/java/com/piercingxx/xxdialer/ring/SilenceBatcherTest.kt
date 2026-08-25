package com.piercingxx.xxdialer.ring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Burst-window decision (§12) — pure JVM. */
class SilenceBatcherTest {

    @Test
    fun `first entry posts a single card and opens a burst`() {
        val outcome = SilenceBatcher.next(SilenceBatcher.State(), nowMillis = 1_000L)
        val single = assertIs<SilenceBatcher.Outcome.Single>(outcome)
        assertEquals(1_000L, single.state.lastPostAtMillis)
        assertEquals(1, single.state.count)
    }

    @Test
    fun `entry inside the window collapses into the batch`() {
        val state = SilenceBatcher.State(lastPostAtMillis = 0L, count = 1)
        val outcome = SilenceBatcher.next(state, nowMillis = 59_999L)
        val batch = assertIs<SilenceBatcher.Outcome.Batch>(outcome)
        assertEquals(2, batch.count)
        assertEquals(59_999L, batch.state.lastPostAtMillis)
    }

    @Test
    fun `exactly sixty seconds is still inside the window (inclusive)`() {
        val outcome = SilenceBatcher.next(SilenceBatcher.State(0L, 4), nowMillis = SilenceBatcher.BATCH_WINDOW_MS)
        assertEquals(5, assertIs<SilenceBatcher.Outcome.Batch>(outcome).count)
    }

    @Test
    fun `entry past the window starts a fresh single card`() {
        val outcome = SilenceBatcher.next(SilenceBatcher.State(0L, 3), nowMillis = 60_001L)
        val single = assertIs<SilenceBatcher.Outcome.Single>(outcome)
        assertEquals(1, single.state.count, "burst closed — count resets")
        assertEquals(60_001L, single.state.lastPostAtMillis)
    }

    @Test
    fun `a long drip keeps refreshing one card instead of stacking`() {
        var state = SilenceBatcher.State()
        // seven calls, one per minute minus one second: always inside the window
        repeat(7) { i ->
            val now = (i + 1) * 59_000L
            when (val o = SilenceBatcher.next(state, now)) {
                is SilenceBatcher.Outcome.Single -> state = o.state
                is SilenceBatcher.Outcome.Batch -> state = o.state
            }
        }
        assertEquals(7, state.count, "§12: an out-of-window spam run never stacks seven cards")
    }
}
