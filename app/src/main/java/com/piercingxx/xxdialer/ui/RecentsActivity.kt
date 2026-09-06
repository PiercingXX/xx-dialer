package com.piercingxx.xxdialer.ui

import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.ServiceLocator
import com.piercingxx.xxdialer.core.Rules
import com.piercingxx.xxdialer.data.ContactMirrorEntity
import com.piercingxx.xxdialer.data.ScreenLogEntity
import com.piercingxx.xxdialer.data.SettingsRepository
import com.piercingxx.xxdialer.data.TierMemberEntity
import com.piercingxx.xxdialer.telecom.CallManager
import com.piercingxx.xxdialer.databinding.ActivityRecentsBinding
import com.piercingxx.xxdialer.databinding.ItemRecentHeaderBinding
import com.piercingxx.xxdialer.databinding.ItemRecentRowBinding
import com.piercingxx.xxdialer.ring.Intents
import com.piercingxx.xxdialer.ring.MissedCallsClear
import com.piercingxx.xxdialer.ui.RecentsMerge.Filter
import com.piercingxx.xxdialer.ui.RecentsMerge.Grouped
import com.piercingxx.xxdialer.ui.VerdictLines.Glyph
import com.piercingxx.xxdialer.util.E164
import com.piercingxx.xxdialer.util.StarContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Home tab (design §12.1): collapsible starred strip, filter chips, and the
 * merged call list — platform CallLog LEFT-JOINed with screen_log so every
 * row carries its disposition glyph and the rule that fired (R7). Row actions:
 * call back / add to contacts / block; on silenced rows also Ring next time
 * (star) and Add to Business. Views + viewBinding, no Compose (D7).
 */
class RecentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecentsBinding
    private lateinit var adapter: RecentAdapter

    private var filter = Filter.ALL
    private var deepLinkE164: String? = null
    private val expandedGroups = mutableSetOf<String>()
    private var groupingOn = true
    private var merged: List<RecentsMerge.MergedCall> = emptyList()
    private var lines: Map<Long, VerdictLines.Line> = emptyMap()
    private var loadJob: Job? = null

    private var starredCollapsed = false
    private var starredNames: List<Starred> = emptyList()

    private val callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TabBar.bind(this, Tab.RECENTS)

        adapter = RecentAdapter(
            onItemClick = ::callBack,
            onItemLongClick = ::showRowMenu,
            onHeaderClick = ::toggleGroup,
        )
        binding.recentsList.layoutManager = LinearLayoutManager(this)
        binding.recentsList.adapter = adapter
        binding.recentsList.addItemDecoration(HairlineDivider(this))

        binding.recentsChips.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = RecentsFilterIntent.filterForChipId(checkedIds.firstOrNull())
            restyleRecentsChips()
            if (filter == Filter.MISSED) MissedCallsClear.clear(this)
            rerender()
        }
        restyleRecentsChips()

        binding.recentsStarToggle.setOnClickListener {
            starredCollapsed = !starredCollapsed
            renderStarStrip(starredNames)
        }
        binding.recentsFilterNote.setOnClickListener {
            deepLinkE164 = null
            rerender()
        }

        applyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (MissedCallsClear.shouldClear(recentsVisible = true, missedFilterActive = filter == Filter.MISSED)) {
            MissedCallsClear.clear(this)
        }
        reload()
    }

    override fun onStart() {
        super.onStart()
        TabBar.onTabScreenStart(this, Tab.RECENTS)
        runCatching {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { contentResolver.unregisterContentObserver(callLogObserver) }
    }

    /** Silenced-notification deep link (§12): pre-filter to that number's rows. */
    private fun readDeepLink(extra: String?) {
        deepLinkE164 = extra?.takeIf { it.isNotBlank() }
        if (deepLinkE164 != null) rerender()
    }

    /**
     * Missed-call notification extra: open Recents already on the Missed chip.
     * ChipGroup.check fires the checked listener (chips are checkable), which
     * also clears the lifetime missed badge.
     */
    private fun applyIntent(intent: Intent?) {
        readDeepLink(intent?.getStringExtra(Intents.EXTRA_FILTER_E164))
        RecentsFilterIntent.filterOf(intent?.getStringExtra(Intents.EXTRA_RECENTS_FILTER))?.let { next ->
            filter = next
            binding.recentsChips.check(RecentsFilterIntent.chipIdFor(next))
            restyleRecentsChips()
            rerender()
        }
    }

    /** Selected chip = Widget.Xx.Chip.Selected invert; others stay hairline. */
    private fun restyleRecentsChips() {
        listOf(
            binding.recentsChipAll,
            binding.recentsChipMissed,
            binding.recentsChipSilenced,
            binding.recentsChipBlocked,
        ).forEach { chip -> styleChip(chip, chip.isChecked) }
    }

    private fun styleChip(chip: Chip, selected: Boolean) {
        chip.chipBackgroundColor = ColorStateList.valueOf(
            getColor(if (selected) R.color.pxx_emphasis_bg else android.R.color.transparent),
        )
        chip.setTextColor(
            if (selected) getColor(R.color.pxx_emphasis_fg)
            else ContextCompat.getColor(this, R.color.pxx_white_50),
        )
        chip.chipStrokeColor = ColorStateList.valueOf(
            getColor(if (selected) R.color.pxx_emphasis_bg else R.color.pxx_white_25),
        )
    }

    // ---- data loading ---------------------------------------------------------

    private fun reload() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            val loaded = load()
            withContext(Dispatchers.Main) {
                merged = loaded.merged
                lines = loaded.lines
                groupingOn = loaded.groupingOn
                renderStarStrip(loaded.starredNames)
                rerender()
            }
        }
    }

    private suspend fun load(): Loaded {
        val rules = runCatching { ServiceLocator.rules(this).current() }.getOrDefault(Rules())
        val logs = runCatching { ServiceLocator.db(this).screenLogDao().lastN(LOG_LIMIT) }
            .getOrDefault(emptyList())
            .map(::toLogRow)
        val mirror = runCatching { ServiceLocator.db(this).contactMirrorDao().all() }
            .getOrDefault(emptyList())
        val groupingRaw = runCatching {
            ServiceLocator.settings(this).getString(SettingsRepository.KEY_GROUP_RECENTS)
        }.getOrNull()

        val platformCalls = queryPlatformCalls()
        val outgoingTimes = platformCalls
            .filter { it.outgoing && it.e164 != null }
            .groupBy({ it.e164!! }, { it.timeMillis })

        val namesByE164 = HashMap<String, String>()
        for (row in mirror) namesByE164.putIfAbsent(row.e164, row.displayName)

        val mergedCalls = RecentsMerge.merge(platformCalls, logs).map { call ->
            call.copy(displayName = call.e164?.let(namesByE164::get))
        }
        val linesById = mergedCalls.associate { call ->
            call.key to VerdictLines.annotate(
                e164 = call.e164,
                verdict = screenVerdictOf(call),
                reasonRaw = call.reasonRaw,
                observed = call.observed,
                rules = rules,
                recentOutgoingWeekday = weekdayHint(outgoingTimes, call),
                blockedUpstream = call.blockedUpstream,
            )
        }
        val starred = mirror.asSequence()
            .filter { it.starred }
            .distinctBy { it.lookupKey }
            .map { Starred(it.displayName, it.e164) }
            .toList()
        return Loaded(mergedCalls, linesById, starred, groupingRaw != "0")
    }

    /** Glyph already encodes the disposition; recover the verdict vocabulary. */
    private fun screenVerdictOf(call: RecentsMerge.MergedCall): String? = when (call.glyph) {
        Glyph.BLOCKED -> "Block"
        Glyph.SILENCED -> "Silence"
        Glyph.RING -> "Ring"
        Glyph.NONE -> null
    }

    /**
     * D14's printed skeleton ("you called them Tue") gets its weekday from the
     * most recent outgoing to this number within 48 h before the call; absent
     * one, VerdictLines keeps the static label.
     */
    private fun weekdayHint(
        outgoingTimes: Map<String, List<Long>>,
        call: RecentsMerge.MergedCall,
    ): String? {
        val times = outgoingTimes[call.e164] ?: return null
        val latestBefore = times.filter {
            it < call.timeMillis && call.timeMillis - it <= RECENT_OUTGOING_MS
        }.maxOrNull() ?: return null
        return Instant.ofEpochMilli(latestBefore).atZone(ZoneId.systemDefault()).dayOfWeek
            .getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

    private fun queryPlatformCalls(): List<RecentsMerge.PlatformCall> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
        )
        return runCatching {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberCol = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameCol = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val dateCol = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val typeCol = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val durationCol = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                buildList {
                    while (c.moveToNext() && size < CALL_LIMIT) {
                        val raw = c.getString(numberCol)
                        val type = c.getInt(typeCol)
                        add(
                            RecentsMerge.PlatformCall(
                                id = c.getLong(idCol),
                                timeMillis = c.getLong(dateCol),
                                rawNumber = raw,
                                e164 = E164.normalize(raw),
                                cachedName = c.getString(nameCol)?.takeIf { it.isNotBlank() },
                                missed = type == CallLog.Calls.MISSED_TYPE ||
                                    type == CallLog.Calls.REJECTED_TYPE,
                                outgoing = type == CallLog.Calls.OUTGOING_TYPE,
                                blockedUpstream = type == CallLog.Calls.BLOCKED_TYPE,
                                durationSec = c.getInt(durationCol),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.onFailure { Log.w(TAG, "call log read failed", it) }
            .getOrDefault(emptyList()) // §15: degrade to a log-only view, never crash
    }

    private fun toLogRow(e: ScreenLogEntity) = RecentsMerge.LogRow(
        id = e.id,
        at = e.at,
        e164 = e.e164,
        verdict = e.verdict,
        reason = e.reason,
        mode = e.mode,
    )

    // ---- rendering ------------------------------------------------------------

    private class Loaded(
        val merged: List<RecentsMerge.MergedCall>,
        val lines: Map<Long, VerdictLines.Line>,
        val starredNames: List<Starred>,
        val groupingOn: Boolean,
    )

    private fun renderStarStrip(names: List<Starred>) {
        starredNames = names
        val strip = binding.recentsStarStrip
        strip.removeAllViews()
        if (names.isEmpty()) {
            binding.recentsStarBar.visibility = View.GONE
            return
        }
        binding.recentsStarBar.visibility = View.VISIBLE
        binding.recentsStarToggle.text = if (starredCollapsed) "★ show" else "★ collapse"
        strip.visibility = if (starredCollapsed) View.GONE else View.VISIBLE
        if (!starredCollapsed) names.forEach { strip.addView(monogramView(it)) }
    }

    /** One starred-strip avatar: display name + number behind it. */
    private data class Starred(val name: String, val e164: String)

    /** Deterministic monogram avatar (§12.1): initials on a pxx_slate circle. */
    private fun monogramView(person: Starred): TextView {
        val name = person.name
        val density = resources.displayMetrics.density
        val size = (AVATAR_DP * density).toInt()
        return TextView(this).apply {
            text = Monograms.initials(name)
            setTextColor(ContextCompat.getColor(context, R.color.pxx_white_80))
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = ResourcesCompat.getFont(context, R.font.spacemono_regular)
            background = ColorDrawable(ContextCompat.getColor(context, R.color.pxx_slate))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
                }
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (10 * density).toInt()
            }
            contentDescription = name
            // §12.1: the strip is an entry point, not decoration — tap
            // filters Recents to this person (same deep-link path the
            // silenced card uses); safe, never an accidental call.
            setOnClickListener {
                deepLinkE164 = person.e164
                rerender()
            }
        }
    }

    private fun rerender() {
        val visible = merged.asSequence()
            .filter { RecentsMerge.passes(filter, it) }
            .filter { deepLinkE164 == null || it.identity == deepLinkE164 }
            .toList()
        adapter.submit(flatten(RecentsMerge.group(visible, groupingOn, expandedGroups)))

        binding.recentsFilterNote.apply {
            visibility = if (deepLinkE164 != null) View.VISIBLE else View.GONE
            text = deepLinkE164?.let { "Filtered to $it · tap to clear" }
        }
        binding.recentsEmpty.apply {
            visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            text = if (merged.isEmpty()) "No calls yet" else "Nothing matches this filter"
        }
    }

    private fun flatten(groupedRows: List<Grouped>): List<RecentAdapter.Row> = buildList {
        for (g in groupedRows) when (g) {
            is Grouped.Single -> add(RecentAdapter.Row.Item(g.call, lineFor(g.call)))
            is Grouped.Group -> {
                add(
                    RecentAdapter.Row.Header(
                        g.identity,
                        titleFor(g.calls.first()),
                        g.calls.size,
                        g.expanded,
                    ),
                )
                if (g.expanded) g.calls.forEach { add(RecentAdapter.Row.Item(it, lineFor(it))) }
            }
        }
    }

    private fun lineFor(call: RecentsMerge.MergedCall): VerdictLines.Line =
        lines[call.key] ?: VerdictLines.Line(Glyph.NONE, "")

    private fun titleFor(call: RecentsMerge.MergedCall): String =
        displayNameOf(call) ?: "Hidden number"

    // ---- interactions ----------------------------------------------------------

    private fun toggleGroup(identity: String) {
        if (!expandedGroups.remove(identity)) expandedGroups.add(identity)
        rerender()
    }

    private fun callBack(call: RecentsMerge.MergedCall) {
        val number = dialable(call) ?: run {
            toast("No number to call back")
            return
        }
        place(number)
    }

    private fun place(number: String) {
        if (!CallManager.place(this, number)) toast("Couldn't place call")
    }

    private fun dialable(call: RecentsMerge.MergedCall): String? =
        call.e164 ?: call.rawNumber?.takeIf { it.isNotBlank() }

    private fun showRowMenu(anchor: View, call: RecentsMerge.MergedCall) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        var order = 0
        fun item(id: Int, title: CharSequence) = menu.add(0, id, order++, title)

        val number = dialable(call)
        if (number != null) {
            item(ID_CALL_BACK, getString(R.string.call_back))
            item(ID_MESSAGE, SEND_MESSAGE)
            item(ID_ADD_CONTACTS, ADD_TO_CONTACTS)
            item(ID_COPY, COPY_NUMBER)
            item(ID_BLOCK, getString(R.string.block))
        }
        if (call.glyph == Glyph.SILENCED && call.e164 != null) {
            item(ID_RING_NEXT_TIME, RING_NEXT_TIME)
            item(ID_ADD_BUSINESS, ADD_TO_BUSINESS)
        }
        item(ID_DELETE, DELETE_CALL)

        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                ID_CALL_BACK -> number?.let(::place)
                ID_MESSAGE -> number?.let(::sendMessage)
                ID_ADD_CONTACTS -> number?.let(::addToContacts)
                ID_COPY -> number?.let(::copyNumber)
                ID_BLOCK -> number?.let(::blockNumber)
                ID_RING_NEXT_TIME -> ringNextTime(call.e164!!)
                ID_ADD_BUSINESS -> addToBusiness(call.e164!!)
                ID_DELETE -> deleteCall(call)
            }
            true
        }
        popup.show()
    }

    /** SMS handoff to the default messaging app — this app sends nothing itself (R8). */
    private fun sendMessage(number: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")))
        }.onFailure { toast("No messaging app available") }
    }

    private fun copyNumber(number: String) {
        runCatching {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("number", number))
        }
        toast("Copied $number")
    }

    /**
     * Deletes THIS CallLog row (key = platform _ID; WRITE_CALL_LOG rides the
     * dialer role). The screen_log row stays — R7's reason record is a
     * policy audit, cleared only from Rules, not a call-history mirror.
     */
    private fun deleteCall(call: RecentsMerge.MergedCall) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = try {
                contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls._ID} = ?",
                    arrayOf(call.key.toString()),
                ) > 0
            } catch (t: Throwable) {
                Log.w(TAG, "call-log delete refused", t)
                false
            }
            withContext(Dispatchers.Main) {
                toast(if (ok) "Deleted from history" else "Couldn't delete — call log refused")
                if (ok) reload()
            }
        }
    }

    private fun addToContacts(number: String) {
        val intent = Intent(
            ContactsContract.Intents.Insert.ACTION,
            ContactsContract.Contacts.CONTENT_URI,
        ).putExtra(ContactsContract.Intents.Insert.PHONE, number)
        runCatching { startActivity(intent) }
            .onFailure { toast("No contacts app available") }
    }

    /** D6: the system blocklist is the block store; best-effort under role loss. */
    private fun blockNumber(number: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                BlockedNumberContract.canCurrentUserBlockNumbers(this@RecentsActivity) &&
                    contentResolver.insert(
                        BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                        ContentValues().apply {
                            put(
                                BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
                                number,
                            )
                        },
                    ) != null
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                toast(if (ok) "Blocked $number" else "Couldn't block $number")
            }
            if (ok) withContext(Dispatchers.Main) { reload() }
        }
    }

    /** Stars via ContactsContract STARRED=1 — best-effort under Scopes (D4). */
    private fun ringNextTime(e164: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = StarContact.ringNextTime(applicationContext, e164)
            withContext(Dispatchers.Main) {
                toast(if (ok) "Will ring next time ★" else "Couldn't star — contact write refused")
                if (ok) reload()
            }
        }
    }

    private fun addToBusiness(e164: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val mirror = mirrorFor(e164)
            val ok = mirror != null && runCatching {
                ServiceLocator.db(this@RecentsActivity).tierMemberDao().upsert(
                    TierMemberEntity(mirror.lookupKey, "biz", System.currentTimeMillis()),
                )
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                toast(
                    if (ok) "Added to Business" else "Unknown caller — no contact record",
                )
            }
        }
    }

    private suspend fun mirrorFor(e164: String): ContactMirrorEntity? =
        runCatching { ServiceLocator.db(this).contactMirrorDao().findByE164(e164) }.getOrNull()

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---- adapter ----------------------------------------------------------------

    private class HairlineDivider(activity: RecentsActivity) : RecyclerView.ItemDecoration() {
        private val drawable =
            ContextCompat.getDrawable(activity, R.drawable.divider_hairline)!!

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val last = parent.adapter?.itemCount?.minus(1) ?: return
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION || pos >= last) continue
                drawable.setBounds(child.left, child.bottom, child.right, child.bottom + 1)
                drawable.draw(c)
            }
        }
    }

    private class RecentAdapter(
        private val onItemClick: (RecentsMerge.MergedCall) -> Unit,
        private val onItemLongClick: (View, RecentsMerge.MergedCall) -> Unit,
        private val onHeaderClick: (String) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        sealed interface Row {
            data class Item(
                val call: RecentsMerge.MergedCall,
                val line: VerdictLines.Line,
            ) : Row

            data class Header(
                val identity: String,
                val title: String,
                val count: Int,
                val expanded: Boolean,
            ) : Row
        }

        private val rows = ArrayList<Row>()

        fun submit(next: List<Row>) {
            rows.clear()
            rows.addAll(next)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Item -> TYPE_ITEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderViewHolder(ItemRecentHeaderBinding.inflate(inflater, parent, false))
            } else {
                ItemViewHolder(ItemRecentRowBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderViewHolder).bind(row, onHeaderClick)
                is Row.Item -> (holder as ItemViewHolder).bind(row, onItemClick, onItemLongClick)
            }
        }

        private class ItemViewHolder(private val b: ItemRecentRowBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(
                row: Row.Item,
                onClick: (RecentsMerge.MergedCall) -> Unit,
                onLongClick: (View, RecentsMerge.MergedCall) -> Unit,
            ) {
                val context = b.root.context
                b.rowGlyph.setImageResource(glyphDrawable(row))
                b.rowTitle.text = displayNameOf(row.call) ?: "Hidden number"
                b.rowRule.text = row.line.text
                b.rowRule.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (row.line.glyph == Glyph.BLOCKED && row.call.reasonRaw.isNotEmpty()) {
                            R.color.pxx_error
                        } else {
                            R.color.pxx_white_80
                        },
                    ),
                )
                b.rowWhen.text = whenText(row.call.timeMillis)
                b.rowExtra.text = extraText(row.call)
                b.rowRoot.setOnClickListener { onClick(row.call) }
                b.rowRoot.setOnLongClickListener {
                    onLongClick(b.rowRoot, row.call)
                    true
                }
            }

            private fun glyphDrawable(row: Row.Item): Int = when (row.line.glyph) {
                Glyph.BLOCKED ->
                    if (row.call.reasonRaw.isEmpty()) R.drawable.ic_phone_missed
                    else R.drawable.ic_verdict_blocked
                Glyph.SILENCED -> R.drawable.ic_verdict_silenced
                Glyph.RING -> R.drawable.ic_verdict_ring
                Glyph.NONE ->
                    if (row.call.missed) R.drawable.ic_phone_missed else R.drawable.ic_phone_outgoing
            }

            private fun extraText(call: RecentsMerge.MergedCall): String =
                if (!call.missed && call.durationSec > 0) {
                    "%d:%02d".format(call.durationSec / 60, call.durationSec % 60)
                } else {
                    ""
                }
        }

        private class HeaderViewHolder(private val b: ItemRecentHeaderBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(row: Row.Header, onClick: (String) -> Unit) {
                b.headerArrow.text = if (row.expanded) "▾" else "▸"
                b.headerTitle.text = row.title
                b.headerCount.text = "×${row.count}"
                b.headerRoot.setOnClickListener { onClick(row.identity) }
            }
        }
    }

    private companion object {
        const val TAG = "RecentsActivity"

        const val CALL_LIMIT = 200
        const val LOG_LIMIT = 400
        const val AVATAR_DP = 34
        const val RECENT_OUTGOING_MS = 48L * 60 * 60 * 1000 // D14

        const val ADD_TO_CONTACTS = "Add to contacts"
        const val RING_NEXT_TIME = "Ring next time ★"
        const val ADD_TO_BUSINESS = "Add to Business"

        const val SEND_MESSAGE = "Send message"
        const val COPY_NUMBER = "Copy number"
        const val DELETE_CALL = "Delete from history"

        const val ID_CALL_BACK = 1
        const val ID_ADD_CONTACTS = 2
        const val ID_BLOCK = 3
        const val ID_RING_NEXT_TIME = 4
        const val ID_ADD_BUSINESS = 5
        const val ID_MESSAGE = 6
        const val ID_COPY = 7
        const val ID_DELETE = 8

        const val TYPE_ITEM = 0
        const val TYPE_HEADER = 1

        fun displayNameOf(call: RecentsMerge.MergedCall): String? =
            call.displayName ?: call.cachedName ?: call.rawNumber?.takeIf { it.isNotBlank() }

        fun whenText(timeMillis: Long): String {
            val zone = ZoneId.systemDefault()
            val dateTime = Instant.ofEpochMilli(timeMillis).atZone(zone)
            val date = dateTime.toLocalDate()
            val today = LocalDate.now(zone)
            return when {
                date == today -> dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
                date == today.minusDays(1) -> "yest"
                date.isAfter(today.minusDays(7)) ->
                    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                else -> dateTime.format(DateTimeFormatter.ofPattern("MMM d"))
            }
        }
    }
}
