package com.piercingxx.xxdialer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.piercingxx.xxdialer.R

/**
 * Voicemail tab (design §12): the fifth tab, reachable only while the
 * Visual voicemail toggle is on (todo.md: "TabBar omits it when setting is
 * 0"). This is the placeholder shell that binds the shared tab bar so the
 * tab navigation works; the carrier-mailbox list/play/delete surface is a
 * later task. Views + viewBinding, no Compose (D7).
 */
class VoicemailActivity : AppCompatActivity() {

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