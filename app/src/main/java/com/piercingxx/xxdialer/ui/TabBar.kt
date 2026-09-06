package com.piercingxx.xxdialer.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.ServiceLocator
import kotlinx.coroutines.launch

/**
 * Shared bottom tab shell (design §12 IA): Recents · Keypad · People ·
 * Rules · Voicemail. Each tab screen appends view_tab_bar.xml and calls
 * [bind] once after setContentView; switching tabs reorders an existing
 * instance to the front instead of stacking copies, so back stays within
 * the app.
 */
enum class Tab(val labelId: Int, val itemId: Int, val indicatorId: Int, val target: Class<*>) {
    RECENTS(R.string.tab_recents, R.id.tab_recents, R.id.tab_ind_recents, RecentsActivity::class.java),
    KEYPAD(R.string.tab_keypad, R.id.tab_keypad, R.id.tab_ind_keypad, KeypadActivity::class.java),
    PEOPLE(R.string.tab_people, R.id.tab_people, R.id.tab_ind_people, PeopleActivity::class.java),
    RULES(R.string.tab_rules, R.id.tab_rules, R.id.tab_ind_rules, RulesActivity::class.java),
    VOICEMAIL(R.string.tab_voicemail, R.id.tab_voicemail, R.id.tab_ind_voicemail, VoicemailActivity::class.java),
}

object TabBar {

    /** Mark the current tab and wire clicks to reorder-to-front launches. */
    fun bind(activity: Activity, current: Tab) {
        val entries = Tab.entries
        entries.filterNot { it == current }.forEach { tab ->
            activity.findViewById<View>(tab.itemId)?.setOnClickListener { source ->
                source.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                activity.startActivity(
                    Intent(activity, tab.target)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
        entries.forEach { tab ->
            activity.findViewById<View>(tab.indicatorId)?.isVisibleWhen(tab == current)
            activity.findViewById<View>(tab.itemId)?.isSelected = tab == current
        }
    }

    /**
     * Pure visibility decision (§12). A tab is visible when it is the current
     * one (a screen the user is standing on never loses its own marker), or
     * Rules (always reachable so the setting can undo itself), or it is not in
     * the hidden set — and Voicemail additionally requires the opt-in toggle,
     * because while Visual voicemail is off the tab is ABSENT entirely, not
     * hidden (todo.md: "TabBar omits it when setting is 0"). Pure so it is
     * unit-testable without a View tree.
     */
    fun visibleTabs(
        current: Tab,
        hiddenNames: Set<String>,
        vvmEnabled: Boolean,
    ): Set<Tab> = Tab.entries.filter { tab ->
        tab == current ||
            tab == Tab.RULES ||
            (tab != Tab.VOICEMAIL || vvmEnabled) && tab.name.lowercase() !in hiddenNames
    }.toSet()

    /**
     * Persistable hidden-tab names from the Rules "Hide tabs" chips. Voicemail
     * is included — omitting it left Hide Voicemail as a no-op across process
     * death (todo.md V3).
     */
    fun hiddenNames(
        hideRecents: Boolean,
        hideKeypad: Boolean,
        hidePeople: Boolean,
        hideVoicemail: Boolean,
    ): Set<String> = buildSet {
        if (hideRecents) add(Tab.RECENTS.name.lowercase())
        if (hideKeypad) add(Tab.KEYPAD.name.lowercase())
        if (hidePeople) add(Tab.PEOPLE.name.lowercase())
        if (hideVoicemail) add(Tab.VOICEMAIL.name.lowercase())
    }

    /**
     * Tab-hiding setting (§12). The current tab always survives — a screen
     * the user is standing on never loses its own marker — and Rules is
     * unhideable so the setting can always be reached to undo itself. Hidden
     * tabs GONE their hairline indicator with the label (no ghost slot).
     */
    fun applyHidden(activity: Activity, current: Tab, hiddenNames: Set<String>, vvmEnabled: Boolean) {
        val visible = visibleTabs(current, hiddenNames, vvmEnabled)
        Tab.entries.forEach { tab ->
            val shown = tab in visible
            activity.findViewById<View>(tab.itemId)?.visibility =
                if (shown) View.VISIBLE else View.GONE
            // Hairline must GONE with the label. INVISIBLE still occupies a
            // layout_weight slot, which is the blank fifth tab when VVM is off.
            activity.findViewById<View>(tab.indicatorId)?.visibility = when {
                !shown -> View.GONE
                tab == current -> View.VISIBLE
                else -> View.INVISIBLE
            }
        }
    }

    private fun View.isVisibleWhen(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Call from every tab screen's onStart. Two duties: §15 routing — a lost
     * role or dead channel reopens Setup first, so no screen ever renders
     * fully-functional-looking over a disarmed policy — and re-applying the
     * hidden-tab set, which may have changed on the Rules screen since this
     * instance was last in front.
     */
    fun onTabScreenStart(activity: AppCompatActivity, current: Tab) {
        if (!SetupActivity.isFullyConfigured(activity)) {
            activity.startActivity(Intent(activity, SetupActivity::class.java))
            return
        }
        activity.lifecycleScope.launch {
            val settings = ServiceLocator.settings(activity)
            val hidden = runCatching { settings.hiddenTabs() }.getOrDefault(emptySet())
            val vvmEnabled = runCatching { settings.visualVoicemailEnabled() }.getOrDefault(false)
            applyHidden(activity, current, hidden, vvmEnabled)
        }
    }
}
