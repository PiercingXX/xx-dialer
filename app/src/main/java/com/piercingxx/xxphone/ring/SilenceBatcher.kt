package com.piercingxx.xxphone.ring

/**
 * Burst-batching decision for silenced-call notifications (§12): consecutive
 * entries inside [BATCH_WINDOW_MS] collapse into one card — "3 silenced
 * calls" — so an out-of-window spam run never stacks seven cards.
 *
 * Pure JVM (todo #5); [SilencedNotifier] owns the in-memory state this
 * consumes. The window is measured against the LAST posted card, so a steady
 * drip keeps one card refreshing instead of accumulating.
 */
internal object SilenceBatcher {

    /** §12 burst window: 60 s of consecutive silencing shares one card. */
    const val BATCH_WINDOW_MS = 60_000L

    data class State(val lastPostAtMillis: Long? = null, val count: Int = 0)

    sealed interface Outcome {
        val state: State

        /** First entry of a burst (or a lone call): post the single card. */
        data class Single(override val state: State) : Outcome

        /** Inside an open burst: replace the card with an N-count batch. */
        data class Batch(val count: Int, override val state: State) : Outcome
    }

    fun next(state: State, nowMillis: Long): Outcome {
        val last = state.lastPostAtMillis
        return if (last != null && nowMillis - last <= BATCH_WINDOW_MS) {
            Outcome.Batch(state.count + 1, State(lastPostAtMillis = nowMillis, count = state.count + 1))
        } else {
            Outcome.Single(State(lastPostAtMillis = nowMillis, count = 1))
        }
    }
}
