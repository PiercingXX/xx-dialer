package com.piercingxx.xxdialer.ui

import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.ServiceLocator
import com.piercingxx.xxdialer.core.HiddenCallerPolicy
import com.piercingxx.xxdialer.core.Mode
import com.piercingxx.xxdialer.core.Reason
import com.piercingxx.xxdialer.core.RingPolicy
import com.piercingxx.xxdialer.core.RingRepeat
import com.piercingxx.xxdialer.core.RingRepeatPolicy
import com.piercingxx.xxdialer.core.Rules
import com.piercingxx.xxdialer.core.StirAction
import com.piercingxx.xxdialer.core.Verdict
import com.piercingxx.xxdialer.core.Window
import com.piercingxx.xxdialer.data.BackupJson
import com.piercingxx.xxdialer.data.PatternRuleEntity
import com.piercingxx.xxdialer.data.ScreenLogEntity
import com.piercingxx.xxdialer.data.SettingsRepository
import com.piercingxx.xxdialer.databinding.ActivityRulesBinding
import com.piercingxx.xxdialer.databinding.ItemRuleRowBinding
import com.piercingxx.xxdialer.databinding.ViewPatternBuilderBinding
import com.piercingxx.xxdialer.databinding.ViewTestNumberSheetBinding
import com.piercingxx.xxdialer.databinding.ViewWindowEditorBinding
import com.piercingxx.xxdialer.ring.ChannelIds
import com.piercingxx.xxdialer.ring.TileCountdown
import com.piercingxx.xxdialer.telecom.FactSource
import com.piercingxx.xxdialer.telecom.LogRows
import com.piercingxx.xxdialer.util.BlocklistImport
import com.piercingxx.xxdialer.util.E164
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Rules tab (§12.4) — the control room, and the screen IS the documentation:
 * enforcement switch with the R11 confirm + observe banner, Expecting-a-call
 * countdown, the §6 precedence list rendered live, window editors, tone
 * picker, policies, pattern rules (mask builder + neighbor-spoof preset,
 * silence never block, D5), system-blocklist link, Test-a-number on THE
 * decide(), weekly tallies, full screening log, BackupJson export/import.
 */
class RulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRulesBinding

    private var suppressEnforce = false
    private var suppressPolicies = false
    private var currentMode: Mode = Mode.OBSERVING

    private var bypassUntil: Long? = null
    private var bypassMinutes: Int = DEFAULT_BYPASS_MINUTES

    private var unknownWindow: Window = Window(9 * 60, 17 * 60, Window.ALL_DAYS)
    private var businessWindow: Window = Window(9 * 60, 19 * 60, Window.ALL_DAYS)

    // ---- activity-result launchers -------------------------------------------

    /** §4.3 law: a picked tone mints ring_unknown_vN+1 via mintUnknownTone(). */
    private val tonePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.let {
                IntentCompat.getParcelableExtra(
                    it,
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri::class.java,
                )
            } ?: return@registerForActivityResult
            lifecycleScope.launch {
                runCatching {
                    ServiceLocator.channelRegistry(this@RulesActivity).mintUnknownTone(uri)
                }
                renderTone()
            }
        }

    private val exportDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    val json = BackupJson.export(ServiceLocator.db(applicationContext))
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray())
                    } ?: error("no output stream for $uri")
                }.isSuccess
                withContext(Dispatchers.Main) {
                    toast(if (ok) "backup exported" else "export failed")
                }
            }
        }

    private val importDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val outcome = runCatching {
                    val json = contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().decodeToString()
                    } ?: error("unreadable document")
                    BackupJson.import(ServiceLocator.db(applicationContext), json).getOrThrow()
                }
                withContext(Dispatchers.Main) {
                    outcome.fold(
                        onSuccess = {
                            toast("backup restored")
                            load()
                        },
                        onFailure = { toast("import failed: ${it.message}") },
                    )
                }
            }
        }

    /**
     * §8 offline blocklist import: ACTION_OPEN_DOCUMENT text/plain → one
     * number per line → normalize on-device (R8) → insert survivors into
     * BlockedNumberContract (D6). Role refused ⇒ honest toast, no pretending.
     */
    private val importBlocklistDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val parsed = runCatching {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().decodeToString()
                    } ?: error("unreadable document")
                }.mapCatching { BlocklistImport.parse(it) }.getOrNull()

                var imported = 0
                var failed = 0
                var roleLost = false
                if (parsed != null) {
                    for (e164 in parsed.numbers) {
                        try {
                            contentResolver.insert(
                                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                                ContentValues().apply {
                                    put(
                                        BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
                                        e164,
                                    )
                                },
                            )
                            imported++
                        } catch (_: SecurityException) {
                            // Without the dialer role every remaining insert is
                            // refused too — stop and say why instead of
                            // reporting a partial success that isn't real.
                            roleLost = true
                            break
                        } catch (_: Exception) {
                            failed++
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    when {
                        parsed == null -> toast("blocklist import failed")
                        roleLost -> toast(
                            "blocked-number writes refused — XX-Dialer must hold the dialer role (re-run Setup)",
                        )
                        else -> toast("imported $imported, skipped ${parsed.skipped + failed}")
                    }
                }
            }
        }

    // ---- lifecycle --------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TabBar.bind(this, Tab.RULES)
        paintStaticCopy()

        binding.enforceSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressEnforce) return@setOnCheckedChangeListener
            if (checked) confirmEnforceThenFlip() else setMode(Mode.OBSERVING)
        }
        binding.enforceOfferButton.setOnClickListener { confirmEnforceThenFlip() }

        binding.expectButton.setOnClickListener { toggleExpecting() }
        lifecycleScope.launch {
            while (isActive) {
                renderExpecting()
                delay(COUNTDOWN_TICK_MS)
            }
        }

        attachPolicyListeners()

        binding.windowUnknownRow.setOnClickListener {
            editWindow(SettingsRepository.KEY_UNKNOWN_WINDOW, unknownWindow)
        }
        binding.windowBusinessRow.setOnClickListener {
            editWindow(SettingsRepository.KEY_BUSINESS_WINDOW, businessWindow)
        }
        binding.tonePick.setOnClickListener { launchTonePicker() }
        binding.neighborButton.setOnClickListener { toggleNeighborSpoof() }
        binding.addPatternButton.setOnClickListener { openPatternBuilder() }
        binding.blockedLinkRow.setOnClickListener { openSystemBlocklist() }
        binding.testRow.setOnClickListener { openTestSheet() }
        binding.exportButton.setOnClickListener { exportDoc.launch(BACKUP_FILE_NAME) }
        binding.importButton.setOnClickListener {
            importDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        binding.clearHistoryButton.setOnClickListener { confirmClearHistory() }
        binding.clearLogButton.setOnClickListener { confirmClearLog() }
        binding.importBlocklistButton.setOnClickListener {
            importBlocklistDoc.launch(arrayOf("text/plain"))
        }

        collectPatterns()
        load()
    }

    override fun onStart() {
        super.onStart()
        TabBar.onTabScreenStart(this, Tab.RULES)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun paintStaticCopy() {
        // §15 state copy lives in strings.xml so the switch, Setup's last step
        // and the offer notification cannot drift into three different answers
        // to "is it silencing yet?". Everything below is Rules-only chrome.
        binding.observeBanner.text = getString(R.string.watch_banner)
        binding.enforceLabel.text = getString(R.string.silencing_switch_label)
        binding.enforceOfferButton.text = getString(R.string.silencing_offer_button)
        binding.precedEyebrow.text = "EVALUATION ORDER · LIVE"
        binding.windowsEyebrow.text = "WINDOWS"
        binding.windowUnknownTitle.text = "Unknown callers"
        binding.windowBusinessTitle.text = "Business tier"
        binding.toneEyebrow.text = "RINGING"
        binding.tonePick.text = "Change tone"
        binding.ringRepeatLabel.text = getString(R.string.ring_repeat_label)
        binding.chipRingOnce.text = getString(R.string.ring_repeat_once)
        binding.chipRingTwice.text = getString(R.string.ring_repeat_twice)
        binding.chipRingUntilVm.text = getString(R.string.ring_repeat_until_vm)
        binding.policiesEyebrow.text = "POLICIES"
        binding.phoneEyebrow.text = "PHONE"
        binding.vvmEyebrow.text = "VOICEMAIL"
        binding.dataEyebrow.text = "DATA"
        binding.hiddenPolicyLabel.text = getString(R.string.hidden_caller_label)
        // Chips label the CHOICE, never the constant behind it. Printing
        // HiddenCallerPolicy.name / StirAction.name / the persisted notif
        // token put storage vocabulary on screen and quietly welded the UI to
        // the wire format — renaming a constant would have reworded the
        // screen. The mapping from chip id to stored value is unchanged and
        // still lives in attachPolicyListeners(); only the text moved.
        binding.chipHiddenUnknown.text = getString(R.string.hidden_caller_unknown)
        binding.chipHiddenSilence.text = getString(R.string.hidden_caller_silence)
        binding.chipHiddenBlock.text = getString(R.string.hidden_caller_block)
        binding.stirLabel.text = getString(R.string.stir_label)
        binding.chipStirBlock.text = getString(R.string.stir_block)
        binding.chipStirSilence.text = getString(R.string.stir_silence)
        binding.chipStirOff.text = getString(R.string.stir_off)
        binding.repeatLabel.text = getString(R.string.repeat_caller_label)
        binding.notifLabel.text = getString(R.string.silenced_notif_label)
        binding.chipNotifImmediate.text = getString(R.string.silenced_notif_immediate)
        binding.chipNotifDaily.text = getString(R.string.silenced_notif_daily)
        binding.chipNotifNever.text = getString(R.string.silenced_notif_never)
        binding.answerLabel.text = "Answer interaction on the incoming screen"
        binding.chipAnswerTap.text = "TAP"
        binding.chipAnswerSlide.text = "SLIDE"
        binding.bypassLabel.text = getString(R.string.bypass_label)
        binding.chipBypass30.text = "30 MIN"
        binding.chipBypass2h.text = "2 H"
        binding.chipBypass8h.text = "8 H"
        binding.groupRecentsLabel.text = "Group consecutive same-number calls in Recents"
        binding.tabsLabel.text = "Hide tabs (Rules stays)"
        binding.chipTabRecents.text = "RECENTS"
        binding.chipTabKeypad.text = "KEYPAD"
        binding.chipTabPeople.text = "PEOPLE"
        binding.chipTabVoicemail.text = "VOICEMAIL"
        binding.vvmLabel.text = getString(R.string.vvm_label)
        binding.vvmCaption.text = getString(R.string.vvm_caption)
        binding.vvmNetworkNote.text = getString(R.string.vvm_network_note)
        binding.patternsEyebrow.text = "PATTERN RULES"
        binding.exemptionNote.text =
            "Every pattern rule carries the contacts exemption, non-optionally: " +
                "a number matching a pattern that resolves to a saved contact rings anyway."
        binding.neighborButton.text = "Neighbor-spoof preset · silence, off until tapped"
        binding.addPatternButton.text = "Add pattern rule"
        binding.blockedEyebrow.text = "BLOCKLIST"
        binding.blockedLinkTitle.text = "System blocked numbers"
        binding.blockedLinkDetail.text = "open ↗"
        binding.testEyebrowRow.text = "TEST A NUMBER"
        binding.testRowTitle.text = "Test a number"
        binding.testRowDetail.text = "try any number ↗"
        binding.tallyEyebrow.text = "THIS WEEK"
        binding.tallyScreenedLabel.text = "SCREENED"
        binding.tallySilencedLabel.text = "SILENCED"
        binding.tallyBlockedLabel.text = "BLOCKED"
        binding.logEyebrow.text = "SCREENING LOG"
        binding.exportButton.text = "Export backup"
        binding.importButton.text = "Import backup"
        binding.importBlocklistButton.text = "Import blocklist"
        binding.clearHistoryButton.text = "Clear call history"
        binding.clearLogButton.text = "Clear screening log"
    }

    // ---- load / render -----------------------------------------------------------

    private fun load() {
        lifecycleScope.launch {
            val settings = ServiceLocator.settings(this@RulesActivity)
            val rules = runCatching { ServiceLocator.rules(this@RulesActivity).current() }
                .getOrDefault(Rules())
            currentMode = runCatching { settings.enforcementMode() }.getOrDefault(Mode.OBSERVING)
            val offer = runCatching { settings.shouldOfferEnforcement(System.currentTimeMillis()) }
                .getOrDefault(false)

            suppressEnforce = true
            binding.enforceSwitch.isChecked = currentMode == Mode.ENFORCING
            suppressEnforce = false
            binding.observeBanner.isVisible = currentMode == Mode.OBSERVING
            binding.enforceOfferButton.isVisible = offer && currentMode == Mode.OBSERVING
            binding.enforceCaption.text = when (currentMode) {
                Mode.ENFORCING -> getString(R.string.silencing_caption_on)
                Mode.OBSERVING -> getString(R.string.silencing_caption_off)
            }

            bypassUntil = runCatching { settings.bypassUntilMillis() }.getOrNull()
            bypassMinutes = runCatching { settings.bypassDurationMinutes() }
                .getOrDefault(DEFAULT_BYPASS_MINUTES)
            renderExpecting()

            unknownWindow = rules.unknownWindow
            businessWindow = rules.businessWindow
            renderPrecedence(rules)
            binding.windowUnknownDetail.text = WindowChips.windowLine(rules.unknownWindow)
            binding.windowBusinessDetail.text = WindowChips.windowLine(rules.businessWindow)

            renderTone()
            syncPolicyControls(settings, rules)
            renderWeek()
            renderLog()
            renderUpstreamWarning()
        }
    }

    // ---- privacy wipes ---------------------------------------------------------

    /** Deletes every platform CallLog row (WRITE_CALL_LOG rides the role). Irreversible. */
    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear call history?")
            .setMessage("Deletes every call from the system call log. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = runCatching {
                        contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
                    }.isSuccess
                    withContext(Dispatchers.Main) {
                        toast(if (ok) "Call history cleared" else "Couldn't clear — call log refused")
                    }
                }
            }
            .show()
    }

    /** Wipes the R7 reason store. The platform call log is untouched. */
    private fun confirmClearLog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear screening log?")
            .setMessage(
                "Deletes every screening reason and the weekly tallies. " +
                    "The system call log keeps its own entries.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    runCatching { ServiceLocator.db(this@RulesActivity).screenLogDao().deleteAll() }
                        .fold(
                            onSuccess = {
                                toast("Screening log cleared")
                                load()
                            },
                            onFailure = { toast("Couldn't clear the log") },
                        )
                }
            }
            .show()
    }

    /**
     * §4.4/WS10 upstream-setting detection: platform-blocked CallLog rows
     * whose BLOCK_REASON says the OS's own "block callers not in contacts" /
     * "block unknown" toggles ate the call before this app's policy ever saw
     * it. Empirical — no hidden-settings API needed; unreadable log ⇒ no
     * warning (never cry wolf on a permissions hiccup).
     */
    private fun renderUpstreamWarning() {
        lifecycleScope.launch(Dispatchers.IO) {
            val starving = runCatching {
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.BLOCK_REASON),
                    "${CallLog.Calls.TYPE} = ?",
                    arrayOf(CallLog.Calls.BLOCKED_TYPE.toString()),
                    "${CallLog.Calls.DATE} DESC LIMIT 50",
                )?.use { c ->
                    var found = false
                    val idx = c.getColumnIndex(CallLog.Calls.BLOCK_REASON)
                    while (idx >= 0 && c.moveToNext()) {
                        when (c.getInt(idx)) {
                            CallLog.Calls.BLOCK_REASON_NOT_IN_CONTACTS,
                            CallLog.Calls.BLOCK_REASON_PAY_PHONE,
                            CallLog.Calls.BLOCK_REASON_RESTRICTED_NUMBER,
                            CallLog.Calls.BLOCK_REASON_UNKNOWN_NUMBER,
                            -> { found = true }
                        }
                        if (found) break
                    }
                    found
                } ?: false
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                binding.upstreamWarning.isVisible = starving
                if (starving) {
                    binding.upstreamWarning.text =
                        "⚠ The OS's own call blocking (e.g. \"block callers not in contacts\") " +
                            "recently ate calls before this policy saw them — check the system " +
                            "Phone/blocking settings, or those callers can never ring here."
                }
            }
        }
    }

    private fun renderPrecedence(rules: Rules) {
        binding.precedList.removeAllViews()
        PrecedenceRows.build(rules.unknownWindow, rules.businessWindow, rules.stirAction)
            .forEach { row ->
                val rowView = ItemRuleRowBinding.inflate(layoutInflater, binding.precedList, false)
                rowView.rowIndex.text = row.n.toString()
                rowView.rowTitle.text = row.condition
                rowView.rowDetail.text =
                    if (row.tone == null) row.verdict else "${row.verdict} · ${row.tone}"
                rowView.rowDetail.setTextColor(
                    ContextCompat.getColor(
                        this,
                        when (row.kind) {
                            PrecedenceRows.Kind.RING -> R.color.pxx_white_90
                            PrecedenceRows.Kind.SILENCE -> R.color.pxx_white_50
                            PrecedenceRows.Kind.BLOCK -> R.color.pxx_error
                            PrecedenceRows.Kind.INFO -> R.color.pxx_white_50
                        },
                    ),
                )
                binding.precedList.addView(rowView.root)
            }
    }

    private fun renderExpecting() {
        val active = TileCountdown.isActive(bypassUntil, System.currentTimeMillis())
        binding.expectCountdown.isVisible = active
        if (active) {
            binding.expectCountdown.text =
                TileCountdown.label(bypassUntil, System.currentTimeMillis())
        }
    }

    private fun toggleExpecting() {
        lifecycleScope.launch {
            val settings = ServiceLocator.settings(this@RulesActivity)
            if (TileCountdown.isActive(bypassUntil, System.currentTimeMillis())) {
                settings.setBypassUntil(null) // D15: bounded; tapping ends it early
                toast("expecting a call — off")
            } else {
                settings.setBypassUntil(System.currentTimeMillis() + bypassMinutes * 60_000L)
                toast("every silenced call rings for $bypassMinutes min")
            }
            bypassUntil = runCatching { settings.bypassUntilMillis() }.getOrNull()
            renderExpecting()
        }
    }

    // ---- enforcement -----------------------------------------------------------

    /**
     * The §15 guarantee has a UI half: silencing is never turned on for the
     * user, so every path that could turn it on lands here first and says, in
     * the same words the switch and the offer notification use, what changes.
     * Cancelling must leave the switch reading OFF — the negative button
     * un-checks it rather than trusting the toggle to bounce back.
     */
    private fun confirmEnforceThenFlip() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.silencing_confirm_title)
            .setMessage(R.string.silencing_confirm_body)
            .setPositiveButton(R.string.silencing_confirm_yes) { _, _ -> setMode(Mode.ENFORCING) }
            .setNegativeButton(R.string.silencing_confirm_no) { _, _ ->
                suppressEnforce = true
                binding.enforceSwitch.isChecked = false
                suppressEnforce = false
            }
            .show()
    }

    private fun setMode(mode: Mode) {
        lifecycleScope.launch {
            ServiceLocator.settings(this@RulesActivity).setEnforcementMode(mode)
            load()
        }
    }

    // ---- windows -----------------------------------------------------------------

    private fun editWindow(key: String, current: Window) {
        val editor = ViewWindowEditorBinding.inflate(layoutInflater)
        editor.winTimesEyebrow.text = "TIMES"
        editor.winDaysEyebrow.text = "DAYS"
        editor.winSemanticsNote.text =
            "Start inclusive · end exclusive · end ≤ start wraps past midnight."

        var startMinute = current.startMinuteOfDay
        var endMinute = current.endMinuteOfDay
        var daysMask = current.daysMask

        fun paintRows() {
            editor.winStartRow.text =
                "Start · ${WindowChips.clockLabel(startMinute)}   (tap to change)"
            editor.winEndRow.text =
                "End · ${WindowChips.clockLabel(endMinute)}   (tap to change)"
        }
        paintRows()

        editor.winStartRow.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                startMinute = h * 60 + m
                paintRows()
            }, startMinute / 60, startMinute % 60, true).show()
        }
        editor.winEndRow.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                endMinute = h * 60 + m
                paintRows()
            }, endMinute / 60, endMinute % 60, true).show()
        }

        WindowChips.DAY_LABELS.forEachIndexed { index, label ->
            val chip = Chip(ContextThemeWrapper(this, R.style.Widget_Xx_Chip))
            chip.text = label
            chip.isCheckable = true
            chip.isChecked = daysMask and WindowChips.dayBit(index) != 0
            styleChip(chip, chip.isChecked)
            chip.setOnCheckedChangeListener { _, checked ->
                daysMask = WindowChips.toggled(daysMask, index)
                styleChip(chip, checked)
            }
            editor.winDays.addView(chip)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit window")
            .setView(editor.root)
            .setPositiveButton("Save") { _, _ ->
                if (daysMask == 0) {
                    toast("at least one day must stay enabled")
                } else {
                    lifecycleScope.launch {
                        runCatching { Window(startMinute, endMinute, daysMask) }
                            .onSuccess { window ->
                                ServiceLocator.settings(this@RulesActivity)
                                    .setString(key, SettingsRepository.windowToJson(window))
                                load()
                            }
                            .onFailure { toast("invalid window") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- tone ---------------------------------------------------------------------

    private suspend fun currentUnknownSound(): Uri? {
        val id = runCatching {
            ServiceLocator.channelRegistry(this).channelIdFor(ChannelIds.PURPOSE_RING_UNKNOWN)
        }.getOrNull() ?: return null
        val manager = getSystemService(NotificationManager::class.java)
        return runCatching { manager.getNotificationChannel(id)?.sound }.getOrNull()
    }

    private fun renderTone() {
        lifecycleScope.launch {
            val sound = currentUnknownSound()
            binding.toneCurrent.text =
                "Unknown callers ring: ${sound?.lastPathSegment ?: "shipped xx_unknown"}"
        }
    }

    private fun launchTonePicker() {
        lifecycleScope.launch {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUnknownSound())
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            runCatching { tonePicker.launch(intent) }
                .onFailure { toast("tone picker unavailable") }
        }
    }

    // ---- policies --------------------------------------------------------------------

    private fun attachPolicyListeners() {
        binding.hiddenPolicyGroup.setOnCheckedStateChangeListener { group, _ ->
            if (suppressPolicies) return@setOnCheckedStateChangeListener
            restyleGroup(group)
            persistSetting(
                SettingsRepository.KEY_HIDDEN_CALLER_POLICY,
                when (group.checkedChipId) {
                    R.id.chipHiddenSilence -> HiddenCallerPolicy.SILENCE.name
                    R.id.chipHiddenBlock -> HiddenCallerPolicy.BLOCK.name
                    else -> HiddenCallerPolicy.UNKNOWN.name
                },
            )
        }
        binding.stirGroup.setOnCheckedStateChangeListener { group, _ ->
            if (suppressPolicies) return@setOnCheckedStateChangeListener
            restyleGroup(group)
            persistSetting(
                SettingsRepository.KEY_STIR_ACTION,
                when (group.checkedChipId) {
                    R.id.chipStirSilence -> StirAction.SILENCE.name
                    R.id.chipStirOff -> StirAction.OFF.name
                    else -> StirAction.BLOCK.name
                },
            )
        }
        binding.notifGroup.setOnCheckedStateChangeListener { group, _ ->
            if (suppressPolicies) return@setOnCheckedStateChangeListener
            restyleGroup(group)
            persistSetting(
                SettingsRepository.KEY_SILENCED_NOTIF_POLICY,
                when (group.checkedChipId) {
                    R.id.chipNotifDaily -> NOTIF_DAILY
                    R.id.chipNotifNever -> NOTIF_NEVER
                    else -> NOTIF_IMMEDIATE
                },
            )
        }
        binding.repeatSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressPolicies) return@setOnCheckedChangeListener
            persistSetting(
                SettingsRepository.KEY_REPEAT_CALLER_ENABLED,
                if (checked) "1" else "0",
            )
        }
        binding.answerGroup.setOnCheckedStateChangeListener { group, _ ->
            if (suppressPolicies) return@setOnCheckedStateChangeListener
            restyleGroup(group)
            persistSetting(
                SettingsRepository.KEY_ANSWER_INTERACTION,
                if (group.checkedChipId == R.id.chipAnswerSlide) ANSWER_SLIDER else ANSWER_TAP,
            )
        }
        binding.ringRepeatGroup.setOnCheckedStateChangeListener { group, _ ->
            if (suppressPolicies) return@setOnCheckedStateChangeListener
            restyleGroup(group)
            persistSetting(
                SettingsRepository.KEY_RING_REPEAT,
                when (group.checkedChipId) {
                    R.id.chipRingTwice -> RingRepeatPolicy.TOKEN_TWICE
                    R.id.chipRingUntilVm -> RingRepeatPolicy.TOKEN_UNTIL_VOICEMAIL
                    else -> RingRepeatPolicy.TOKEN_ONCE
                },
            )
        }
        binding.bypassGroup.setOnCheckedStateChangeListener { group, _ ->
            if (suppressPolicies) return@setOnCheckedStateChangeListener
            restyleGroup(group)
            persistSetting(
                SettingsRepository.KEY_BYPASS_DURATION_MINUTES,
                when (group.checkedChipId) {
                    R.id.chipBypass30 -> "30"
                    R.id.chipBypass8h -> "480"
                    else -> "120"
                },
            )
        }
        binding.groupRecentsSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressPolicies) return@setOnCheckedChangeListener
            persistSetting(
                SettingsRepository.KEY_GROUP_RECENTS,
                if (checked) "1" else "0",
            )
        }
        // Visual voicemail (§12): OPT-IN. Flipping the toggle persists the
        // setting; the Voicemail hide-chip's visibility is re-gated on it in
        // syncPolicyControls via RulesScreenVoicemail.voicemailHideChipVisible.
        binding.vvmSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressPolicies) return@setOnCheckedChangeListener
            persistSetting(
                SettingsRepository.KEY_VISUAL_VOICEMAIL,
                if (checked) "1" else "0",
            )
            syncVoicemailGate()
        }
        // Multi-select: checked chip = hidden tab. Rules itself is absent by
        // construction — the setting can always be reached to undo itself.
        listOf(
            binding.chipTabRecents,
            binding.chipTabKeypad,
            binding.chipTabPeople,
            binding.chipTabVoicemail,
        ).forEach { chip ->
            chip.setOnCheckedChangeListener { _, _ ->
                if (suppressPolicies) return@setOnCheckedChangeListener
                val hidden = TabBar.hiddenNames(
                    hideRecents = binding.chipTabRecents.isChecked,
                    hideKeypad = binding.chipTabKeypad.isChecked,
                    hidePeople = binding.chipTabPeople.isChecked,
                    hideVoicemail = binding.chipTabVoicemail.isChecked,
                )
                lifecycleScope.launch {
                    runCatching { ServiceLocator.settings(this@RulesActivity).setHiddenTabs(hidden) }
                    TabBar.onTabScreenStart(this@RulesActivity, Tab.RULES)
                }
            }
        }
    }

    private fun syncPolicyControls(settings: SettingsRepository, rules: Rules) {
        suppressPolicies = true
        binding.hiddenPolicyGroup.check(
            when (rules.hiddenCallerPolicy) {
                HiddenCallerPolicy.SILENCE -> R.id.chipHiddenSilence
                HiddenCallerPolicy.BLOCK -> R.id.chipHiddenBlock
                HiddenCallerPolicy.UNKNOWN -> R.id.chipHiddenUnknown
            },
        )
        binding.stirGroup.check(
            when (rules.stirAction) {
                StirAction.SILENCE -> R.id.chipStirSilence
                StirAction.OFF -> R.id.chipStirOff
                StirAction.BLOCK -> R.id.chipStirBlock
            },
        )
        binding.repeatSwitch.isChecked = rules.repeatCallerEnabled
        lifecycleScope.launch {
            val notif = runCatching { settings.silencedNotifPolicy() }
                .getOrDefault(NOTIF_IMMEDIATE)
            binding.notifGroup.check(
                when (notif) {
                    NOTIF_DAILY -> R.id.chipNotifDaily
                    NOTIF_NEVER -> R.id.chipNotifNever
                    else -> R.id.chipNotifImmediate
                },
            )
            val answer = runCatching { settings.getString(SettingsRepository.KEY_ANSWER_INTERACTION) }
                .getOrNull().orEmpty()
            binding.answerGroup.check(
                if (answer == ANSWER_SLIDER) R.id.chipAnswerSlide else R.id.chipAnswerTap,
            )
            val ringRepeat = runCatching { settings.ringRepeat() }.getOrDefault(RingRepeat.ONCE)
            binding.ringRepeatGroup.check(
                when (ringRepeat) {
                    RingRepeat.TWICE -> R.id.chipRingTwice
                    RingRepeat.UNTIL_VOICEMAIL -> R.id.chipRingUntilVm
                    RingRepeat.ONCE -> R.id.chipRingOnce
                },
            )
            val bypassMinutes = runCatching { settings.bypassDurationMinutes() }.getOrDefault(120)
            binding.bypassGroup.check(
                when (bypassMinutes) {
                    30 -> R.id.chipBypass30
                    480 -> R.id.chipBypass8h
                    else -> R.id.chipBypass2h
                },
            )
            binding.groupRecentsSwitch.isChecked =
                runCatching { settings.getString(SettingsRepository.KEY_GROUP_RECENTS) }
                    .getOrNull() != "0"
            val hiddenTabs = runCatching { settings.hiddenTabs() }.getOrDefault(emptySet())
            binding.chipTabRecents.isChecked = "recents" in hiddenTabs
            binding.chipTabKeypad.isChecked = "keypad" in hiddenTabs
            binding.chipTabPeople.isChecked = "people" in hiddenTabs
            // Visual voicemail (§12): read the toggle and gate the hide-chip on
            // it. Off ⇒ the chip is gone (the tab is absent, not hidden); on ⇒
            // the chip appears and reflects the hidden set like the others.
            val vvmEnabled = runCatching { settings.visualVoicemailEnabled() }
                .getOrDefault(false)
            binding.vvmSwitch.isChecked = vvmEnabled
            syncVoicemailGate()
            listOf(binding.hiddenPolicyGroup, binding.stirGroup, binding.notifGroup,
                binding.answerGroup, binding.bypassGroup, binding.ringRepeatGroup)
                .forEach(::restyleGroup)
            suppressPolicies = false
        }
        listOf(binding.hiddenPolicyGroup, binding.stirGroup).forEach(::restyleGroup)
    }

    private fun persistSetting(key: String, value: String) {
        lifecycleScope.launch {
            runCatching { ServiceLocator.settings(this@RulesActivity).setString(key, value) }
        }
    }

    /**
     * §12 gated hide-chip: the Voicemail chip in the "Hide tabs" group is
     * present only while Visual voicemail is on. Off ⇒ the tab is absent
     * entirely, so its hide-chip is gone too (todo.md) — never merely
     * disabled. The gate is [RulesScreenVoicemail.voicemailHideChipVisible],
     * the single source of truth the unit test drives.
     */
    private fun syncVoicemailGate() {
        lifecycleScope.launch {
            val settings = ServiceLocator.settings(this@RulesActivity)
            val vvmEnabled = runCatching { settings.visualVoicemailEnabled() }
                .getOrDefault(false)
            val hiddenTabs = runCatching { settings.hiddenTabs() }.getOrDefault(emptySet())
            binding.chipTabVoicemail.isVisible =
                RulesScreenVoicemail.voicemailHideChipVisible(vvmEnabled)
            binding.chipTabVoicemail.isChecked = "voicemail" in hiddenTabs
        }
    }

    private fun restyleGroup(group: ChipGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is Chip) styleChip(child, child.isChecked)
        }
    }

    /** Monochrome selection: emphasis invert when on, hairline outline when off. */
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

    // ---- pattern rules -----------------------------------------------------------

    private fun collectPatterns() {
        lifecycleScope.launch {
            runCatching {
                ServiceLocator.db(this@RulesActivity).patternRuleDao().all()
                    .collect { rows -> renderPatterns(rows) }
            }
        }
    }

    private fun renderPatterns(rows: List<PatternRuleEntity>) {
        binding.patternsBox.removeAllViews()
        if (rows.isEmpty()) {
            addNoteRow(binding.patternsBox, "No pattern rules yet.")
            return
        }
        rows.forEach { entity ->
            val row = ItemRuleRowBinding.inflate(layoutInflater, binding.patternsBox, false)
            val digits = entity.e164Prefix.filter(Char::isDigit)
            val blocking = entity.action == BackupJson.ACTION_BLOCK
            row.rowIndex.text = if (blocking) "✗" else "→"
            row.rowIndex.setTextColor(
                ContextCompat.getColor(this, if (blocking) R.color.pxx_error else R.color.pxx_white_50),
            )
            row.rowTitle.text = MaskBuilder.preview(MaskBuilder.Draft(digits, entity.wildcards)) +
                if (entity.preset == PRESET_NEIGHBOR_SPOOF) "  ·  neighbor spoof" else ""
            row.rowDetail.text = entity.action
            row.rowAction.isVisible = true
            row.rowAction.setOnClickListener {
                lifecycleScope.launch {
                    runCatching {
                        ServiceLocator.db(this@RulesActivity).patternRuleDao().delete(entity.id)
                    }
                }
            }
            binding.patternsBox.addView(row.root)
        }
    }

    private fun addNoteRow(parent: ViewGroup, text: String) {
        val row = ItemRuleRowBinding.inflate(layoutInflater, parent, false)
        row.rowIndex.isVisible = false
        row.rowAction.isVisible = false
        row.rowTitle.text = text
        parent.addView(row.root)
    }

    /**
     * Neighbor-spoof preset (§8): derives the user's own six-digit prefix from
     * the line-1 number and arms a SILENCE rule — silence never block (D5).
     * OFF until tapped; tapping again disarms. No line number → honest
     * fallback pointing at manual entry.
     */
    private fun toggleNeighborSpoof() {
        lifecycleScope.launch {
            val telephony = getSystemService(TelephonyManager::class.java)
            // line1Number wants READ_PHONE_NUMBERS on 30+; a refusal lands in
            // the manual-entry fallback below, never a crash.
            val line = try {
                telephony.line1Number
            } catch (_: SecurityException) {
                null
            }
            val draft = line?.let { E164.normalize(it)?.removePrefix("+") }
                ?.let { MaskBuilder.neighborDraft(it) }
            if (draft == null) {
                toast("carrier number unavailable — enter your prefix manually")
                return@launch
            }
            val dao = ServiceLocator.db(this@RulesActivity).patternRuleDao()
            val armed = runCatching { dao.presets() }.getOrDefault(emptyList())
                .any { it.preset == PRESET_NEIGHBOR_SPOOF }
            if (!armed) {
                dao.insert(
                    PatternRuleEntity(
                        id = 0,
                        e164Prefix = draft.prefix,
                        wildcards = draft.wildcards,
                        action = BackupJson.ACTION_SILENCE,
                        preset = PRESET_NEIGHBOR_SPOOF,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                toast("neighbor spoof armed — silences ${MaskBuilder.preview(draft)}")
            } else {
                runCatching { dao.presets() }.getOrDefault(emptyList())
                    .filter { it.preset == PRESET_NEIGHBOR_SPOOF }
                    .forEach { dao.delete(it.id) }
                toast("neighbor spoof removed")
            }
        }
    }

    /** §8 mask builder: type a sample, slide to mask trailing digits. */
    private fun openPatternBuilder() {
        val builder = ViewPatternBuilderBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Add pattern rule")
            .setView(builder.root)
            .setNegativeButton("Cancel", null)
            .create()

        var action: String = BackupJson.ACTION_BLOCK
        var maskedTail = 1
        var fallbackDigits = false

        fun digits(): String? {
            val typed = builder.patternInput.text?.toString().orEmpty()
            if (MaskBuilder.digitsOf(typed).isEmpty()) return null
            val normalized = runCatching { E164.normalize(typed)?.removePrefix("+") }.getOrNull()
            fallbackDigits = normalized == null
            return normalized ?: MaskBuilder.digitsOf(typed)
        }

        fun paint() {
            val all = digits()
            val max = (all?.length ?: 0) - 1
            builder.patternMask.max = max.coerceAtLeast(0)
            builder.patternMask.isEnabled = max >= 1
            if (maskedTail > max.coerceAtLeast(1)) maskedTail = max.coerceAtLeast(1)
            builder.patternMask.progress = maskedTail - 1
            val draft = all?.let { MaskBuilder.build(it, maskedTail) }
            builder.patternMaskLabel.text =
                if (max >= 1) "$maskedTail trailing digit${if (maskedTail == 1) "" else "s"} masked"
                else "Type a longer number to mask digits"
            builder.patternPreview.text = draft?.let(MaskBuilder::preview) ?: "—"
            builder.patternSave.isEnabled = draft != null
            builder.patternHint.text = when {
                all == null -> "Type a full sample number first."
                fallbackDigits -> "Unrecognized format — saving dialed digits as-is."
                else -> "Matches exactly ${draft!!.totalLength}-digit numbers starting with the kept prefix."
            }
        }

        val actionChips = listOf(
            BackupJson.ACTION_BLOCK to Chip(ContextThemeWrapper(this, R.style.Widget_Xx_Chip)).apply {
                text = "BLOCK"
                isCheckable = true
                isChecked = true
            },
            BackupJson.ACTION_SILENCE to Chip(ContextThemeWrapper(this, R.style.Widget_Xx_Chip)).apply {
                text = "SILENCE"
                isCheckable = true
            },
        )
        actionChips.forEach { (value, chip) ->
            chip.setOnCheckedChangeListener { _, checked -> styleChip(chip, checked) }
            chip.setOnClickListener {
                action = value
                actionChips.forEach { (v, c) ->
                    c.isChecked = v == value
                    styleChip(c, v == value)
                }
            }
            styleChip(chip, chip.isChecked)
            builder.patternAction.addView(chip)
        }

        builder.patternInput.doAfterTextChanged {
            maskedTail = 1
            paint()
        }
        builder.patternMask.setOnSeekBarChangeListener(object :
                android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    if (seek.max >= 1) maskedTail = progress + 1
                    paint()
                }

                override fun onStartTrackingTouch(seek: android.widget.SeekBar) = Unit
                override fun onStopTrackingTouch(seek: android.widget.SeekBar) = Unit
            })
        builder.patternEyebrow.text = "MASK TRAILING DIGITS"
        builder.patternPreview.text = "—"
        builder.patternSave.text = "SAVE RULE"
        builder.patternSave.isEnabled = false
        builder.patternSave.setOnClickListener {
            val draft = digits()?.let { MaskBuilder.build(it, maskedTail) } ?: return@setOnClickListener
            lifecycleScope.launch {
                runCatching {
                    ServiceLocator.db(this@RulesActivity).patternRuleDao().insert(
                        PatternRuleEntity(
                            id = 0,
                            e164Prefix = draft.prefix,
                            wildcards = draft.wildcards,
                            action = action,
                            preset = null,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                dialog.dismiss()
            }
        }

        paint()
        dialog.show()
    }

    // ---- blocklist + test ---------------------------------------------------------

    private fun openSystemBlocklist() {
        val telecom = getSystemService(TelecomManager::class.java)
        val intent = runCatching { telecom.createManageBlockedNumbersIntent() }.getOrNull()
        if (intent == null) {
            toast("blocked-numbers screen unavailable on this build")
        } else {
            runCatching { startActivity(intent) }
                .onFailure { toast("blocked-numbers screen needs the dialer role") }
        }
    }

    /**
     * Test-a-number (§12.4, §16): the sheet runs THE decide() over real facts
     * via FactSource — the exact production path — and prints what the log
     * would say, verdict vocabulary included. Nothing simulated.
     */
    private fun openTestSheet() {
        val sheetView = ViewTestNumberSheetBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetView.root)

        sheetView.testEyebrow.text = "TEST A NUMBER"
        sheetView.testGo.text = "TEST THIS NUMBER"
        // The claim worth making here is that nothing is simulated. Saying it
        // in English keeps the claim and drops the function name.
        sheetView.testModeLine.text =
            "Runs the same check a real call runs, against your live rules and contacts."

        sheetView.testGo.setOnClickListener {
            val raw = sheetView.testInput.text?.toString()?.trim().orEmpty()
            val e164 = runCatching { E164.normalize(raw) }.getOrNull()
            if (e164 == null) {
                sheetView.testResultBlock.isVisible = false
                sheetView.testError.isVisible = true
                sheetView.testError.text = "Not a recognizable phone number."
                return@setOnClickListener
            }
            sheetView.testError.isVisible = false
            sheetView.testGo.isEnabled = false
            lifecycleScope.launch {
                val now = LocalDateTime.now()
                val facts = FactSource(ServiceLocator.db(applicationContext), applicationContext)
                    .assemble(
                        numberE164 = e164,
                        presentation = 1, // ALLOWED; the test dials a real number shape
                        stirFailed = false,
                        emergencyCallbackExtraPresent = false,
                        nowEpochMillis = System.currentTimeMillis(),
                        elapsedNowMillis = SystemClock.elapsedRealtime(),
                        now = now,
                    )
                val rules = runCatching { ServiceLocator.rules(this@RulesActivity).current() }
                    .getOrDefault(Rules())
                val verdict = RingPolicy.decide(now, facts, rules)
                val reason = LogRows.reason(verdict, facts, rules, now)
                val modeLabel = LogRows.modeLabel(LogRows.modeName(currentMode))

                sheetView.testVerdict.text = describe(verdict)
                sheetView.testVerdict.setTextColor(
                    ContextCompat.getColor(
                        this@RulesActivity,
                        when (verdict) {
                            is Verdict.Block -> R.color.pxx_error
                            is Verdict.Silence -> R.color.pxx_white_80
                            is Verdict.Ring -> R.color.pxx_signal
                        },
                    ),
                )
                sheetView.testReason.text = reason?.uiLabel.orEmpty()
                // The words the log row itself would carry, not the field
                // names behind them — the point of the preview is that you
                // can go and find this line afterwards. The reason is already
                // spelled out above, so it is not repeated here; the mode is,
                // because it decides whether a real call would have rung.
                val disposition = LogRows.dispositionWord(verdict.token())?.lowercase()
                sheetView.testLogLine.text =
                    "would log: ${disposition ?: "—"} · $modeLabel"
                sheetView.testResultBlock.isVisible = true
                sheetView.testGo.isEnabled = true
            }
        }
        dialog.show()
    }

    private fun describe(verdict: Verdict): String = when (verdict) {
        is Verdict.Block -> "✗ BLOCKED"
        is Verdict.Silence -> "→ SILENCED"
        is Verdict.Ring -> when (verdict.tone.name) {
            "UNKNOWN" -> "✓ RINGS · unknown tone"
            else -> "✓ RINGS · default tone"
        }
    }

    // ---- week + log ------------------------------------------------------------------

    private suspend fun renderWeek() {
        val startOfWeek = java.time.LocalDate.now().with(DayOfWeek.MONDAY)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tallies = runCatching {
            ServiceLocator.db(this).screenLogDao().talliesSince(startOfWeek)
        }.getOrDefault(emptyList())
        val byVerdict = tallies.associate { it.verdict to it.n }
        val screened = byVerdict.values.sum()
        binding.tallyScreened.text = screened.toString()
        binding.tallySilenced.text = (byVerdict["Silence"] ?: 0).toString()
        binding.tallyBlocked.text = (byVerdict["Block"] ?: 0).toString()
    }

    private suspend fun renderLog() {
        val rows = runCatching {
            ServiceLocator.db(this).screenLogDao().lastN(LOG_LIMIT)
        }.getOrDefault(emptyList())
        binding.logEyebrow.text =
            if (rows.isEmpty()) "SCREENING LOG" else "SCREENING LOG · LAST ${rows.size}"
        binding.logBox.removeAllViews()
        if (rows.isEmpty()) {
            addNoteRow(binding.logBox, "No calls screened yet.")
            return
        }
        rows.forEach { entry -> binding.logBox.addView(logRow(entry)) }
    }

    private fun logRow(entry: ScreenLogEntity): View {
        val row = ItemRuleRowBinding.inflate(layoutInflater, binding.logBox, false)
        val (glyph, colorRes) = when (entry.verdict) {
            "Block" -> "✗" to R.color.pxx_error
            "Silence" -> "→" to R.color.pxx_white_50
            else -> "✓" to R.color.pxx_white_90
        }
        row.rowIndex.text = glyph
        row.rowIndex.setTextColor(ContextCompat.getColor(this, colorRes))
        row.rowTitle.text = buildString {
            append(entry.e164 ?: "withheld")
            append(" · ")
            append(reasonLabel(entry))
            if (entry.answered) append(" · answered")
        }
        // entry.mode is the stored 'enforced'/'observed' token (§11); it is
        // matched on elsewhere and travels in the backup, so it is translated
        // for display instead of being restated in the database.
        row.rowDetail.text =
            "${LogRows.modeLabel(entry.mode)} · ${DateUtils.getRelativeTimeSpanString(entry.at)}"
        row.rowAction.isVisible = false
        return row.root
    }

    private fun reasonLabel(entry: ScreenLogEntity): String =
        runCatching { Reason.valueOf(entry.reason).uiLabel }.getOrDefault(entry.reason.ifEmpty { "—" })

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val COUNTDOWN_TICK_MS = 15_000L
        const val DEFAULT_BYPASS_MINUTES = 120
        const val BACKUP_FILE_NAME = "xx-dialer-backup.json"
        const val LOG_LIMIT = 200

        /** PatternRuleEntity.preset marker for the §8 neighbor-spoof rule. */
        const val PRESET_NEIGHBOR_SPOOF = "neighbor_spoof"

        const val NOTIF_IMMEDIATE = "immediate"
        const val ANSWER_TAP = "tap"
        const val ANSWER_SLIDER = "slider" // IncomingCallActivity.MODE_SLIDER literal
        const val NOTIF_DAILY = "daily"
        const val NOTIF_NEVER = "never"
    }
}
