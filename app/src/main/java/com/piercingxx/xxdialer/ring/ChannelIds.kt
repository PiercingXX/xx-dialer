package com.piercingxx.xxdialer.ring

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

    /**
     * §10 floor for `ring_default`: the lowest version this purpose will ever
     * register or hand out. `ChannelRegistry` treats any registered version
     * below the floor as superseded — re-minting at or above it — and seeds
     * its mint walk at `floor - 1` so even an empty registry lands here.
     *
     * Why 2 and not 1. The floor was raised when the shipped default tone
     * changed: `ring_default_v1` carried the system-ringtone indirection
     * (`content://settings/system/ringtone`) and v2 carries the baked
     * `res/raw/xx_ringtone`. Channel sound is immutable after creation, so
     * that swap could only ride a §4.3 version bump — mint the successor,
     * delete the predecessor, NEVER edit or reuse an id.
     *
     * That history predates the `com.piercingxx.xxdialer` application id, and
     * a new application id is a new package with an empty NotificationManager:
     * **no `ring_default_v1` can exist on any install of this package, so the
     * supersede path below the floor is unreachable in practice.** The floor
     * is kept at 2 anyway, deliberately:
     *
     *  - Version numbers are append-only counters, not app versions. Starting
     *    a clean package at v2 costs nothing — ids are never user-visible
     *    (users see channel *titles*) and monotonicity is all that matters.
     *  - Lowering it to 1 would not simplify anything; it would only make
     *    `Spec.firstVersion` uniformly 1, turning the floor machinery
     *    ([ChannelRegistry.ensure]'s supersede branch, [ChannelRegistry]'s
     *    below-floor sweep, the `firstVersion - 1` mint seed) into dead code
     *    that goes untested until the next shipped tone change needs it.
     *  - Reusing v1 for a *different* sound would contradict the append-only
     *    law this object exists to enforce, even though nothing on disk would
     *    currently catch us at it.
     */
    const val RING_DEFAULT_FIRST_VERSION = 2

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
