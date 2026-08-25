package com.piercingxx.xxdialer.ui

import com.piercingxx.xxdialer.core.StirAction
import com.piercingxx.xxdialer.core.Window

/**
 * The §6 precedence table, rows 1–12 in evaluation order — built with LIVE
 * window values so the Rules screen is the documentation (§12.4). Static
 * condition labels carry dynamic ranges; verdict strings are the exact words
 * the mockup prints. Pure: no android.*.
 */
internal object PrecedenceRows {

    enum class Kind { RING, SILENCE, BLOCK, INFO }

    data class Row(
        val n: Int,
        val condition: String,
        val verdict: String,
        val tone: String?,
        val kind: Kind,
    )

    fun build(
        unknownWindow: Window,
        businessWindow: Window,
        stirAction: StirAction = StirAction.BLOCK,
    ): List<Row> {
        val biz = WindowChips.timeRange(businessWindow)
        val unk = WindowChips.timeRange(unknownWindow)
        return listOf(
            Row(1, "Emergency callback window", "Ring · any time", "default", Kind.RING),
            Row(2, "Contact set to send-to-voicemail", "→ voicemail", null, Kind.INFO),
            Row(3, "System blocklist · block pattern", "Block", null, Kind.BLOCK),
            when (stirAction) {
                StirAction.BLOCK -> Row(4, "STIR failed — forged caller ID", "Block", null, Kind.BLOCK)
                StirAction.SILENCE -> Row(4, "STIR failed — forged caller ID", "Silence", null, Kind.SILENCE)
                StirAction.OFF -> Row(4, "STIR failed — forged caller ID", "off", null, Kind.INFO)
            },
            Row(5, "★ Starred contact", "Ring · any time", "default", Kind.RING),
            Row(6, "Business tier, inside $biz", "Ring", "default", Kind.RING),
            Row(7, "Business tier, outside $biz", "Silence", null, Kind.SILENCE),
            Row(8, "Saved contact", "Ring · any time", "default", Kind.RING),
            Row(9, "You called them, last 48 h", "Ring · any time", "unknown", Kind.RING),
            Row(10, "Silence pattern rule", "Silence", null, Kind.SILENCE),
            Row(11, "Unknown, inside $unk", "Ring", "unknown tone", Kind.RING),
            Row(12, "Unknown, outside $unk", "Silence", null, Kind.SILENCE),
        )
    }
}
