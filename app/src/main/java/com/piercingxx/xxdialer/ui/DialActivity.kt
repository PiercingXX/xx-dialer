package com.piercingxx.xxdialer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * The launcher and the dialer-role entry point. One activity serves every
 * ROLE_DIALER eligibility form (design §4.1): bare ACTION_DIAL, ACTION_DIAL
 * tel:, ACTION_VIEW tel:. Routing glue only — no layout, it forwards and
 * finishes (§15: a half-configured dialer must never look configured):
 *
 * - launcher / bare ACTION_DIAL → SetupActivity until Setup verifies both
 *   roles + channels ([SetupActivity.isFullyConfigured]), else Recents.
 * - ACTION_DIAL/ACTION_VIEW with tel: data → KeypadActivity prefilled via
 *   the standard [Intent.EXTRA_PHONE_NUMBER] extra.
 */
class DialActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route(intent)
        finish()
    }

    private fun route(intent: Intent?) {
        val number = intent?.data
            ?.takeIf { it.scheme == SCHEME_TEL }
            ?.schemeSpecificPart
        when {
            // Dial-something intents always reach the keypad, setup or not:
            // they are how other apps hand us a number mid-onboarding too.
            number != null -> startActivity(keypadIntent(number))

            SetupActivity.isFullyConfigured(this) ->
                startActivity(tabIntent(RecentsActivity::class.java))

            else -> startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    /**
     * PREFILL CONTRACT: the number rides the standard EXTRA_PHONE_NUMBER
     * extra; KeypadActivity consumes it in onCreate.
     */
    private fun keypadIntent(prefillE164: String): Intent =
        Intent(this, KeypadActivity::class.java)
            .putExtra(Intent.EXTRA_PHONE_NUMBER, prefillE164)

    private fun tabIntent(target: Class<*>): Intent =
        Intent(this, target)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private companion object {
        const val SCHEME_TEL = "tel"
    }
}
