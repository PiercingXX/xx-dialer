package com.piercingxx.xxdialer.ring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log
import com.piercingxx.xxdialer.data.ChannelRegistryDao
import com.piercingxx.xxdialer.data.ChannelRegistryEntity

/**
 * Owns the §10 channel table. The whole ringer (§4.3) rides on notification
 * channels, so this class enforces the two laws that make that safe:
 *
 *  - **Append-only** (todo rule #3): sound is immutable after creation and
 *    delete-and-recreate with the same id resurrects the old settings — every
 *    tone change mints a NEW versioned id via [ChannelIds.nextVersion] and
 *    deletes only the superseded one.
 *  - **Never fight the user** (§15): an existing, live channel is left exactly
 *    as the user may have re-edited it; reconcile reports, it does not rewrite.
 *
 * Registry rows (`channel_registry`, §11) are written on every mint so the
 * purpose→id mapping survives our own version churn.
 */
class ChannelRegistry(private val context: Context, private val dao: ChannelRegistryDao) {

    init {
        // SilencedNotifier is a contextless object by dictated contract; the
        // registry is constructed before any ring-path work (ServiceLocator),
        // so it seeds the holder. FLAGGED deviation from the dictated shape.
        AppContextHolder.set(context)
    }

    private val manager: NotificationManager? =
        runCatching { context.getSystemService(NotificationManager::class.java) }.getOrNull()

    private val unknownToneUri: Uri =
        Uri.parse("android.resource://${context.packageName}/raw/xx_unknown") // §10

    /**
     * Idempotent first-run creation of the four §10 channels, each at its
     * spec's [Spec.firstVersion] floor — so a fresh install of
     * `com.piercingxx.xxdialer` gets `ring_default_v2`, `ring_unknown_v1`,
     * `ring_silent_v1`, `ongoing_v1` and nothing else.
     *
     * This also runs the below-floor supersede path (see [ensure]), which is
     * the general mechanism for a shipped tone changing in an app update. It
     * has nothing to supersede today: the version floors were inherited from
     * the pre-rename package, and this application id has never created a
     * below-floor channel. Kept because the *next* tone change will need it,
     * not because a migration is pending.
     */
    suspend fun ensureAll() = SPECS(context.packageName).forEach { ensure(it) }

    /**
     * Live channel id for [purpose], minting on demand when the registry has
     * no row or the registered channel was deleted out from under us. Never
     * throws: ringing must not die in channel bookkeeping (todo rule #2).
     */
    suspend fun channelIdFor(purpose: String): String =
        runCatching { ensureLive(specFor(purpose)) }
            .onFailure { Log.w(TAG, "channelIdFor($purpose) failed", it) }
            // Fallback is the spec's FLOOR id, not blanket v1 — handing out a
            // below-floor id would name a channel the migration deletes.
            .getOrDefault(ChannelIds.versioned(purpose, specFor(purpose).firstVersion))

    /**
     * Per-call health check vs NotificationManager: muted/deleted channels
     * become warning strings for Setup to surface (§15). A deleted ring
     * channel is recovered under a fresh id — silence-by-deletion would break
     * the failure-direction law — but a MUTED channel is respected, not fought.
     */
    suspend fun reconcileAtCall(): List<String> {
        val warnings = mutableListOf<String>()
        SPECS(context.packageName).forEach { spec ->
            runCatching { reconcileOne(spec) }
                .onSuccess { warnings.addAll(it) }
                .onFailure { Log.w(TAG, "reconcile ${spec.purpose} failed", it) }
        }
        return warnings
    }

    /**
     * User swapped the unknown-caller tone: mint `ring_unknown_vN+1` (NEVER
     * reuse an id, §4.3); [mint] deletes the superseded channel and the
     * registry keeps the mapping (§11).
     */
    suspend fun mintUnknownTone(uri: Uri): String =
        runCatching { mint(unknownSpec(context.packageName).withTone(uri)) }
            .getOrElse { channelIdFor(ChannelIds.PURPOSE_RING_UNKNOWN) }

    /**
     * Ad-hoc high-importance channel carrying a contact's custom ringtone
     * (D11). Versioned ids like everything else; re-requesting the SAME tone
     * returns the live channel instead of churning versions.
     */
    suspend fun customChannelFor(lookupKey: String, toneUri: Uri): String {
        val purpose = ChannelIds.customPurpose(lookupKey)
        val row = currentRowOrNull(purpose)
        val existing = row?.channelId?.let { manager?.getNotificationChannel(it) }
        if (existing != null && existing.sound == toneUri) return row.channelId
        return runCatching {
            mint(
                Spec(
                    purpose = purpose,
                    toneUri = toneUri,
                    importance = NotificationManager.IMPORTANCE_HIGH,
                    vibration = true,
                    requiresSound = true,
                    title = "Custom ringtone",
                ),
            )
        }.getOrElse { channelIdFor(purpose) }
    }

