package com.piercingxx.xxphone.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.piercingxx.xxphone.R
import com.piercingxx.xxphone.ServiceLocator
import com.piercingxx.xxphone.data.ContactMirrorEntity
import com.piercingxx.xxphone.databinding.ActivityKeypadBinding
import com.piercingxx.xxphone.telecom.CallManager
import com.piercingxx.xxphone.util.E164
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keypad tab (design §12.2): 3×4 grid plus trailing backspace column, Space
 * Mono tabular digits, T9 match-as-you-type against the contact mirror, and
 * the call block. Views + viewBinding, no Compose (D7).
 */
class KeypadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeypadBinding
    private val digits = StringBuilder()
    private var contacts: List<ContactMirrorEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeypadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TabBar.bind(this, Tab.KEYPAD)

        wireKeys()

        binding.keypadCall.setOnClickListener { placeCall() }
        binding.keyDelete.setOnClickListener {
            haptic(binding.keyDelete)
            backspace()
        }
        binding.keyDelete.setOnLongClickListener {
            haptic(binding.keyDelete)
            clearAll()
            true
        }

        intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)?.let { prefill ->
            digits.append(prefill)
            refresh()
        }

        // Long-press the entry pastes a number from the clipboard — the
        // standard dialer affordance for numbers copied out of other apps.
        binding.keypadEntry.setOnLongClickListener {
            val pasted = runCatching {
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            }.getOrNull()
            // Keep only dialable characters; garbage clipboards paste nothing.
            val cleaned = pasted.orEmpty().filter { it.isDigit() || it in "+*#" }
            if (cleaned.isEmpty()) {
                toast("Nothing dialable on the clipboard")
            } else {
                haptic(binding.keypadEntry)
                digits.append(cleaned)
                refresh()
            }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        TabBar.onTabScreenStart(this, Tab.KEYPAD)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            // Mirror read fails soft (§15): an empty list just means no matches.
            val all = runCatching { ServiceLocator.db(this@KeypadActivity).contactMirrorDao().all() }
                .getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                contacts = all
                refreshMatches()
            }
        }
    }

    private fun wireKeys() {
        val keys = mapOf(
            binding.key1 to "1",
            binding.key2 to "2",
            binding.key3 to "3",
            binding.key4 to "4",
            binding.key5 to "5",
            binding.key6 to "6",
            binding.key7 to "7",
            binding.key8 to "8",
            binding.key9 to "9",
            binding.keyStar to "*",
            binding.key0 to "0",
            binding.keyHash to "#",
        )
        for ((view, digit) in keys) {
            view.setOnClickListener {
                haptic(view)
                append(digit)
            }
        }
        // Long-press 1 dials voicemail via the voicemail: scheme — Telecom
        // resolves the carrier's number from the PhoneAccount (§12).
        binding.key1.setOnLongClickListener {
            haptic(binding.key1)
            if (!CallManager.placeVoicemail(this)) toast("Couldn't reach voicemail")
            true
        }
        // Long-press 0 yields '+'.
        binding.key0.setOnLongClickListener {
            haptic(binding.key0)
            if (digits.isNotEmpty() && digits.last() == '0') digits.deleteCharAt(digits.length - 1)
            append("+")
            true
        }
    }

    private fun append(text: String) {
        digits.append(text)
        refresh()
    }

    private fun backspace() {
        if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1)
        refresh()
    }

    private fun clearAll() {
        digits.clear()
        refresh()
    }

    private fun placeCall(explicit: String? = null) {
        val raw = explicit ?: digits.toString().takeIf { it.isNotEmpty() } ?: return
        val target = E164.normalize(raw) ?: raw // unparseable input still dials raw (§15 spirit)
        if (!CallManager.place(this, target)) toast("Couldn't place call")
    }

    private fun refresh() {
        binding.keypadEntry.text = digits.toString()
        val hasInput = digits.isNotEmpty()
        // Backspace stays enabled when empty so its long-press clear still works.
        binding.keyDelete.alpha = if (hasInput) 1f else DISABLED_ALPHA
        binding.keypadCall.alpha = if (hasInput) 1f else DISABLED_ALPHA
        refreshMatches()
    }

    // ---- T9 match overlay -------------------------------------------------------

    private fun refreshMatches() {
        val query = digits.toString()
        val hits = if (query.isEmpty()) emptyList() else rankMatches(query)
        renderMatches(hits)
        // No match on a real-length number: offer the save (standard dialer
        // affordance) instead of a dead empty overlay.
        val offerSave = hits.isEmpty() && query.count(Char::isDigit) >= MIN_SAVE_DIGITS
        if (offerSave) binding.keypadMatches.addView(addContactRow(query))
        binding.keypadMatchScroll.visibility =
            if (hits.isEmpty() && !offerSave) View.GONE else View.VISIBLE
    }

    private fun addContactRow(raw: String): View {
        val number = E164.normalize(raw) ?: raw
        return TextView(this).apply {
            text = "＋ Save $number to contacts"
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Xx_Body)
            setPadding(0, 24, 0, 24)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.ContactsContract.Intents.Insert.ACTION,
                            android.provider.ContactsContract.Contacts.CONTENT_URI,
                        ).putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, number),
                    )
                }.onFailure { toast("No contacts app available") }
            }
        }
    }

    private data class Match(val mirror: ContactMirrorEntity, val hit: T9.Hit)

    /** Number-prefix hits first, then number-contains, then name matches; capped. */
    private fun rankMatches(query: String): List<Match> =
        contacts.asSequence()
            .mapNotNull { c ->
                T9.hit(query, c.e164, c.displayName)?.let { Match(c, it) }
            }
            .sortedWith(
                compareBy(
                    { m -> when (val h = m.hit) {
                        is T9.Hit.InNumber -> if (h.digitStart == 0) 0 else 1
                        is T9.Hit.InName -> 2
                    } },
                    { it.mirror.displayName.lowercase() },
                ),
            )
            .take(MAX_MATCHES)
            .toList()

    private fun renderMatches(matches: List<Match>) {
        val container = binding.keypadMatches
        container.removeAllViews()
        for (match in matches) {
            container.addView(matchRow(match))
        }
    }

    private fun matchRow(match: Match): View {
        val density = resources.displayMetrics.density
        fun pad(v: Int) = (v * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad(10), 0, pad(10))
            // Themed attribute resolution: selectableItemBackground for the ripple.
            val a = context.theme.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackground),
            )
            background = a.getDrawable(0)
            a.recycle()
            isClickable = true
            isFocusable = true
        }

        val name = TextView(this).apply {
            text = highlighted(match.mirror.displayName, nameHitRange(match))
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Xx_Body)
            maxLines = 1
        }
        val number = TextView(this).apply {
            text = highlighted(match.mirror.e164, numberHitRange(match))
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Xx_BodySmall)
            maxLines = 1
        }
        row.addView(name)
        row.addView(number)
        row.setOnClickListener {
            haptic(row)
            digits.clear()
            digits.append(match.mirror.e164)
            refresh()
        }
        return row
    }

    private fun numberHitRange(match: Match): IntRange? = when (val h = match.hit) {
        is T9.Hit.InNumber -> T9.charRange(match.mirror.e164, h)
        is T9.Hit.InName -> null
    }

    private fun nameHitRange(match: Match): IntRange? = when (val hit = match.hit) {
        is T9.Hit.InName -> 0 until hit.prefixLen.coerceAtMost(match.mirror.displayName.length)
        is T9.Hit.InNumber -> null
    }

    /** Matched span reads at white_90 bold; the rest stays quiet white_80. */
    private fun highlighted(text: String, range: IntRange?): CharSequence {
        val span = SpannableStringBuilder(text)
        val strong = ContextCompat.getColor(this, R.color.pxx_white_90)
        val base = ContextCompat.getColor(this, R.color.pxx_white_80)
        span.setSpan(ForegroundColorSpan(base), 0, span.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        range?.takeIf { !it.isEmpty() && it.last < text.length }?.let { r ->
            span.setSpan(ForegroundColorSpan(strong), r.first, r.last + 1, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(Typeface.BOLD), r.first, r.last + 1, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }

    private fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) // VIBRATE held (§13)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MAX_MATCHES = 6
        const val MIN_SAVE_DIGITS = 3
        const val DISABLED_ALPHA = 0.38f
    }
}
