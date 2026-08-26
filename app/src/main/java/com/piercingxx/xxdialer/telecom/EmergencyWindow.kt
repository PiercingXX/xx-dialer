package com.piercingxx.xxdialer.telecom

/**
 * The 24 h post-emergency bypass window (R10, §15 clock row). Pure — no
 * android imports, JVM-tested.
 *
 * The marker stores wall-clock and elapsed-realtime side by side; §15 says
 * "the stricter reading wins". The window CLOSES only when BOTH usable
 * readings agree it is over (`wallFresh || elapsedFresh`):
 *
 * - clock moved forward  → wall looks stale, elapsed still fresh → open
 * - clock moved backward → wall age clamps to fresh, elapsed truthful → open
 * - reboot               → nowElapsed < markerElapsed, elapsed unusable,
 *                          wall decides. (A reset elapsed counter would
 *                          otherwise look "fresh" forever via negative age.)
 */
object EmergencyWindow {

    const val WINDOW_MS: Long = 24L * 60 * 60 * 1000

    fun active(
        nowEpochMillis: Long,
        markerWallMillis: Long?,
        nowElapsedMillis: Long,
        markerElapsedMillis: Long?,
    ): Boolean {
        val wallFresh = markerWallMillis != null && fresh(markerWallMillis, nowEpochMillis)
        val elapsedFresh = markerElapsedMillis != null &&
            nowElapsedMillis >= markerElapsedMillis &&
            fresh(markerElapsedMillis, nowElapsedMillis)
        return wallFresh || elapsedFresh
    }

    /** A future timestamp (clock moved behind the marker) counts as maximally fresh. */
    private fun fresh(markerMillis: Long, nowMillis: Long): Boolean {
        val age = nowMillis - markerMillis
        return age < WINDOW_MS
    }
}
