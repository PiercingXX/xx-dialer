package com.piercingxx.xxdialer.ui

import android.net.Uri
import android.os.Bundle
import android.provider.VoicemailContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.databinding.ActivityVoicemailBinding
import com.piercingxx.xxdialer.databinding.ItemVoicemailRowBinding
import com.piercingxx.xxdialer.vvm.VvmAudioPlayer
import com.piercingxx.xxdialer.vvm.VvmDetailPlayer
import com.piercingxx.xxdialer.vvm.VvmListQuery
import com.piercingxx.xxdialer.vvm.VvmListRow
import com.piercingxx.xxdialer.vvm.VvmListState

/**
 * Voicemail tab (design §12, todo.md V7): the fifth tab, reachable only while
 * the Visual voicemail toggle is on (todo.md: "TabBar omits it when setting is
 * 0"). This is the list/detail surface: it renders every honest [VvmListState]
 * — the list is the only state that shows rows, every other state explains
 * itself instead of lying — and wires each row's detail controls (play/pause,
 * speaker, call back, delete) to the [VvmDetailPlayer] seams. Views +
 * viewBinding, no Compose (D7).
 */
class VoicemailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoicemailBinding
    private var adapter: VoicemailAdapter? = null

    private val audioPlayer: VvmAudioPlayer by lazy { VvmAudioPlayer(this) }

    private val listQuery: VvmListQuery by lazy { VvmListQuery(this) }

    /**
     * T3: the detail screen's action seams — play/pause, speaker, call back, and
     * delete. [VvmDetailPlayer] owns its own self-contained MediaPlayer and drives
     * playback directly from the voicemail's content URI (V6 fetch-then-play is
     * not landed). The list rows (V7) call these when the user taps the controls.
     * Injectable so the wiring is JVM-testable without a live player.
     */
    private var detailPlayer: VvmDetailPlayer? = null
    private fun detail(): VvmDetailPlayer = detailPlayer ?: VvmDetailPlayer(this).also { detailPlayer = it }

    /** Test seam: substitutes a [VvmDetailPlayer] so the detail wiring is observable. */
    internal fun attachDetailPlayer(player: VvmDetailPlayer) {
        detailPlayer = player
    }

    /**
     * T2: reads the mailbox rows from VoicemailContract and resolves each caller
     * name (via the live PhoneLookup path). The list/detail UI (V7) calls this
     * to render the voicemail list; [VvmListState] decides which honest state the
     * screen shows from the query result.
     */
    fun queryList(): List<VvmListRow> = listQuery.readRows()

    /**
     * T3: plays the voicemail at [uri], fetching its audio first when the row
     * carries no content (fetch-then-play). The list/detail UI (V7) calls this
     * when the user taps a voicemail; the player owns the fetch-vs-play
     * decision and the MediaPlayer lifecycle.
     */
    fun playVoicemail(uri: Uri): Boolean = audioPlayer.play(uri)

    /**
     * T3: toggles play/pause for the voicemail at [uri] via the detail player's
     * self-contained MediaPlayer. The list rows (V7) call this on the play/pause
     * control.
     */
    fun toggleDetailPlay(uri: Uri): Boolean = detail().togglePlay(uri)

    /**
     * T3: routes detail playback through the speakerphone ([on] true) or the
     * earpiece ([on] false).
     */
    fun setDetailSpeakerphone(on: Boolean): Boolean = detail().setSpeakerphone(on)

    /**
     * T3: dials the caller's [number] back from the detail screen.
     */
    fun callBackVoicemail(number: String): Boolean = detail().callBack(number)

    /**
     * T3: deletes the voicemail row at [uri] from the detail screen.
     */
    fun deleteVoicemail(uri: Uri): Boolean = detail().delete(uri)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoicemailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TabBar.bind(this, Tab.VOICEMAIL)

        adapter = VoicemailAdapter(
            onPlayPause = ::toggleDetailPlay,
            onSpeaker = ::setDetailSpeakerphone,
            onCallBack = ::callBackVoicemail,
            onDelete = ::deleteVoicemail,
        )
        binding.vvmList.layoutManager = LinearLayoutManager(this)
        binding.vvmList.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        TabBar.onTabScreenStart(this, Tab.VOICEMAIL)
    }

    /**
     * Renders one honest [VvmListState] (todo.md "Tab states"). The list is the
     * only state that shows rows; every other state shows its explanatory
     * message instead of a fake-empty list. This is the single seam that maps
     * the state machine to the screen, so the honest-state rendering is
     * JVM-testable without a live mailbox.
     */
    fun renderState(state: VvmListState) {
        val messageRes: Int? = when (state) {
            is VvmListState.NoCarrierConfig -> R.string.vvm_state_no_config
            is VvmListState.Activating -> R.string.vvm_state_activating
            is VvmListState.NetworkRevoked -> R.string.vvm_state_network_revoked
            is VvmListState.ImapError -> R.string.vvm_state_imap_error
            is VvmListState.Empty -> R.string.vvm_state_empty
            is VvmListState.List -> null
        }
        binding.vvmEmptyState.apply {
            if (messageRes != null) {
                setText(messageRes)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        binding.vvmList.visibility = if (state is VvmListState.List) View.VISIBLE else View.GONE
    }

    /**
     * Renders the mailbox rows: an empty result is the honest [VvmListState.Empty]
     * (never a fake list), a non-empty one submits the rows to the list and shows
     * it. The list/detail UI (V7) calls this after [queryList].
     */
    fun renderRows(rows: List<VvmListRow>) {
        if (rows.isEmpty()) {
            renderState(VvmListState.Empty)
            return
        }
        adapter?.submit(rows)
        renderState(VvmListState.List(rows.size))
    }

    /**
     * Binds one voicemail row and wires its detail controls (play/pause, speaker,
     * call back, delete) to the activity's detail seams. The row's content URI is
     * derived from its id so the detail actions target the right voicemail.
     */
    private class VoicemailAdapter(
        private val onPlayPause: (Uri) -> Boolean,
        private val onSpeaker: (Boolean) -> Boolean,
        private val onCallBack: (String) -> Boolean,
        private val onDelete: (Uri) -> Boolean,
    ) : RecyclerView.Adapter<VoicemailAdapter.Holder>() {

        private val rows = mutableListOf<VvmListRow>()

        fun submit(list: List<VvmListRow>) {
            rows.clear()
            rows.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemVoicemailRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

        inner class Holder(private val binding: ItemVoicemailRowBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(row: VvmListRow) {
                val context = binding.root.context
                binding.vvmRowCaller.text = row.callerName
                    ?: context.getString(R.string.vvm_unknown_caller)
                binding.vvmRowTime.text = row.timestampMillis.takeIf { it > 0L }
                    ?.let { android.text.format.DateFormat.getTimeFormat(context).format(it) }
                    .orEmpty()
                binding.vvmRowDuration.text = context.getString(
                    R.string.vvm_duration_format,
                    row.durationSeconds,
                )
                val uri = VoicemailContract.Voicemails.CONTENT_URI.buildUpon()
                    .appendPath(row.id.toString())
                    .build()
                binding.vvmRowPlay.setOnClickListener { onPlayPause(uri) }
                binding.vvmRowSpeaker.setOnClickListener { onSpeaker(true) }
                binding.vvmRowCallBack.setOnClickListener {
                    row.number?.takeIf { it.isNotBlank() }?.let(onCallBack)
                }
                binding.vvmRowDelete.setOnClickListener { onDelete(uri) }
            }
        }
    }
}