package com.piercingxx.xxphone.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A daily time window over wall-clock local time (design §7, D3): no zone
 * arithmetic anywhere — evaluation uses the LocalDateTime handed in as-is.
 * Start is inclusive, end is exclusive, and `end <= start` wraps past midnight.
 */
data class Window(
    val startMinuteOfDay: Int,   // 09:00 -> 540
    val endMinuteOfDay: Int,     // 17:00 -> 1020
    val daysMask: Int,           // bit 0 = Monday .. bit 6 = Sunday
) {
    init {
        require(startMinuteOfDay in 0..<MINUTES_PER_DAY) { "startMinuteOfDay out of range: $startMinuteOfDay" }
        require(endMinuteOfDay in 0..<MINUTES_PER_DAY) { "endMinuteOfDay out of range: $endMinuteOfDay" }
        require(daysMask in 1..ALL_DAYS) { "daysMask must enable at least one day: $daysMask" }
    }

    /**
     * The arrival day governs the mask even for a wrapping window: a call at
     * 01:00 on Monday belongs to Monday's bit, not to the Saturday/Sunday
     * evening the span started in.
     */
    fun contains(dt: LocalDateTime): Boolean {
        if (daysMask and arrivalBit(dt.dayOfWeek) == 0) return false
        val t = dt.toLocalTime()
        val start = timeAt(startMinuteOfDay)
        val end = timeAt(endMinuteOfDay)
        return if (end <= start) t >= start || t < end else t >= start && t < end
    }

    private fun arrivalBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    companion object {
        const val ALL_DAYS = 0b1111111
        private const val MINUTES_PER_DAY = 24 * 60

        private fun timeAt(minuteOfDay: Int): LocalTime =
            LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    }
}
