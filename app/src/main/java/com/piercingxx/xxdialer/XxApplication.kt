package com.piercingxx.xxdialer

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import com.piercingxx.xxdialer.data.SettingsRepository
import com.piercingxx.xxdialer.ring.SilencedNotifier
import com.piercingxx.xxdialer.theme.ThemeGroundApplier
import com.piercingxx.xxdialer.ui.RulesActivity
import com.piercingxx.xxdialer.util.E164
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Boot glue only (design §12 Setup, §17 WS1): seed the `setting` defaults,
 * let ring/ create its channels, and arm the mirror engine — initial bulk
 * read, contacts observer, and the on-foreground sweep (§9, WS4). Nothing
 * here may crash boot — every step is fire-and-forget and reconciles at
 * point of use (D3: no alarms, no re-arm).
 */
class XxApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Foreground-transition detector for the §9 sweep (0 → 1 started activities). */
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        // Region for national-format normalization (§8): the SIM knows better
        // than a hardcoded default; absence keeps the built-in fallback.
        runCatching {
            getSystemService(TelephonyManager::class.java)?.simCountryIso
                ?.takeIf { it.isNotBlank() }
                ?.let { E164.defaultRegion = it.uppercase() }
        }
        applicationScope.launch {
            runCatching {
                ServiceLocator.settings(this@XxApplication)
                    .ensureDefaults(SettingsRepository.designDefaults(System.currentTimeMillis()))
            }
            runCatching {
                ServiceLocator.channelRegistry(this@XxApplication).ensureAll()
            }
            // Mirror boot (§9): observer first so no change slips between the
            // bulk read and registration; a denied read keeps warm data (§15).
            runCatching {
                val mirror = ServiceLocator.contactMirror(this@XxApplication)
                mirror.registerObserver(this@XxApplication)
                mirror.refreshAll()
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacksAdapter() {
            override fun onActivityResumed(activity: Activity) {
                // Family theme sync (BRAND-GUIDE §3.3): repaint the ground
                // from the persisted launcher broadcast on every resume, so a
                // theme change landing while backgrounded shows on return.
                // No-op until a broadcast has ever landed.
                runCatching { ThemeGroundApplier.apply(activity) }
            }

            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) {
                    applicationScope.launch {
                        runCatching {
                            ServiceLocator.contactMirror(this@XxApplication).sweepOnForeground()
                        }
                    }
                    // §12 three-state policy: the daily silenced summary has no
                    // alarm (D3); app foreground is one of its two flush points.
                    SilencedNotifier.flushDailyDigestIfDue()
                    // §15: enforcement is OFFERED — banner on Rules plus this
                    // one-time notification when the observe week lapses.
                    applicationScope.launch { runCatching { maybeOfferEnforcement() } }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }
        })
    }

    /**
     * §15 observe-week-end: enforcement is offered, never flipped silently.
     * One notification, once — the Rules banner keeps offering after that.
     */
    private suspend fun maybeOfferEnforcement() {
        val settings = ServiceLocator.settings(this)
        if (!settings.shouldOfferEnforcement(System.currentTimeMillis())) return
        if (settings.getString(KEY_OFFER_NOTIFIED) == "1") return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(OFFER_CHANNEL, "Enforcement offer", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            this, RC_OFFER,
            Intent(this, RulesActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val card = Notification.Builder(this, OFFER_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Observation week complete")
            .setContentText("Every verdict was logged, nothing was silenced. Ready to enforce?")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIF_OFFER, card)
            settings.setString(KEY_OFFER_NOTIFIED, "1")
        } catch (se: SecurityException) {
            Log.w(TAG, "offer notification refused", se) // banner on Rules still offers
        }
    }

    private companion object {
        const val TAG = "XxApplication"
        const val OFFER_CHANNEL = "enforce_offer_v1"
        const val KEY_OFFER_NOTIFIED = "enforce_offer_notified"
        const val NOTIF_OFFER = 1300
        const val RC_OFFER = 301
    }

    /** Adapter so the sweep hook above only spells the two callbacks it uses. */
    private abstract class ActivityLifecycleCallbacksAdapter : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
