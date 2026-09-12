package com.piercingxx.xxdialer.ring

import android.provider.CallLog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissedCallsClearTest {

    @Test
    fun recentsShownOrMissedFilterClearsTheBadge() {
        assertTrue(MissedCallsClear.shouldClear(recentsVisible = true, missedFilterActive = false))
        assertTrue(MissedCallsClear.shouldClear(recentsVisible = false, missedFilterActive = true))
        assertTrue(MissedCallsClear.shouldClear(recentsVisible = true, missedFilterActive = true))
        assertFalse(MissedCallsClear.shouldClear(recentsVisible = false, missedFilterActive = false))
    }

    @Test
    fun viewedUpdateMarksNewZeroOnMissedAndRejected() {
        assertTrue(MissedCallsClear.VIEWED_SELECTION.contains(CallLog.Calls.NEW))
        assertTrue(MissedCallsClear.VIEWED_SELECTION.contains(CallLog.Calls.TYPE))
        val args = MissedCallsClear.viewedArgs().toSet()
        assertTrue(CallLog.Calls.MISSED_TYPE.toString() in args)
        assertTrue(CallLog.Calls.REJECTED_TYPE.toString() in args)
        val clear = source("ring/MissedCallsClear.kt")
        assertTrue(clear.contains("put(CallLog.Calls.NEW, 0)"))
    }

    @Test
    fun recentsActivityCallsClear() {
        val recents = source("ui/RecentsActivity.kt")
        assertTrue(
            recents.contains("MissedCallsClear.clear"),
            "Recents must clear the missed badge on show / Missed chip",
        )
        val clear = source("ring/MissedCallsClear.kt")
        assertTrue(clear.contains("cancelMissedCallsNotification"))
        assertTrue(clear.contains("NotifIds.MISSED"))
        assertTrue(clear.contains("CallLog.Calls.NEW"))
        assertTrue(clear.contains("markViewed"))
        assertTrue(clear.contains("KEY_VIEWED_AT") || clear.contains("viewed_at"))
    }

    private fun source(relative: String): String =
        sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer/$relative"),
            File("app/src/main/java/com/piercingxx/xxdialer/$relative"),
        ).first { it.exists() }.readText()
}
