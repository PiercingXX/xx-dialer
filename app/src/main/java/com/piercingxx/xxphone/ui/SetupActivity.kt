package com.piercingxx.xxphone.ui

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.piercingxx.xxphone.R
import com.piercingxx.xxphone.ServiceLocator
import com.piercingxx.xxphone.core.Mode
import com.piercingxx.xxphone.data.SettingsRepository
import com.piercingxx.xxphone.databinding.ActivitySetupBinding
import com.piercingxx.xxphone.ring.ChannelIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * First-run flow (design §12 Setup): dialer + screening roles with the
 * Restricted-Settings walk-through (§4.1), channel creation and reconcile
 * warnings surfaced verbatim (§10, §15), contacts access (Contact Scopes
 * aware), full-screen-intent grant, observe-mode handoff.
 *
 * Every step re-audits onResume and shows its ACTUAL state — a half-configured
 * dialer that looks configured is the Fossify silent-ring bug (§4.1), designed
 * against explicitly here. RESULT_OK is never trusted; roles are probed after
 * every request (refusal signature mirrors probe/MainActivity).
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    /** Role awaiting post-return verification — null when no request in flight. */
    private var pendingRole: String? = null

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            evaluateRoleResult(result.resultCode)
        }

    private val contactsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            auditStatic()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnDialer.setOnClickListener { requestRole(RoleManager.ROLE_DIALER) }
        binding.btnAppInfoDialer.setOnClickListener { openAppInfo() }
        binding.btnScreening.setOnClickListener { requestRole(RoleManager.ROLE_CALL_SCREENING) }
        binding.btnAppInfoScreening.setOnClickListener { openAppInfo() }
        binding.btnContacts.setOnClickListener { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }
        binding.btnFsi.setOnClickListener { openFullScreenIntentGrant() }
        binding.btnDone.setOnClickListener {
            seedObserveWeekIfAbsent()
            startActivity(Intent(this, RecentsActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        auditStatic()   // synchronous state: roles, contacts, FSI
        refreshAsync()  // suspend state: channels, observe-week boundary
    }

    // ---- step audits -------------------------------------------------------

    /** Roles / contacts / FSI read straight from the system, never cached. */
    private fun auditStatic() {
        val roles = getSystemService(RoleManager::class.java)
        val dialerHeld = roles?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        val screeningHeld = roles?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true

        renderStep(
            glyph = binding.glyphDialer,
            status = binding.statusDialer,
            held = dialerHeld,
            okText = "role held and verified",
            warnText = "not the default phone app — calls placed through us need this (§4.1)",
        )
        // A fresh audit clears any stale walk-through once the role verifies.
        if (dialerHeld) hideRestrictedWalkthrough(dialer = true)
        binding.btnDialer.isVisible = !dialerHeld

        renderStep(
            glyph = binding.glyphScreening,
            status = binding.statusScreening,
            held = screeningHeld,
            okText = "screening role held",
            warnText = "another screener wins — our screening silently never runs (§4.1)",
        )
        if (screeningHeld) {
            binding.conflictScreening.visibility = View.GONE
            hideRestrictedWalkthrough(dialer = false)
        } else {
            nameScreeningConflict(roles)
        }
        binding.btnScreening.isVisible = !screeningHeld

        val contactsGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        renderStep(
            glyph = binding.glyphContacts,
            status = binding.statusContacts,
            held = contactsGranted,
            okText = "granted",
            warnText = "needed to star and save callers",
        )
        binding.btnContacts.isVisible = !contactsGranted

        val fsiAllowed = NotificationManagerCompat.from(this).canUseFullScreenIntent()
        renderStep(
            glyph = binding.glyphFsi,
            status = binding.statusFsi,
            held = fsiAllowed,
            okText = "allowed",
            warnText = "heads-up only — calls still ring, just not over the lock screen (§15)",
        )
        binding.btnFsi.isVisible = !fsiAllowed
    }

    /**
     * §4.1 conflict naming: when another package holds ROLE_CALL_SCREENING,
     * show its label and offer the request anyway (claiming takes over).
     */
    private fun nameScreeningConflict(roles: RoleManager?) {
        val holders = roleHolders(roles, RoleManager.ROLE_CALL_SCREENING)
            .split(HOLDER_SEPARATOR)
            .filter { it.isNotEmpty() && it != packageName && !it.startsWith(UNREADABLE_PREFIX) }
        if (holders.isEmpty()) return
        val labels = holders.mapNotNull(::labelFor).ifEmpty { holders }
        binding.conflictScreening.text =
            "currently held by ${labels.joinToString(" · ")} — claiming it will take over"
        binding.conflictScreening.visibility = View.VISIBLE
    }

    /** Channel creation + reconcile warnings (§15), listed verbatim. */
    private fun refreshChannels() {
        lifecycleScope.launch {
            val registry = ServiceLocator.channelRegistry(this@SetupActivity)
            runCatching { registry.ensureAll() }
            val warnings = runCatching { registry.reconcileAtCall() }.getOrDefault(emptyList())
            binding.warningsChannels.removeAllViews()
            warnings.forEach { warning ->
                binding.warningsChannels.addView(
                    TextView(this@SetupActivity).apply {
                        text = warning
                        setTextAppearance(R.style.TextAppearance_Xx_BodySmall)
                    },
                )
            }
            binding.glyphChannels.text = if (warnings.isEmpty()) GLYPH_OK else GLYPH_WARN
            binding.glyphChannels.setTextColor(glyphColor(ok = warnings.isEmpty()))
            binding.statusChannels.text =
                if (warnings.isEmpty()) "four channels created · healthy" else "channel warnings:"
        }
    }

    /** Observe-week boundary (§15): enforcement offered after it, never flipped. */
    private fun refreshObserve() {
        lifecycleScope.launch {
            val settings = ServiceLocator.settings(this@SetupActivity)
            val mode = settings.enforcementMode()
            // The glyph shows its actual state like every other step (§12):
            // ✓ once a week boundary exists or enforcement was flipped.
            val seeded = mode == Mode.ENFORCING || settings.observeWeekEndMillis() != null
            binding.glyphObserve.text = if (seeded) GLYPH_OK else GLYPH_WARN
            binding.glyphObserve.setTextColor(glyphColor(ok = seeded))
            binding.dateObserve.text = when {
                mode == Mode.ENFORCING -> "enforcement active — flipped by you"
                else -> settings.observeWeekEndMillis()
                    ?.let(SettingsRepository::epochToDateTime)
                    ?.format(DATE_FORMAT)
                    ?.let { "observe week ends $it" }
                    ?: "observe week boundary unset"
            }
        }
    }

    private fun refreshAsync() {
        refreshChannels()
        refreshObserve()
    }

    // ---- role requests (§4.1, probe refusal-signature pattern) -------------

    /**
     * §12/D13: the observe-week clock starts HERE — Setup's final step
     * completing — not at process launch, so a user stalling mid-setup never
     * burns the week. Put-if-absent: installs that already carry a boundary
     * keep it untouched (back-compat), and re-entering Setup never restarts
     * the clock. Own scope because finish() tears lifecycleScope down before
     * a suspend write could land.
     */
    private fun seedObserveWeekIfAbsent() {
        val appContext = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val settings = ServiceLocator.settings(appContext)
                if (settings.getString(SettingsRepository.KEY_OBSERVE_WEEK_END) == null) {
                    settings.setString(
                        SettingsRepository.KEY_OBSERVE_WEEK_END,
                        SettingsRepository.observeWeekEndSeed(System.currentTimeMillis()),
                    )
                }
            }
        }
    }

    private fun requestRole(role: String) {
        val roles = getSystemService(RoleManager::class.java)
        if (roles == null || !roles.isRoleAvailable(role)) return
        if (roles.isRoleHeld(role)) return
        pendingRole = role
        roleLauncher.launch(roles.createRequestRoleIntent(role))
    }

    /**
     * Verification AFTER return: RESULT_OK alone proves nothing. OK-but-not-held
     * is exactly the Restricted-Settings refusal signature (§4.1) → walk-through.
     */
    private fun evaluateRoleResult(resultCode: Int) {
        val role = pendingRole ?: return
        pendingRole = null
        val held = getSystemService(RoleManager::class.java)?.isRoleHeld(role) == true
        if (!held) showRestrictedWalkthrough(role == RoleManager.ROLE_DIALER)
        auditStatic()
    }

    private fun showRestrictedWalkthrough(dialer: Boolean) {
        val walkthrough = if (dialer) binding.walkthroughDialer else binding.walkthroughScreening
        val appInfo = if (dialer) binding.btnAppInfoDialer else binding.btnAppInfoScreening
        walkthrough.visibility = View.VISIBLE
        appInfo.visibility = View.VISIBLE
        (if (dialer) binding.statusDialer else binding.statusScreening).text =
            "request refused — restricted-settings gate (§4.1)"
    }

    private fun hideRestrictedWalkthrough(dialer: Boolean) {
        if (dialer) {
            binding.walkthroughDialer.visibility = View.GONE
            binding.btnAppInfoDialer.visibility = View.GONE
        } else {
            binding.walkthroughScreening.visibility = View.GONE
            binding.btnAppInfoScreening.visibility = View.GONE
        }
    }

    // ---- deep links ----------------------------------------------------------

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null)),
        )
    }

    /** API 31+ grant screen for USE_FULL_SCREEN_INTENT (minSdk 31). */
    private fun openFullScreenIntentGrant() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.fromParts("package", packageName, null)),
            )
        }
    }

    // ---- helpers -------------------------------------------------------------

    private fun renderStep(glyph: TextView, status: TextView, held: Boolean, okText: String, warnText: String) {
        glyph.text = if (held) GLYPH_OK else GLYPH_WARN
        glyph.setTextColor(glyphColor(held))
        status.text = if (held) okText else warnText
    }

    /** §12.1 glyph-first states: ✓ at ok, ⚠ may use warn — attention only. */
    private fun glyphColor(ok: Boolean): Int =
        ContextCompat.getColor(this, if (ok) R.color.pxx_ok else R.color.pxx_warn)

    private fun labelFor(pkg: String): String? =
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()

    /**
     * getRoleHolders is not public SDK surface; reflect it exactly like
     * probe/MainActivity.roleHolders — "none"/"unreadable:*" on failure.
     */
    private fun roleHolders(roles: RoleManager?, role: String): String {
        if (roles == null) return UNREADABLE_PREFIX + "no_role_manager"
        return runCatching {
            val method = roles.javaClass.getMethod("getRoleHolders", String::class.java)
            @Suppress("UNCHECKED_CAST")
            (method.invoke(roles, role) as? List<String>)?.joinToString(HOLDER_SEPARATOR) ?: "none"
        }.getOrElse { UNREADABLE_PREFIX + it.javaClass.simpleName }
    }

    companion object {

        private const val GLYPH_OK = "✓"      // §12.1 verdict glyphs
        private const val GLYPH_WARN = "⚠"
        private const val HOLDER_SEPARATOR = "|"
        private const val UNREADABLE_PREFIX = "unreadable:"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d")

        /**
         * Audit for other screens (§15 routing: a role lost reopens Setup
         * first). True ONLY when both roles verify held AND all four §10
         * channel purposes resolve live under some versioned id — read-only,
         * never mints, so callers can trust it without side effects.
         */
        fun isFullyConfigured(context: Context): Boolean {
            val roles = context.getSystemService(RoleManager::class.java)
            val rolesHeld = roles?.isRoleHeld(RoleManager.ROLE_DIALER) == true &&
                roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            if (!rolesHeld) return false
            // Deliberately NOT gated on areNotificationsEnabled(): no Setup
            // step surfaces or can grant it, so a false reading turned the
            // §15 routing into a trap — "Done — open recents" bounced every
            // tab screen straight back here with zero feedback. Notification
            // health belongs to §15 warnings, never to routing.
            val channels = context.getSystemService(NotificationManager::class.java) ?: return false
            return CHANNEL_PURPOSES.all { purpose ->
                channels.notificationChannels.any {
                    it.id.startsWith(purpose + ChannelIds.VERSION_SEPARATOR)
                }
            }
        }

        private val CHANNEL_PURPOSES = listOf(
            ChannelIds.PURPOSE_RING_DEFAULT,
            ChannelIds.PURPOSE_RING_UNKNOWN,
            ChannelIds.PURPOSE_RING_SILENT,
            ChannelIds.PURPOSE_ONGOING,
        )
    }
}
