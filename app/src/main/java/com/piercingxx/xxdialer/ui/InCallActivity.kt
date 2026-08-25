package com.piercingxx.xxdialer.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.databinding.ActivityInCallBinding

/**
 * Active-call surface (design §12 In-call, R1): tabular duration ticker,
 * Keypad · Mute · Speaker/route · More row, overflow (Hold, Add call),
 * audio-routing bottom sheet on both the CallEndpoint and CallAudioState
 * paths, and call waiting (hold-and-answer + banner swap).
 *
 * UI is deliberately dumb: [CallGrid] owns every call reference and all
 * decisions; this activity renders snapshots and forwards commands.
 */
class InCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInCallBinding

    private val handler = Handler(Looper.getMainLooper())
    private var dtmfPanelBuilt = false

    /** §12.1: 1 s ticks on tabular Space Mono figures — never reflows. */
    private val tick = object : Runnable {
        override fun run() {
            renderDuration()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEnd.setOnClickListener { CallGrid.endActive() }
        binding.btnMute.setOnClickListener { toggleMute() }
        binding.btnSpeaker.setOnClickListener { showRouteSheet() }
        binding.btnKeypad.setOnClickListener { toggleDtmf() }
        binding.btnSwap.setOnClickListener { CallGrid.swap() }
        binding.btnAnswerWaiting.setOnClickListener { CallGrid.answerWaiting() }
        binding.btnMore.setOnClickListener { showOverflow(it) }

        CallGrid.onChange = { handler.post { renderSnapshot() } }
    }

    override fun onResume() {
        super.onResume()
        renderSnapshot()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        CallGrid.onChange = null
        super.onDestroy()
    }

    // ---- render -----------------------------------------------------------------

    private fun renderSnapshot() {
        val grid = CallGrid.snapshot()
        val primary = grid.primary
        val primaryCall = primary?.let(CallGrid::callFor)

        binding.incallKind.text = when {
            primary == null && grid.waiting == null -> NO_CALL_LABEL
            primary == null -> INCOMING_LABEL
            primary.line == Line.OUTGOING -> CALLING_LABEL
            grid.primaryHeld -> HELD_LABEL
            else -> ON_CALL_LABEL
        }

        val name = primary?.label ?: grid.waiting?.label.orEmpty()
        binding.incallName.text = name
        binding.incallName.isVisible = name.isNotEmpty()

        val contextLine = when {
            primaryCall != null -> primaryCall.details.handle?.schemeSpecificPart.orEmpty()
            primary != null && grid.primaryHeld -> STATE_HELD
            primary == null && grid.waiting != null -> WAITING_HINT
            else -> ""
        }
        binding.incallContext.text = contextLine
        binding.incallContext.isVisible = contextLine.isNotEmpty()

        renderDuration()

        binding.btnSwap.isVisible = grid.canSwap

        val waiting = grid.waiting
        binding.waitingCard.isVisible = waiting != null
        binding.waitingName.text = waiting?.label.orEmpty()

        renderControls(primary)
    }

    /** Duration anchored at ACTIVE entry; negatives impossible by clamp. */
    private fun renderDuration() {
        val anchor = CallGrid.snapshot().primary
            ?.let(CallGrid::callFor)
            ?.let(CallGrid::anchorMillis)
        if (anchor == null) {
            binding.incallDuration.isVisible = false
            return
        }
        binding.incallDuration.text =
            formatDuration((System.currentTimeMillis() - anchor) / 1000)
        binding.incallDuration.isVisible = true
    }

    private fun renderControls(primary: Cell?) {
        setArmed(binding.btnMute, CallGrid.isMuted())
        setArmed(binding.btnSpeaker, CallGrid.isRoutedAwayFromEar())
        val liveCall = primary != null
        binding.btnKeypad.isEnabled = liveCall
        binding.btnMute.isEnabled = liveCall &&
            (primary.line == Line.ACTIVE || primary.line == Line.HELD)
        binding.btnSpeaker.isEnabled = liveCall
        binding.btnMore.isEnabled = liveCall
        binding.btnEnd.isEnabled = liveCall || CallGrid.snapshot().waiting != null
    }

    /** Emphasis-invert marks an engaged control (§12.1: inversion, not hue). */
    private fun setArmed(view: TextView, armed: Boolean) {
        view.background = if (armed) {
            ContextCompat.getDrawable(this, R.drawable.bg_emphasis_invert)
        } else {
            null
        }
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (armed) R.color.pxx_emphasis_fg else R.color.pxx_white_90,
            ),
        )
    }

    private fun toggleMute() {
        val next = !CallGrid.isMuted()
        CallGrid.setMuted(next)
        setArmed(binding.btnMute, next) // service echo re-renders shortly
    }

    // ---- overflow: Hold / Add call (§12) ------------------------------------------

    private fun showOverflow(anchor: View) {
        val grid = CallGrid.snapshot()
        val menu = PopupMenu(this, anchor)
        menu.menu.add(if (grid.primaryHeld) RESUME_ITEM else HOLD_ITEM)
        menu.menu.add(ADD_CALL_ITEM)
        menu.setOnMenuItemClickListener { item ->
            val call = grid.primary?.let(CallGrid::callFor)
            when (item.title.toString()) {
                HOLD_ITEM -> call?.let(CallGrid::hold)
                RESUME_ITEM -> call?.let(CallGrid::unhold)
                ADD_CALL_ITEM -> startActivity(
                    Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ) // the dialer handles it — ours (§12 Add call)
            }
            true
        }
        menu.show()
    }

    // ---- audio routing bottom sheet (both paths, §12) ------------------------------

    private fun showRouteSheet() {
        val routes = CallGrid.routes()
        if (routes.isEmpty()) return
        val dialog = BottomSheetDialog(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.pxx_ink_raised))
            val pad = resources.getDimensionPixelSize(R.dimen.xx_gutter)
            setPadding(pad, pad, pad, pad)
        }
        routes.forEachIndexed { index, route ->
            list.addView(
                TextView(this).apply {
                    text = route.label
                    TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Xx_Body)
                    minHeight = resources.getDimensionPixelSize(R.dimen.xx_row_height)
                    gravity = Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(context, R.drawable.bg_list_row)
                    setOnClickListener {
                        CallGrid.requestRoute(route)
                        dialog.dismiss()
                    }
                },
            )
            if (index < routes.lastIndex) list.addView(divider())
        }
        dialog.setContentView(list)
        dialog.show()
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.xx_hairline),
        )
        background = ContextCompat.getDrawable(context, R.drawable.divider_hairline)
    }

    // ---- DTMF pad ------------------------------------------------------------------

    /** Digit grid in the KeypadActivity treatment; tones ride the active call. */
    private fun toggleDtmf() {
        if (!dtmfPanelBuilt) {
            buildDtmfPad()
            dtmfPanelBuilt = true
        }
        val showing = binding.incallDtmfScroll.isVisible
        binding.incallDtmfScroll.isVisible = !showing
        setArmed(binding.btnKeypad, !showing)
    }

    private fun buildDtmfPad() {
        val keySize = resources.getDimensionPixelSize(R.dimen.xx_key_size)
        val gap = resources.getDimensionPixelSize(R.dimen.xx_key_gap)
        val digits = arrayOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#')
        digits.forEach { digit ->
            val key = TextView(this).apply {
                text = digit.toString()
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Xx_KeypadDigit)
                includeFontPadding = false
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.bg_keypad_digit)
                isClickable = true
                isFocusable = true
            }
            key.setOnClickListener {
                haptic(key)
                if (CallGrid.dtmfStart(digit)) {
                    handler.postDelayed({ CallGrid.dtmfStop() }, DTMF_TONE_MS)
                }
            }
            binding.incallDtmfGrid.addView(
                key,
                GridLayout.LayoutParams().apply {
                    width = keySize
                    height = keySize
                    setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                },
            )
        }
    }

    private fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val DTMF_TONE_MS = 150L

        const val NO_CALL_LABEL = "No active call"
        const val ON_CALL_LABEL = "On call"
        const val CALLING_LABEL = "Calling"
        const val HELD_LABEL = "On hold"
        const val INCOMING_LABEL = "Incoming"
        const val WAITING_HINT = "Second call waiting"
        const val STATE_HELD = "held"

        const val HOLD_ITEM = "Hold"
        const val RESUME_ITEM = "Resume"
        const val ADD_CALL_ITEM = "Add call"
    }
}
