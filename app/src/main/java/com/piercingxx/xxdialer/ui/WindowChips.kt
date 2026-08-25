package com.piercingxx.xxdialer.ui

import com.piercingxx.xxdialer.core.Window

/**
 * Pure seam between a core.[Window] and the Rules screen's window chrome
 * (§7, §12.4): daysMask ↔ seven day-toggle chips, clock labels, and the
 * compact "09:00–17:00 · Mon–Fri" line the precedence list prints live.
 *
 * Bit order matches [Window] exactly: bit 0 = Monday .. bit 6 = Sunday.
 * No android.* imports — this is JVM-testable by contract.
 */
internal object WindowChips {

    val DAY_LABELS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    fun dayBit(dayIndex: Int): Int = 1 shl dayIndex

    /** Flip one day bit. Saving a 0 mask is refused upstream (Window requires ≥1). */
    fun toggled(daysMask: Int, dayIndex: Int): Int = daysMask xor dayBit(dayIndex)

    fun chipsFromMask(daysMask: Int): BooleanArray =
        BooleanArray(DAY_LABELS.size) { i -> daysMask and dayBit(i) != 0 }

    fun maskFromChips(selected: List<Boolean>): Int =
        selected.foldIndexed(0) { i, acc, on -> if (on) acc or dayBit(i) else acc }

    /** 540 → "09:00". Tabular by construction (§12.1). */
    fun clockLabel(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** Inclusive-start / exclusive-end bounds, printed as "09:00–17:00". */
    fun timeRange(window: Window): String =
        "${clockLabel(window.startMinuteOfDay)}–${clockLabel(window.endMinuteOfDay)}"

    /** Compressed day list: "every day" · "MON–FRI" · "MON, WED, SAT". */
    fun daysLabel(daysMask: Int): String {
        if (daysMask == Window.ALL_DAYS) return "every day"
        val on = chipsFromMask(daysMask)
        val parts = mutableListOf<String>()
        var i = 0
        while (i < on.size) {
            if (!on[i]) {
                i++
                continue
            }
            var j = i
            while (j + 1 < on.size && on[j + 1]) j++
            parts += if (j == i) DAY_LABELS[i] else "${DAY_LABELS[i]}–${DAY_LABELS[j]}"
            i = j + 1
        }
        return if (parts.isEmpty()) "no days" else parts.joinToString(", ")
    }

    /** One-line summary for rows and editors: range + days. */
    fun windowLine(window: Window): String = "${timeRange(window)} · ${daysLabel(window.daysMask)}"
}
