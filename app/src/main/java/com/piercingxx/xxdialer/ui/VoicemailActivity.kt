package com.piercingxx.xxdialer.ui

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.vvm.VvmAudioPlayer
import com.piercingxx.xxdialer.vvm.VvmDetailPlayer
import com.piercingxx.xxdialer.vvm.VvmListQuery
import com.piercingxx.xxdialer.vvm.VvmListRow

/**
 * Voicemail tab (design §12): the fifth tab, reachable only while the
 * Visual voicemail toggle is on (todo.md: "TabBar omits it when setting is
 * 0"). This is the placeholder shell that binds the shared tab bar so the
 * tab navigation works; the carrier-mailbox list/play/delete surface is a
 * later task. Views + viewBinding, no Compose (D7).
 */
class VoicemailActivity : AppCompatActivity() {

    private val audioPlayer: VvmAudioPlayer by lazy { VvmAudioPlayer(this) }

    private val listQuery: VvmListQuery by lazy { VvmListQuery(this) }

    /**
     * T3: the detail screen's action seams — play/pause, speaker, call back, and
     * delete. [VvmDetailPlayer] owns its own self-contained MediaPlayer and drives
     * playback directly from the voicemail's content URI (V6 fetch-then-play is
     * not landed). The detail UI (V7) calls these when the user taps the controls.
     */
    private val detailPlayer: VvmDetailPlayer by lazy { VvmDetailPlayer(this) }

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
     * self-contained MediaPlayer. The detail UI (V7) calls this on the play/pause
     * control.
     */
    fun toggleDetailPlay(uri: Uri): Boolean = detailPlayer.togglePlay(uri)

    /**
     * T3: routes detail playback through the speakerphone ([on] true) or the
     * earpiece ([on] false).
     */
    fun setDetailSpeakerphone(on: Boolean): Boolean = detailPlayer.setSpeakerphone(on)

    /**
     * T3: dials the caller's [number] back from the detail screen.
     */
    fun callBackVoicemail(number: String): Boolean = detailPlayer.callBack(number)

    /**
     * T3: deletes the voicemail row at [uri] from the detail screen.
     */
    fun deleteVoicemail(uri: Uri): Boolean = detailPlayer.delete(uri)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voicemail)
        TabBar.bind(this, Tab.VOICEMAIL)
    }

    override fun onStart() {
        super.onStart()
        TabBar.onTabScreenStart(this, Tab.VOICEMAIL)
    }
}