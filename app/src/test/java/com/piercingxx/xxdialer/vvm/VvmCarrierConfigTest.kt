package com.piercingxx.xxdialer.vvm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VvmCarrierConfigTest {

    @Test
    fun blankOrMissingTypeIsNotAValidConfig() {
        assertFalse(VvmCarrierConfig.isValid(null))
        assertFalse(VvmCarrierConfig.isValid(""))
        assertFalse(VvmCarrierConfig.isValid("   "))
        assertTrue(VvmCarrierConfig.isValid("vvm_type_omtp"))
    }

    @Test
    fun voicemailOnLoadGoesThroughDecide() {
        val src = sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer/ui/VoicemailActivity.kt"),
            File("app/src/main/java/com/piercingxx/xxdialer/ui/VoicemailActivity.kt"),
        ).first { it.exists() }.readText()
        assertTrue(
            src.contains("VvmListState.decide("),
            "renderStateOnLoad must go through VvmListState.decide so Empty is not a lie",
        )
        assertFalse(
            src.contains("renderRows(queryList())"),
            "the skip-decide path that painted Empty on a not-activated mailbox must stay gone",
        )
    }
}
