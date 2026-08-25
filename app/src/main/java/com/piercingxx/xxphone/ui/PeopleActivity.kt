package com.piercingxx.xxphone.ui

import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.ContactsContract
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.piercingxx.xxphone.ServiceLocator
import com.piercingxx.xxphone.data.ContactMirror
import com.piercingxx.xxphone.data.ContactMirrorEntity
import com.piercingxx.xxphone.data.TierMemberEntity
import com.piercingxx.xxphone.R
import com.piercingxx.xxphone.databinding.ActivityPeopleBinding
import com.piercingxx.xxphone.databinding.ItemPersonRowBinding
import com.piercingxx.xxphone.databinding.ViewPersonSheetBinding
import com.piercingxx.xxphone.ring.Intents
import com.piercingxx.xxphone.telecom.CallManager
import com.piercingxx.xxphone.util.E164
import kotlinx.coroutines.launch

/**
 * People tab (§12.3): the mirrored address book, sectioned ★ Starred /
 * Business / Everyone, with the contact sheet carrying tier controls. Under
 * Contact Scopes the list may be partial or empty — the empty-grant card says
 * why (§4.5) and everything stays alive; star writes report honestly instead
 * of pretending.
 */
class PeopleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPeopleBinding
    private val adapter = PersonAdapter()

    /** Cached for section headers ("Business — ring 09:00–19:00"). */
    private var businessWindowText: String = "09:00–19:00"

    /** Last mirror snapshot; the sheet derives a contact's full number list from it (§12.3). */
    private var lastRows: List<ContactMirrorEntity> = emptyList()

    /** Guards the sheet switches against listener re-entry from paint(). */
    private var suppressSheet = false

    /** Live search query; blank shows everyone. */
    private var query: String = ""

    /** Last biz-key snapshot so the query re-render skips the DB. */
    private var lastBizKeys: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeopleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TabBar.bind(this, Tab.PEOPLE)
        binding.emptyGrantWhy.text = EMPTY_GRANT_COPY
        binding.peopleList.layoutManager = LinearLayoutManager(this)
        binding.peopleList.adapter = adapter
        binding.peopleSearch.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            render(lastRows, lastBizKeys)
        }
        load()
    }

    override fun onStart() {
        super.onStart()
        TabBar.onTabScreenStart(this, Tab.PEOPLE)
    }

    override fun onResume() {
        super.onResume()
        load() // observer + sweep keep the mirror fresh; re-read on foreground
    }

    private fun load() {
        lifecycleScope.launch {
            val db = ServiceLocator.db(this@PeopleActivity)
            val rules = runCatching { ServiceLocator.rules(this@PeopleActivity).current() }.getOrNull()
            rules?.let { businessWindowText = WindowChips.timeRange(it.businessWindow) }
            val rows = runCatching { db.contactMirrorDao().all() }.getOrDefault(emptyList())
            val bizKeys = runCatching { db.tierMemberDao().bizKeys() }
                .getOrDefault(emptyList())
                .toSet()
            lastBizKeys = bizKeys
            render(rows, bizKeys)
        }
    }

    /**
     * Section assignment priority: ★ Starred > Business > Everyone — every
     * contact appears exactly once (the mockup's Roscoe-under-Everyone case).
     */
    private fun render(allRows: List<ContactMirrorEntity>, bizKeys: Set<String>) {
        lastRows = allRows
        // The empty-grant card explains an EMPTY MIRROR (§4.5), never an
        // unmatched search.
        binding.emptyGrantCard.isVisible = allRows.isEmpty()
        val needle = query.trim().lowercase()
        val rows = if (needle.isEmpty()) allRows else allRows.filter {
            it.displayName.lowercase().contains(needle) || it.e164.contains(needle)
        }
        val items = mutableListOf<PeopleItem>()
        if (rows.isNotEmpty()) {
            val sorted = rows.sortedBy { it.displayName.lowercase() }
            val starred = sorted.filter { it.starred }
            val biz = sorted.filter { !it.starred && it.lookupKey in bizKeys }
            val everyone = sorted.filter { !it.starred && it.lookupKey !in bizKeys }
            starred.applyTo(items) { "★ STARRED — ALWAYS RING" }
            biz.applyTo(items) { "BUSINESS — RING $businessWindowText" }
            everyone.applyTo(items) { "EVERYONE" }
            // bizTier lives outside `all()`; fill it for badges and the sheet
            sorted.forEach { it.bizTier = it.lookupKey in bizKeys }
        }
        adapter.submit(items)
    }

    private inline fun List<ContactMirrorEntity>.applyTo(
        items: MutableList<PeopleItem>,
        header: () -> String,
    ) {
        if (isEmpty()) return
        items += PeopleItem.Header(header())
        forEach { items += PeopleItem.Person(it) }
    }

    // ---- contact sheet -------------------------------------------------------

    private fun openSheet(person: ContactMirrorEntity) {
        val sheet = ViewPersonSheetBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)

        // ContactMirrorEntity fields are vals (bizTier aside) — the sheet tracks
        // its own current copy so star writes can replace it wholesale.
        var current = person

        sheet.sheetName.text = current.displayName.ifEmpty { "(unnamed)" }
        // §12.3: number(s) — every mirrored number behind this lookup key.
        sheet.sheetNumber.text = lastRows
            .filter { it.lookupKey == current.lookupKey }
            .map { it.e164 }
            .distinct()
            .ifEmpty { listOf(current.e164) }
            .joinToString("\n")
        sheet.sheetStarLabel.text = "★ Starred — rings any time"
        sheet.sheetBizLabel.text = "Business tier — rings $businessWindowText"
        sheet.sheetRingtoneNote.isVisible = current.customRingtone != null
        sheet.sheetRingtoneNote.text =
            "custom ringtone set — plays over any tier tone (D11)"

        fun paint() {
            sheet.sheetStarIcon.setImageResource(
                if (current.starred) R.drawable.ic_star_filled
                else R.drawable.ic_star_outline,
            )
            sheet.sheetStarIcon.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pxx_signal))
            // A refused write leaves state unchanged; without the guard the
            // isChecked rollback here re-fires the listener into a second
            // spurious write (the suppressPolicies pattern from Rules).
            suppressSheet = true
            sheet.sheetStarSwitch.isChecked = current.starred
            sheet.sheetBizSwitch.isChecked = current.bizTier
            suppressSheet = false
        }

        paint()

        sheet.sheetStarSwitch.setOnCheckedChangeListener { _, want ->
            if (suppressSheet) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                val (actual, verified) = writeStarred(current, want)
                if (actual != null) current = current.copy(starred = actual)
                paint()
                adapter.refresh(current)
                sheet.sheetError.isVisible = !verified
                if (!verified) {
                    sheet.sheetError.text =
                        "Contact Scopes blocked this write — starring must happen in Contacts."
                }
            }
        }

        sheet.sheetBizSwitch.setOnCheckedChangeListener { _, want ->
            if (suppressSheet) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                val db = ServiceLocator.db(this@PeopleActivity)
                // Room-owned tier (D4): always writable, unlike the provider.
                runCatching {
                    if (want) {
                        db.tierMemberDao()
                            .upsert(TierMemberEntity(current.lookupKey, "biz", System.currentTimeMillis()))
                    } else {
                        db.tierMemberDao().delete(current.lookupKey)
                    }
                }
                current.bizTier = want
                adapter.refresh(current)
            }
        }

        sheet.sheetCall.setOnClickListener {
            if (!CallManager.place(this, current.e164)) {
                toast("could not place the call")
            }
        }
        sheet.sheetMessage.setOnClickListener {
            // SMS handoff to the default messaging app — this app sends nothing (R8).
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${current.e164}")),
                )
            }.onFailure { toast("No messaging app available") }
        }
        sheet.sheetHistory.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(this, RecentsActivity::class.java)
                        .putExtra(Intents.EXTRA_FILTER_E164, current.e164),
                )
            }.onFailure { toast("recents unavailable") }
        }

        dialog.show()
    }

    /**
     * Provider star write (§12.3): wrapped hard because GrapheneOS Scopes
     * blocks ALL contact writes. The switch settles on whatever the provider
     * now reports — the UI never claims an outcome it did not verify.
     */
    private suspend fun writeStarred(person: ContactMirrorEntity, want: Boolean): Pair<Boolean?, Boolean> {
        val wrote = runCatching {
            contentResolver.update(
                ContactsContract.Contacts.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Contacts.STARRED, if (want) 1 else 0)
                },
                "${ContactsContract.Contacts.LOOKUP_KEY} = ?",
                arrayOf(person.lookupKey),
            ) > 0
        }.onFailure { android.util.Log.w(TAG, "star write failed", it) }
            .getOrDefault(false)

        val truth = runCatching {
            ServiceLocator.contactMirror(applicationContext)
                .liveLookup(E164.normalize(person.e164) ?: person.e164)
        }.onFailure { android.util.Log.w(TAG, "post-write verification failed", it) }
            .getOrNull()

        // Mirror follows verified truth so classification agrees with the UI.
        if (truth != null) {
            runCatching {
                ServiceLocator.db(this).contactMirrorDao()
                    .upsertAll(listOf(truth.copy(refreshedAt = System.currentTimeMillis())))
            }
        }
        return (truth?.starred ?: if (wrote) want else null) to (wrote && truth != null)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- list ------------------------------------------------------------------

    private sealed interface PeopleItem {
        data class Header(val label: String) : PeopleItem
        data class Person(val person: ContactMirrorEntity) : PeopleItem
    }

    private inner class PersonAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<PeopleItem> = emptyList()

        fun submit(next: List<PeopleItem>) {
            items = next
            notifyDataSetChanged()
        }

        fun refresh(updated: ContactMirrorEntity) {
            val idx = items.indexOfFirst {
                it is PeopleItem.Person && it.person.lookupKey == updated.lookupKey
            }
            if (idx >= 0) {
                items = items.toMutableList().also { it[idx] = PeopleItem.Person(updated) }
                notifyItemChanged(idx)
            }
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position] is PeopleItem.Header) TYPE_HEADER else TYPE_PERSON

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) {
                val padV = (10 * resources.displayMetrics.density).toInt()
                val padH = resources.getDimensionPixelSize(R.dimen.xx_gutter)
                val label = TextView(parent.context).apply {
                    setTextAppearance(R.style.TextAppearance_Xx_Chip)
                    setPadding(padH, padV, padH, padV)
                }
                HeaderHolder(label)
            } else {
                PersonHolder(
                    ItemPersonRowBinding.inflate(layoutInflater, parent, false),
                )
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is PeopleItem.Header -> (holder as HeaderHolder).label.text = item.label
                is PeopleItem.Person -> (holder as PersonHolder).bind(item.person)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class HeaderHolder(val label: TextView) : RecyclerView.ViewHolder(label)

    private inner class PersonHolder(private val row: ItemPersonRowBinding) :
        RecyclerView.ViewHolder(row.root) {

        fun bind(person: ContactMirrorEntity) {
            row.avatar.background = circleAvatar()
            row.avatar.text = Monograms.initials(person.displayName)
            row.name.text = person.displayName.ifEmpty { "(unnamed)" }
            row.number.text = person.e164
            row.starBadge.isVisible = person.starred
            row.bizBadge.isVisible = person.bizTier
            row.bizBadge.text = "BIZ"
            row.root.setOnClickListener { openSheet(person) }
        }

        /** Deterministic monogram disc (§12.3): slate fill, hairline ring. */
        private fun circleAvatar(): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@PeopleActivity, R.color.pxx_slate))
                setStroke(
                    1,
                    ContextCompat.getColor(this@PeopleActivity, R.color.pxx_white_25),
                )
            }
    }

    /** Deterministic initials (§4.5): no photos under Scopes, ever. */
    private companion object {
        const val TAG = "PeopleActivity"
        const val TYPE_HEADER = 0
        const val TYPE_PERSON = 1

        /** §4.5 verbatim duty: say WHY the tab is bare, keep working. */
        const val EMPTY_GRANT_COPY =
            "Contacts access is scoped or empty — showing no one. " +
                "Classification treats everyone as unknown."
    }
}