    // ---- internals -----------------------------------------------------------

    private suspend fun ensure(spec: Spec) {
        val row = currentRowOrNull(spec.purpose)
        // Shipped-tone supersede (§10): channel sound is immutable after
        // creation, so a spec whose SHIPPED tone changes in an app update
        // raises its [Spec.firstVersion] floor instead of editing anything.
        // A registered channel below the floor still plays the superseded
        // sound — mint a fresh id and GC the old one, exactly the §4.3
        // tone-swap path. At or above the floor the channel is the user's to
        // keep (§15): never re-minted, never rewritten.
        // Dormant on this application id — the floors were inherited from the
        // pre-rename package and no below-floor channel was ever created here
        // — but this is the path a future tone change rides, so it stays.
        if (row != null && row.version < spec.firstVersion) {
            mint(spec)
            return
        }
        val id = row?.channelId ?: ChannelIds.versioned(spec.purpose, spec.firstVersion)
        if (manager?.getNotificationChannel(id) == null) create(id, spec)
        if (row == null || row.channelId != id) adopt(spec, id)
        // A DB wipe on an upgraded install can leave a below-floor channel
        // live with no registry row naming it (mint's GC only sees the
        // registered id). Sweeping ids below the floor is always safe: §4.3
        // forbids ever REUSING a superseded id, and deleting a channel that
        // does not exist is a system no-op — which is all this loop does on
        // the current application id, where no below-floor id was ever
        // created. Cheap, and it stays correct the next time a floor rises.
        for (v in ChannelIds.FIRST_VERSION until spec.firstVersion) {
            runCatching { manager?.deleteNotificationChannel(ChannelIds.versioned(spec.purpose, v)) }
        }
    }

    private suspend fun ensureLive(spec: Spec): String {
        val row = currentRowOrNull(spec.purpose)
        val id = row?.channelId
        // Below-floor ids are superseded (shipped-tone supersede, see [ensure])
        // and never handed out — a ring-time lookup that races first-run
        // bookkeeping still mints forward and rings on the current shipped tone.
        return if (row != null && id != null && row.version >= spec.firstVersion &&
            manager?.getNotificationChannel(id) != null
        ) {
            id
        } else {
            mint(spec) // unregistered, below-floor, or system-deleted → new versioned id, never resurrected
        }
    }

    private suspend fun mint(spec: Spec): String {
        val row = currentRowOrNull(spec.purpose)
        val previous = row?.channelId
        // M4: the DB row alone can lie — a crash between createNotificationChannel
        // and writeRow leaves _v(N+1) live-but-unregistered, and recomputing N+1
        // would no-op create() forever, freezing tone changes on that channel.
        // Bump past every id the system still holds (pure seam:
        // ChannelIds.nextFreeVersion), then GC the superseded channel exactly
        // like mintUnknownTone did (§4.3 append-only law).
        // Seed the walk at the spec's floor: nextVersion(firstVersion - 1) ==
        // firstVersion, so even an EMPTY registry can never mint a below-floor
        // id. This clause is NOT vestigial after the package rename — it is
        // the only reason a fresh install of com.piercingxx.xxdialer lands on
        // ring_default_v2 instead of v1, since its registry starts empty. For
        // floor-1 purposes it is exactly nextFreeVersion(row?.version).
        val seed = maxOf(row?.version ?: 0, spec.firstVersion - 1)
        val version = ChannelIds.nextFreeVersion(seed) { v ->
            manager?.getNotificationChannel(ChannelIds.versioned(spec.purpose, v)) != null
        }
        val id = ChannelIds.versioned(spec.purpose, version)
        create(id, spec)
        writeRow(spec.purpose, id, spec.toneUri, version)
        if (previous != null && previous != id) {
            runCatching { manager?.deleteNotificationChannel(previous) }
        }
        return id
    }

    private suspend fun reconcileOne(spec: Spec): List<String> {
        val row = currentRowOrNull(spec.purpose)
        val id = row?.channelId ?: ChannelIds.versioned(spec.purpose, spec.firstVersion)
        val channel = manager?.getNotificationChannel(id)
        return when {
            channel == null -> {
                if (row == null) ensure(spec) else mint(spec) // recovery under a NEW id (§4.3)
                listOf("'$id' was deleted in system settings — recreated as a fresh channel")
            }
            spec.requiresSound && channel.sound == null ->
                listOf("'$id' lost its sound — muted at the system level, respected (§15)")
            channel.importance == NotificationManager.IMPORTANCE_NONE ->
                listOf("'$id' is muted at the system level — respected, not fought (§15)")
            else -> emptyList()
        }
    }

