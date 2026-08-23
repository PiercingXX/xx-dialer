package com.piercingxx.xxphone.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import com.piercingxx.xxphone.R

/**
 * Shared bottom tab shell (design §12 IA): Recents · Keypad · People ·
 * Rules. Each tab screen appends view_tab_bar.xml and calls [bind] once
 * after setContentView; switching tabs reorders an existing instance to
 * the front instead of stacking copies, so back stays within the app.
 */
enum class Tab(val labelId: Int, val itemId: Int, val indicatorId: Int, val target: Class<*>) {
    RECENTS(R.string.tab_recents, R.id.tab_recents, R.id.tab_ind_recents, RecentsActivity::class.java),
    KEYPAD(R.string.tab_keypad, R.id.tab_keypad, R.id.tab_ind_keypad, KeypadActivity::class.java),
    PEOPLE(R.string.tab_people, R.id.tab_people, R.id.tab_ind_people, PeopleActivity::class.java),
    RULES(R.string.tab_rules, R.id.tab_rules, R.id.tab_ind_rules, RulesActivity::class.java),
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

    private fun View.isVisibleWhen(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }
}
