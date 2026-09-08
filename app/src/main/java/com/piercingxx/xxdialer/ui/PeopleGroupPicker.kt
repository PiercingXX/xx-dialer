package com.piercingxx.xxdialer.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.ServiceLocator
import com.piercingxx.xxdialer.data.BlockedGroupSync
import com.piercingxx.xxdialer.data.StealthBlock
import com.piercingxx.xxdialer.data.TierExport
import com.piercingxx.xxdialer.data.TierMemberEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Same chooser Contacts uses: existing groups + Create new. */
object PeopleGroupPicker {

    fun show(
        context: Context,
        scope: CoroutineScope,
        lookupKey: String,
        onChanged: () -> Unit,
    ) {
        if (lookupKey.isEmpty()) return
        scope.launch {
            val dao = ServiceLocator.db(context).tierMemberDao()
            val names = (listOf(StealthBlock.GROUP) +
                runCatching { dao.customGroupNames() }.getOrDefault(emptyList()))
                .distinctBy { it.lowercase() }
            val mine = runCatching { dao.customGroups() }
                .getOrDefault(emptyList())
                .filter { it.lookupKey == lookupKey }
                .map { it.tier }
                .toSet()
            val labels = names.map { GroupGlyphs.prefix(it) } + "Create new"
            val adapter = nerdLabels(context, labels)
            AlertDialog.Builder(context)
                .setTitle("Add to group")
                .setAdapter(adapter) { _, which ->
                    if (which == names.size) {
                        promptCreate(context, scope, lookupKey, onChanged)
                    } else {
                        scope.launch {
                            val name = names[which]
                            val stored = mine.firstOrNull { it.equals(name, ignoreCase = true) }
                            runCatching {
                                if (stored != null) {
                                    dao.delete(lookupKey, stored)
                                    if (StealthBlock.isGroup(name)) {
                                        BlockedGroupSync.sync(context, lookupKey, blocked = false)
                                    }
                                } else {
                                    dao.upsert(TierMemberEntity(lookupKey, name, System.currentTimeMillis()))
                                    if (StealthBlock.isGroup(name)) {
                                        BlockedGroupSync.sync(context, lookupKey, blocked = true)
                                    }
                                }
                            }
                            onChanged()
                        }
                    }
                }
                .show()
        }
    }

    private fun nerdLabels(context: Context, labels: List<String>): ArrayAdapter<String> {
        val face = ResourcesCompat.getFont(context, R.font.font_body)
        val color = ContextCompat.getColor(context, R.color.pxx_signal)
        return object : ArrayAdapter<String>(
            context,
            android.R.layout.simple_list_item_1,
            labels,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                if (face != null) view.typeface = face
                view.setTextColor(color)
                return view
            }
        }
    }

    private fun promptCreate(
        context: Context,
        scope: CoroutineScope,
        lookupKey: String,
        onChanged: () -> Unit,
    ) {
        val input = EditText(context).apply {
            hint = "Group name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle("New group")
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = TierExport.groupTier(input.text?.toString().orEmpty())
                if (name == null) {
                    Toast.makeText(context, "Could not create that group", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                scope.launch {
                    runCatching {
                        ServiceLocator.db(context).tierMemberDao()
                            .upsert(TierMemberEntity(lookupKey, name, System.currentTimeMillis()))
                        if (StealthBlock.isGroup(name)) {
                            BlockedGroupSync.sync(context, lookupKey, blocked = true)
                        }
                    }
                    onChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