    /**
     * CREATE-ONLY. An already-existing id is never rewritten here — that is
     * what keeps user edits (and platform immutability) intact (§15).
     */
    private fun create(id: String, spec: Spec) {
        val channel = NotificationChannel(id, spec.title, spec.importance)
        if (spec.toneUri != null) {
            channel.setSound(spec.toneUri, RING_ATTRIBUTES)
        } else {
            channel.setSound(null, null)
        }
        channel.enableVibration(spec.vibration)
        runCatching { manager?.createNotificationChannel(channel) } // tolerate already-exists (§10)
            .onFailure { Log.w(TAG, "create($id) failed", it) }
    }

    private suspend fun currentRowOrNull(purpose: String): ChannelRegistryEntity? =
        runCatching { dao.current(purpose) }
            .onFailure { Log.w(TAG, "registry read failed", it) }
            .getOrNull()

    /** Registry row for a live-but-unregistered channel (DB wipe recovery). */
    private suspend fun adopt(spec: Spec, id: String) =
        writeRow(spec.purpose, id, spec.toneUri, ChannelIds.versionOf(id) ?: ChannelIds.FIRST_VERSION)

    private suspend fun writeRow(purpose: String, id: String, tone: Uri?, version: Int) {
        runCatching {
            dao.upsert(
                ChannelRegistryEntity(
                    purpose = purpose,
                    channelId = id,
                    toneUri = tone?.toString(),
                    version = version,
                ),
            )
        }.onFailure { Log.w(TAG, "registry write failed", it) }
    }

    private fun specFor(purpose: String): Spec =
        SPECS(context.packageName).firstOrNull { it.purpose == purpose }
            ?: Spec(purpose, null, NotificationManager.IMPORTANCE_DEFAULT, vibration = false)

    private fun unknownSpec(packageName: String): Spec =
        SPECS(packageName).first { it.purpose == ChannelIds.PURPOSE_RING_UNKNOWN }

    /** One §10 row: purpose, sound, importance. */
    internal data class Spec(
        val purpose: String,
        val toneUri: Uri?,
        val importance: Int,
        val vibration: Boolean,
        val requiresSound: Boolean = false,
        val title: String = "XX-Dialer",
        /**
         * Lowest version this spec will register or hand out. Raised (never
         * lowered) when a SHIPPED tone changes in an app update — sound is
         * immutable after creation, so the change must ride a §4.3 version
         * bump; anything registered below the floor is superseded and
         * re-minted by [ensure]/[ensureLive]. A floor above 1 does NOT imply
         * a pending migration: it only asserts which ids this purpose has
         * retired for good.
         */
        val firstVersion: Int = ChannelIds.FIRST_VERSION,
    ) {
        fun withTone(uri: Uri): Spec = copy(toneUri = uri)
    }

    companion object {
        private const val TAG = "ChannelRegistry"

        private val RING_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE) // rings on the ringer stream (§4.3)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        private fun SPECS(packageName: String): List<Spec> = listOf(
            Spec(
                purpose = ChannelIds.PURPOSE_RING_DEFAULT,
                // Baked default tone (§10): shipped in res/raw, same shape as
                // the unknown-caller tone below. The URI is built from the
                // LIVE packageName, never a literal — that is why the
                // com.piercingxx.xxphone → com.piercingxx.xxdialer rename did
                // not silently point every ring at a package that no longer
                // exists.
                //
                // The v2 floor is historical: v1 pointed at the
                // system-ringtone indirection (content://settings/system/
                // ringtone) so the channel followed the user's system pick,
                // and swapping to a fixed app resource had to ride a version
                // bump because channel sound is immutable after creation. No
                // v1 exists under this application id — see
                // [ChannelIds.RING_DEFAULT_FIRST_VERSION] for why the floor
                // is kept at 2 regardless.
                toneUri = Uri.parse("android.resource://$packageName/raw/xx_ringtone"),
                importance = NotificationManager.IMPORTANCE_HIGH,
                vibration = true,
                requiresSound = true,
                title = "Ringing · saved & starred",
                firstVersion = ChannelIds.RING_DEFAULT_FIRST_VERSION,
            ),
            Spec(
                purpose = ChannelIds.PURPOSE_RING_UNKNOWN,
                toneUri = Uri.parse("android.resource://$packageName/raw/xx_unknown"),
                importance = NotificationManager.IMPORTANCE_HIGH,
                vibration = true,
                requiresSound = true,
                title = "Ringing · unknown callers",
            ),
            Spec(
                purpose = ChannelIds.PURPOSE_RING_SILENT,
                toneUri = null,
                importance = NotificationManager.IMPORTANCE_HIGH, // heads-up WITHOUT sound (§10)
                vibration = false,
                requiresSound = false,
                title = "Silenced calls",
            ),
            Spec(
                purpose = ChannelIds.PURPOSE_ONGOING,
                toneUri = null,
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                vibration = false,
                requiresSound = false,
                title = "Ongoing call",
            ),
        )
    }
}
