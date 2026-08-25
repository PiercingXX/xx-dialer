package com.piercingxx.xxphone.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.piercingxx.xxphone.R
import com.piercingxx.xxphone.ServiceLocator
import com.piercingxx.xxphone.data.SettingsRepository
import com.piercingxx.xxphone.databinding.ActivityIncomingCallBinding
import kotlinx.coroutines.launch

/**
 * Full-screen incoming-call surface behind the CallStyle FSI (design §12).
 *
 * Contract consumed from XxInCallService: SHOW_INCOMING carries number /
 * context-line / CNAP / tier extras; ANSWER and DECLINE arrive as bare
 * actions (see the companion contract below — the notification's
 * answer/decline PendingIntents pass no Call handle, and a telecom Call is
 * not parcelable). Settlement goes through CallGrid's live Call objects —
 * the InCallService surface needs no permission, unlike the deprecated
 * TelecomManager.acceptRingingCall()/endCall() pair, which requires
 * ANSWER_PHONE_CALLS that this app neither declares nor requests (§4.1).
 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallBinding

    /** One settlement per surface — double-taps and queued intents must no-op. */
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lockscreen surface (§12); manifest stays exported=false.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAnswer.setOnClickListener { settle { answerCall() } }
        binding.btnDecline.setOnClickListener { settle { declineCall() } }
        listOf(binding.btnReply1, binding.btnReply2, binding.btnReply3).forEachIndexed { index, row ->
            row.setOnClickListener { replyWithText(index) }
            row.setOnLongClickListener { editCannedReply(index); true }
        }

        consume(intent)
        refreshCannedReplies()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) = when (intent?.action) {
        ACTION_ANSWER -> settle { answerCall() }
        ACTION_DECLINE -> settle { declineCall() }
        else -> renderCaller(intent)
    }

    // ---- render --------------------------------------------------------------

    private fun renderCaller(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_NUMBER_E164)
        val rawLine = intent?.getCharSequenceExtra(EXTRA_CONTEXT_LINE)?.toString().orEmpty()
        val cnapExtra = intent?.getStringExtra(EXTRA_CNAP)

        binding.tvName.text = number ?: WITHHELD_LABEL

        // Producer joins "tier · reason · carrier says: X" with SEPARATOR
        // (XxInCallService.contextLine); design §12 gives CNAP its own line,
        // so split the clause out rather than duplicating it.
        val segments = rawLine.split(SEPARATOR).filter { it.isNotBlank() }
        val cnapClause = segments.firstOrNull { it.startsWith(CNAP_PREFIX) }
            ?: cnapExtra?.let { CNAP_PREFIX + it }
        val whyLine = segments.filterNot { it.startsWith(CNAP_PREFIX) }.joinToString(SEPARATOR)

        binding.tvCnap.text = cnapClause.orEmpty()
        binding.tvCnap.isVisible = !cnapClause.isNullOrEmpty()
        binding.tvContext.text = whyLine
        binding.tvContext.isVisible = whyLine.isNotEmpty()

        applyAnswerInteraction()
        refreshCannedReplies()
    }

    /** §12: tap (two buttons, default) vs slider — decline stays a button. */
    private fun applyAnswerInteraction() {
        lifecycleScope.launch {
            val mode = runCatching {
                settings().getString(SettingsRepository.KEY_ANSWER_INTERACTION)
            }.getOrNull().orEmpty()
            if (mode == MODE_SLIDER) installSlider() else showTapButtons()
        }
    }

    private fun showTapButtons() {
        binding.sliderContainer.isVisible = false
        binding.btnAnswer.isVisible = true
    }

    private fun installSlider() {
        binding.btnAnswer.isVisible = false
        binding.sliderContainer.removeAllViews()
        binding.sliderContainer.isVisible = true
        binding.sliderContainer.addView(
            AnswerSliderView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                onAnswered = { settle { answerCall() } }
            },
        )
    }

    // ---- canned replies ------------------------------------------------------

    private fun replyRows(): List<TextView> =
        listOf(binding.btnReply1, binding.btnReply2, binding.btnReply3)

    private fun refreshCannedReplies() {
        lifecycleScope.launch {
            cannedReplies().forEachIndexed { index, text ->
                replyRows()[index].text = text
            }
        }
    }

    private suspend fun cannedReplies(): List<String> =
        CANNED_KEYS.map { key -> runCatching { settings().getString(key) }.getOrNull() ?: "" }
            .mapIndexed { index, stored -> stored.ifBlank { DEFAULT_CANNED_REPLIES[index] } }

    private fun replyKey(index: Int): String = CANNED_KEYS[index]

    /**
     * Decline + hand a prefilled draft to the default SMS app — by design
     * there is NO SEND_SMS permission to lose (R8), and this needs none.
     */
    private fun replyWithText(index: Int) {
        lifecycleScope.launch {
            val message = cannedReplies()[index]
            val number = intent?.getStringExtra(EXTRA_NUMBER_E164)
            settle {
                declineCall()
                if (number != null && message.isNotEmpty()) {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                                putExtra("sms_body", message)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun editCannedReply(index: Int) {
        lifecycleScope.launch {
            val current = cannedReplies()[index]
            val input = EditText(this@IncomingCallActivity).apply { setText(current) }
            AlertDialog.Builder(this@IncomingCallActivity)
                .setTitle("Quick reply ${index + 1}")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val value = input.text.toString().trim()
                    lifecycleScope.launch {
                        if (value.isEmpty()) return@launch
                        runCatching { settings().setString(replyKey(index), value) }
                        refreshCannedReplies()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ---- settling ------------------------------------------------------------

    private fun settle(action: () -> Unit) {
        if (settled) return
        settled = true
        action()
        finish()
    }

    private fun answerCall() {
        // Holds any active call first, then answers the ringing one — the
        // same path the call-waiting surface uses.
        runCatching { CallGrid.answerWaiting() }
    }

    private fun declineCall() {
        // disconnect() on a RINGING call rejects it.
        CallGrid.waitingCall()?.let(CallGrid::end)
    }

    private fun settings(): SettingsRepository = ServiceLocator.settings(this)

    /**
     * Horizontal pill drag ≥70% answers (§12: Google kept tap + slider after
     * publicly abandoning vertical swipe as error-prone). Built from existing
     * treatments only: hairline track, Signal-white pill.
     */
    private class AnswerSliderView(context: Context) : FrameLayout(context) {

        var onAnswered: (() -> Unit)? = null

        private val label = TextView(context).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Xx_Chip)
            text = SLIDE_LABEL
        }

        private val pill = TextView(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_answer_block)
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Xx_Body)
            setTextColor(ContextCompat.getColor(context, R.color.pxx_emphasis_fg))
            text = ARROW
            gravity = Gravity.CENTER
        }

        init {
            background = ContextCompat.getDrawable(context, R.drawable.bg_decline_block)
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val side = resources.getDimensionPixelSize(R.dimen.xx_button_height)
            addView(
                pill,
                LayoutParams(side, side, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                    marginStart = resources.getDimensionPixelSize(R.dimen.xx_hairline)
                },
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_MOVE -> {
                    pill.translationX = (event.x - pill.width / 2f).coerceIn(0f, travel())
                    label.alpha = 1f - (pill.translationX / travel()).coerceIn(0f, 1f)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val answered =
                        event.actionMasked == MotionEvent.ACTION_UP &&
                            pill.translationX >= travel() * THRESHOLD
                    if (answered) onAnswered?.invoke()
                    pill.animate().translationX(0f).setDuration(RETURN_MS).start()
                    label.animate().alpha(1f).setDuration(RETURN_MS).start()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun travel(): Float =
            (width - pill.width - 2 * resources.getDimensionPixelSize(R.dimen.xx_hairline))
                .toFloat()
                .coerceAtLeast(1f)

        private companion object {
            const val THRESHOLD = 0.70f   // §12: ≥70% drag answers
            const val RETURN_MS = 120L
            const val ARROW = "→"
            const val SLIDE_LABEL = "slide to answer"
        }
    }

    private companion object {
        const val MODE_SLIDER = "slider"

        const val WITHHELD_LABEL = "Unknown caller"

        /**
         * EXACT extra-key/action contract produced by XxInCallService
         * (declared private there — mirrored literally, do not retype loosely).
         */
        const val ACTION_ANSWER = "com.piercingxx.xxphone.action.ANSWER_CALL"
        const val ACTION_DECLINE = "com.piercingxx.xxphone.action.DECLINE_CALL"
        const val EXTRA_NUMBER_E164 = "com.piercingxx.xxphone.extra.NUMBER_E164"
        const val EXTRA_CONTEXT_LINE = "com.piercingxx.xxphone.extra.CONTEXT_LINE"
        const val EXTRA_CNAP = "com.piercingxx.xxphone.extra.CNAP"
        const val EXTRA_TIER = "com.piercingxx.xxphone.extra.TIER" // folded into CONTEXT_LINE upstream

        const val SEPARATOR = " · "
        const val CNAP_PREFIX = "carrier says: "

        /** Settings keys defined here; defaults shipped in code (res untouchable). */
        val CANNED_KEYS = listOf("canned_reply_1", "canned_reply_2", "canned_reply_3")
        val DEFAULT_CANNED_REPLIES = listOf("Can't talk now", "Call me later?", "On my way")
    }
}
