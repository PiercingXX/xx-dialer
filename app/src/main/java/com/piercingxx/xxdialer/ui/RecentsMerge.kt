package com.piercingxx.xxdialer.ui

import com.piercingxx.xxdialer.ui.VerdictLines.Glyph
import kotlin.math.abs

/**
 * The Recents merge (design §12.1): platform CallLog rows LEFT-JOINed with
 * screen_log rows matched on normalized E.164 + time proximity — a call shows
 * both its disposition glyph and the rule that fired (R7). Pure JVM.
 */
object RecentsMerge {

    /** Screening logs at arrival; CallLog stamps at start/end. ±2 min is a safe join window. */
    const val PROXIMITY_MS: Long = 2L * 60 * 1000

    /** Slim shape of one CallLog row, normalized before merging. */
    data class PlatformCall(
        val id: Long,
        val timeMillis: Long,
        val rawNumber: String?,
        val e164: String?,
        val cachedName: String? = null,
        val missed: Boolean,
        val outgoing: Boolean,
        val blockedUpstream: Boolean,
        val durationSec: Int,
    )

    /** Slim shape of one screen_log row (decoupled from Room for tests). */
    data class LogRow(
        val id: Long,
        val at: Long,
        val e164: String?,
        val verdict: String,
        val reason: String,
        val mode: String,
    )

    data class MergedCall(
        val key: Long,
        val e164: String?,
        val rawNumber: String?,
        val cachedName: String? = null, // CallLog's own contact-name cache
        val displayName: String? = null, // attached by the UI from the mirror
        val timeMillis: Long,
        val missed: Boolean,
        val outgoing: Boolean,
        val durationSec: Int,
        val glyph: Glyph,
        val reasonRaw: String,
        val observed: Boolean,
        val screenLogId: Long?,
        val blockedUpstream: Boolean = false,
    ) {
        /** Withheld/unparseable numbers never group and never deep-link-match. */
        val identity: String? get() = e164
    }

    /**
     * Nearest-in-time pairing, each side consumed at most once: candidate
     * pairs inside [PROXIMITY_MS] are taken closest-first so a rapid redial
     * pairs with its own log row rather than its neighbor's.
     */
    fun merge(
        calls: List<PlatformCall>,
        logs: List<LogRow>,
        proximityMs: Long = PROXIMITY_MS,
    ): List<MergedCall> {
        data class Candidate(val ci: Int, val li: Int, val dist: Long)

        val candidates = ArrayList<Candidate>()
        calls.forEachIndexed { ci, c ->
            if (c.e164 == null) return@forEachIndexed // withheld: nothing to match on
            logs.forEachIndexed { li, l ->
                if (l.e164 != null && l.e164 == c.e164) {
                    val dist = abs(l.at - c.timeMillis)
                    if (dist <= proximityMs) candidates += Candidate(ci, li, dist)
                }
            }
        }
        candidates.sortWith(compareBy({ it.dist }, { -calls[it.ci].timeMillis }))

        val usedCall = BooleanArray(calls.size)
        val usedLog = BooleanArray(logs.size)
        val matchOf = HashMap<Int, LogRow>()
        for ((ci, li) in candidates) {
            if (!usedCall[ci] && !usedLog[li]) {
                usedCall[ci] = true
                usedLog[li] = true
                matchOf[ci] = logs[li]
            }
        }

        return calls
            .mapIndexed { ci, c ->
                val log = matchOf[ci]
                MergedCall(
                    key = c.id,
                    e164 = c.e164,
                    rawNumber = c.rawNumber,
                    cachedName = c.cachedName,
                    timeMillis = c.timeMillis,
                    missed = c.missed,
                    outgoing = c.outgoing,
                    durationSec = c.durationSec,
                    glyph = when {
                        log != null -> glyphOf(log.verdict)
                        c.blockedUpstream -> Glyph.BLOCKED // §8: annotate upstream rejections
                        else -> Glyph.NONE
                    },
                    reasonRaw = log?.reason.orEmpty(),
                    observed = log?.mode == "observed",
                    screenLogId = log?.id,
                    blockedUpstream = log == null && c.blockedUpstream,
                )
            }
            .sortedByDescending { it.timeMillis }
    }

    fun glyphOf(verdict: String): Glyph = when (verdict) {
        "Block" -> Glyph.BLOCKED
        "Silence" -> Glyph.SILENCED
        "Ring" -> Glyph.RING
        else -> Glyph.NONE
    }

    // ---- filter chips (§12.1: All · Missed · Silenced · Blocked) ------------

    enum class Filter { ALL, MISSED, SILENCED, BLOCKED }

    fun passes(filter: Filter, call: MergedCall): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.MISSED -> call.missed
        Filter.SILENCED -> call.glyph == Glyph.SILENCED
        Filter.BLOCKED -> call.glyph == Glyph.BLOCKED
    }

    // ---- grouping of consecutive same-number calls (default ON) -------------

    sealed interface Grouped {
        data class Single(val call: MergedCall) : Grouped

        /** A collapsed run; [calls] keeps newest-first order for expansion. */
        data class Group(
            val identity: String,
            val calls: List<MergedCall>,
            val expanded: Boolean,
        ) : Grouped
    }

    /**
     * Collapse consecutive runs sharing one identity (group_recents setting).
     * Null identities (withheld/hidden) always stand alone; expansion state is
     * carried by the caller so toggling never re-reads providers.
     */
    fun group(items: List<MergedCall>, groupingOn: Boolean, expanded: Set<String>): List<Grouped> {
        if (!groupingOn) return items.map { Grouped.Single(it) }
        val out = ArrayList<Grouped>(items.size)
        var i = 0
        while (i < items.size) {
            val identity = items[i].identity
            var j = i + 1
            if (identity != null) {
                while (j < items.size && items[j].identity == identity) j++
            }
            val run = items.subList(i, if (identity != null) j else i + 1)
            out += if (run.size == 1) {
                Grouped.Single(run[0])
            } else {
                Grouped.Group(identity!!, run.toList(), expanded.contains(identity))
            }
            i = if (identity != null) j else i + 1
        }
        return out
    }
}
