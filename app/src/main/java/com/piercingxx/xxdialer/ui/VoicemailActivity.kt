package com.piercingxx.xxdialer.ui

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.vvm.VvmAudioPlayer
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