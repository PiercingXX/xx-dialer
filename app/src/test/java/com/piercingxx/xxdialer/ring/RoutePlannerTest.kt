package com.piercingxx.xxdialer.ring

import com.piercingxx.xxdialer.core.CallerFacts
import com.piercingxx.xxdialer.core.Tone
import com.piercingxx.xxdialer.core.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Routing decisions (§10, D11) — pure planner, no Android. The registry side
 * (channel minting) is device behavior and stays AMBIGUOUS-on-JVM.
 */
class RoutePlannerTest {

    private val facts = CallerFacts(
        number = "+15551234567",
        saved = false,
        starred = false,
        bizTier = false,
        sendToVoicemail = false,
        userBlocked = false,
        stirFailed = false,
        repeatCaller = false,
        recentOutgoing = false,
        cnapName = null,
        emergencyWindow = false,
    )

    @Test
    fun `default-tier ring without custom tone routes to the default channel`() {
        val plan = RoutePlanner.plan(Verdict.Ring(Tone.DEFAULT), customRingtoneRaw = null, lookupKey = null)
        assertEquals(RoutePlan.Default, plan)
    }

    @Test
    fun `custom ringtone beats the tier tone on the default tier (D11)`() {
        val plan = RoutePlanner.plan(
            Verdict.Ring(Tone.DEFAULT),
            customRingtoneRaw = "content://media/internal/audio/media/3",
            lookupKey = "lookup-42",
        )
        val custom = assertIs<RoutePlan.Custom>(plan)
        assertEquals("lookup-42", custom.lookupKey)
    }

    @Test
    fun `blank or keyless custom tone degrades to the default channel`() {
        assertEquals(
            RoutePlan.Default,
            RoutePlanner.plan(Verdict.Ring(Tone.DEFAULT), "  ", "lookup-42"),
            "blank stored URI is absence",
        )
        assertEquals(
            RoutePlan.Default,
            RoutePlanner.plan(Verdict.Ring(Tone.DEFAULT), "content://x", null),
            "no lookupKey ⇒ no ad-hoc channel possible",
        )
    }

    @Test
    fun `unknown tone is product identity - a custom tone does NOT override it (section 10)`() {
        assertEquals(
            RoutePlan.Unknown,
            RoutePlanner.plan(Verdict.Ring(Tone.UNKNOWN), "content://media/x", "lookup-42"),
        )
        assertEquals(RoutePlan.Unknown, RoutePlanner.plan(Verdict.Ring(Tone.UNKNOWN), null, null))
    }

    @Test
    fun `silence routes to the silent channel choice regardless of reason`() {
        val plan = RoutePlanner.plan(
            Verdict.Silence(com.piercingxx.xxdialer.core.Reason.UNKNOWN_OUTSIDE_WINDOW),
            null,
            null,
        )
        assertEquals(RoutePlan.Silent, plan)
    }

    @Test
    fun `block is defensively None - blocks never ring here`() {
        assertEquals(RoutePlan.None, RoutePlanner.plan(Verdict.Block, null, null))
    }
}
