package com.piercingxx.xxdialer.ring

import android.net.Uri
import com.piercingxx.xxdialer.core.CallerFacts
import com.piercingxx.xxdialer.core.Reason
import com.piercingxx.xxdialer.core.Tone
import com.piercingxx.xxdialer.core.Verdict
import com.piercingxx.xxdialer.data.ContactMirrorEntity

/**
 * Verdict → channel routing (§10). The router picks WHERE a verdict is
 * presented; it never re-decides the verdict, and observe mode never appears
 * here — that gate is last-in-chain upstream (XxInCallService, todo #6).
 */
class RingRouter(private val registry: ChannelRegistry) {

    sealed class Choice {
        /** Post on [channelId]; [customTone] records the D11 override that chose it. */
        data class Ring(val channelId: String, val customTone: Uri?) : Choice()

        /** Caller posts on `ring_silent_v1` — surfaced and answerable, no sound (§6). */
        object Silent : Choice()

        /** No presentation at all. Block lands here defensively; blocks never ring here. */
        object None : Choice()
    }

    /**
     * [facts] rides the dictated signature for per-tier vibration patterns
     * (§10 "plausible v2") — today's routing needs only verdict + mirror.
     */
    suspend fun route(verdict: Verdict, facts: CallerFacts, mirror: ContactMirrorEntity?): Choice =
        when (
            val plan = RoutePlanner.plan(
                verdict,
                customRingtoneRaw = mirror?.customRingtone,
                lookupKey = mirror?.lookupKey,
            )
        ) {
            RoutePlan.Default ->
                Choice.Ring(registry.channelIdFor(ChannelIds.PURPOSE_RING_DEFAULT), null)
            is RoutePlan.Custom -> customChoice(plan.lookupKey, plan.toneRaw)
            RoutePlan.Unknown ->
                Choice.Ring(registry.channelIdFor(ChannelIds.PURPOSE_RING_UNKNOWN), null)
            RoutePlan.Silent -> Choice.Silent
            RoutePlan.None -> Choice.None
        }

    /** A corrupt stored tone URI falls back to the default channel — still rings (§15). */
    private suspend fun customChoice(lookupKey: String, toneRaw: String): Choice {
        val tone = runCatching { Uri.parse(toneRaw) }.getOrNull() ?: return defaultChoice()
        val channelId = runCatching { registry.customChannelFor(lookupKey, tone) }
            .getOrElse { return defaultChoice() }
        return Choice.Ring(channelId, tone)
    }

    private suspend fun defaultChoice(): Choice =
        Choice.Ring(registry.channelIdFor(ChannelIds.PURPOSE_RING_DEFAULT), null)
}

/**
 * Pure decision seam (todo #5): verdict + stored custom ringtone → plan.
 * D11 precedence lives here — a non-blank custom ringtone beats the tier
 * tone, but ONLY on the default tier; the unknown-caller tone is product
 * identity and stays put (§10).
 */
internal sealed interface RoutePlan {
    data class Custom(val lookupKey: String, val toneRaw: String) : RoutePlan
    object Default : RoutePlan
    object Unknown : RoutePlan
    object Silent : RoutePlan
    object None : RoutePlan
}

internal object RoutePlanner {

    fun plan(verdict: Verdict, customRingtoneRaw: String?, lookupKey: String?): RoutePlan =
        when (verdict) {
            is Verdict.Block -> RoutePlan.None // defensive; blocks are disposed of upstream (§6)
            is Verdict.Silence ->
                if (verdict.reason == Reason.SEND_TO_VOICEMAIL) RoutePlan.None
                else RoutePlan.Silent
            is Verdict.Ring -> ring(verdict.tone, customRingtoneRaw?.takeIf { it.isNotBlank() }, lookupKey)
        }

    private fun ring(tone: Tone, custom: String?, lookupKey: String?): RoutePlan =
        when (tone) {
            Tone.UNKNOWN -> RoutePlan.Unknown
            Tone.DEFAULT ->
                if (custom != null && lookupKey != null) RoutePlan.Custom(lookupKey, custom)
                else RoutePlan.Default
        }
}
