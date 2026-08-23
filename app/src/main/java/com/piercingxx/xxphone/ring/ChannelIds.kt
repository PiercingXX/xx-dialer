package com.piercingxx.xxphone.ring

/**
 * Versioned channel-id arithmetic (§4.3, todo rule #3). Channels are
 * append-only: a tone change mints `purpose_v(N+1)` and deletes `_vN` — the
 * same id is NEVER recreated, because delete-and-recreate resurrects the old
 * immutable settings. Pure JVM so the minting law is provable (todo #5).
 */
internal object ChannelIds {

    /** The four §10 purposes; also the registry table's PK values. */
    const val PURPOSE_RING_DEFAULT = "ring_default"
    const val PURPOSE_RING_UNKNOWN = "ring_unknown"
    const val PURPOSE_RING_SILENT = "ring_silent"
    const val PURPOSE_ONGOING = "ongoing"

    const val CUSTOM_PREFIX = "custom:"
    const val VERSION_SEPARATOR = "_v"
    const val FIRST_VERSION = 1

    /** `ring_unknown` + 2 → `ring_unknown_v2`. */
    fun versioned(purpose: String, version: Int): String =
        "$purpose$VERSION_SEPARATOR$version"

    /**
     * The only version source: current registry version + 1. A null current
     * (fresh purpose) yields v1 — exactly the ids named in the §10 table.
     * Monotone by construction, which is what makes id reuse impossible.
     */
    fun nextVersion(currentVersion: Int?): Int = (currentVersion ?: 0) + 1

    /**
     * The registry row alone can lie: a crash between
     * createNotificationChannel(_v(N+1)) and the registry write leaves that id
     * live-but-unregistered, and recomputing N+1 would no-op create() forever —
     * freezing tone changes on that channel (M4). This walks the version past
     * EVERY id the system still holds, starting from [nextVersion]. Pure seam:
     * [occupied] is injected so the walk is JVM-testable; bounded by
     * [MAX_VERSION_PROBES] so a pathological manager can never spin it.
     */
    fun nextFreeVersion(currentVersion: Int?, occupied: (Int) -> Boolean): Int {
        var candidate = nextVersion(currentVersion)
        var probes = 0
        while (occupied(candidate) && probes++ < MAX_VERSION_PROBES) candidate++
        return candidate
    }

    const val MAX_VERSION_PROBES = 10_000

    /** Ad-hoc per-contact channels ([ChannelRegistry.customChannelFor], D11). */
    fun customPurpose(lookupKey: String): String = "$CUSTOM_PREFIX$lookupKey"

    /** Inverse of [versioned]; tolerant of unknown shapes (returns null). */
    fun versionOf(channelId: String): Int? =
        channelId.substringAfterLast(VERSION_SEPARATOR, "").toIntOrNull()
}
