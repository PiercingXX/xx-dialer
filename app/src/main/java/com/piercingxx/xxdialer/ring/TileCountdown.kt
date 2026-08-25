package com.piercingxx.xxdialer.ring

/**
 * Countdown label for the Expecting-a-call tile (§7.1, D15). Pure JVM so the
 * formatting is provable (todo #5). D15: no indefinite variant exists — the
 * type cannot express it — so an absent/expired deadline is simply idle.
 */
internal object TileCountdown {

    const val LABEL_IDLE = "Expecting a call"

    /** "Expecting · 1h59m" while active, the idle label otherwise. */
    fun label(activeUntilMillis: Long?, nowMillis: Long): String =
        if (isActive(activeUntilMillis, nowMillis)) {
            "Expecting · ${duration(activeUntilMillis!! - nowMillis)}"
        } else {
            LABEL_IDLE
        }

    /**
     * Bypass is live only while strictly in the future AND no further out
     * than the maximum configurable duration — a deadline beyond that means
     * the clock moved backwards while armed, so the bypass reads expired
     * (§15; mirrors core RingPolicy.bypassActive's >8 h guard, L5).
     */
    fun isActive(activeUntilMillis: Long?, nowMillis: Long): Boolean =
        activeUntilMillis != null &&
            activeUntilMillis > nowMillis &&
            activeUntilMillis - nowMillis <= MAX_BYPASS_MILLIS

    /** Same bound as RingPolicy's MAX_BYPASS_HOURS = 8 (§7.1 duration options cap at 8 h). */
    const val MAX_BYPASS_MILLIS: Long = 8L * 60 * 60 * 1000

    /**
     * Remaining time as "4m" or "1h59m"; minutes round UP so a live bypass
     * never displays "0m", and hours keep two-digit minutes (tabular, §12.1).
     */
    fun duration(remainingMillis: Long): String {
        if (remainingMillis <= 0) return "0m"
        val totalMinutes = (remainingMillis + 59_999) / 60_000 // ceiling minute
        val hours = totalMinutes / 60
        val minutes = (totalMinutes % 60).toInt()
        return if (hours > 0) "${hours}h${minutes.toString().padStart(2, '0')}m" else "${minutes}m"
    }
}
