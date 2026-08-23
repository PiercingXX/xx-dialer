package com.piercingxx.xxphone

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piercingxx.xxphone.ring.ChannelIds
import com.piercingxx.xxphone.ui.InCallActivity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * design.md §16, instrumented bullet "channel ring end-to-end with each tier" —
 * the V2 [VERIFY] leg of PROBE.md §2 · CHANNEL RING: does a CallStyle
 * notification on the `ring_unknown_v1` channel ring, and does its sound stay
 * exactly what §10 minted (`android.resource://<pkg>/raw/xx_unknown`)?
 *
 * WS gate: WS0/V2 → WS6 (ringer) — "If channel-ringing fails, D2 falls back to
 * self-played USAGE_NOTIFICATION_RINGTONE audio" (§17).
 *
 * What runs here on-device: channel creation through [ChannelRegistry.ensureAll]
 * (the production path, not a hand-built channel), then NotificationManager
 * ground-truth assertions. What CANNOT be asserted from code: audibility — that
 * stays the TODO-on-device manual legs below.
 */
@Ignore("requires caiman — see PROBE.md")
@RunWith(AndroidJUnit4::class)
class ChannelRingEndToEndTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val unknownChannelId: String =
        ChannelIds.versioned(ChannelIds.PURPOSE_RING_UNKNOWN, ChannelIds.FIRST_VERSION)

    /** §10: unknown-caller tone — the shipped raw resource, versioned channel id. */
    private val expectedUnknownSound: String =
        "android.resource://${context.packageName}/raw/xx_unknown"

    /** Channel exists after the real ensureAll() path, at HIGH importance. */
    @Test
    fun ringUnknown_channel_exists_with_expected_importance_after_ensureAll() = runTest {
        ServiceLocator.channelRegistry(context).ensureAll()

        val channel = notificationManager.getNotificationChannel(unknownChannelId)

        assertNotNull(
            "ring_unknown_v1 missing after ensureAll() — registry/versioning drift (§10)",
            channel,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            channel!!.importance,
        )
        assertEquals(expectedUnknownSound, channel.sound?.toString())
    }

    /**
     * CallStyle incoming-call notification posts on ring_unknown_v1 and the
     * platform accepts it (no BadNotificationException / dead channel).
     */
    @Test
    fun callStyle_notification_posts_on_ringUnknown_channel() {
        ServiceLocator.channelRegistry(context) // warm the holder (AppContextHolder seed)
        notificationManager.notify(NOTIFICATION_ID, buildCallStyleNotification())

        // Ground truth available to code ends here; the channel's existence +
        // sound shape is covered by ringUnknown_channel_exists_*.
        assertEquals(
            expectedUnknownSound,
            notificationManager.getNotificationChannel(unknownChannelId)?.sound?.toString(),
        )
    }

    // ---- TODO-on-device (PROBE.md §2, not machine-assertable) ---------------------
    //
    // 1. Manual leg: with this test's notification on screen — does it AUDIBLY
    //    ring with the shipped tone? ([channel_ring] event=call_style_posted …)
    // 2. End-to-end leg: second phone calls while XX-Phone holds both roles —
    //    does the real incoming call ring through this same channel?
    //    (PROBE.md §2 step 2.)
    // 3. Indirection leg: switch Settings → Sound → Phone ringtone, call again,
    //    confirm the tone FOLLOWS without any new channel being minted
    //    (DEFAULT_RINGTONE_URI indirection, §4.3). Default-tier channel only;
    //    unknown tier pins the raw resource by design.
    // 4. Volume-down mid-ring stops the ringer (on_silence_ringer path).

    private fun buildCallStyleNotification(): Notification {
        val caller = Person.Builder()
            .setName(context.getString(R.string.app_name))
            .setImportant(true)
            .build()
        return Notification.Builder(context, unknownChannelId)
            .setContentTitle(context.getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_phone_incoming)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .addPerson(caller.uri)
            .setStyle(
                Notification.CallStyle.forIncomingCall(
                    caller,
                    contentIntent(ACTION_DECLINE),
                    contentIntent(ACTION_ANSWER),
                ),
            )
            .build()
    }

    private fun contentIntent(action: String): PendingIntent = PendingIntent.getActivity(
        context,
        action.hashCode(),
        Intent(context, InCallActivity::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val NOTIFICATION_ID = 4001
        const val ACTION_ANSWER = "com.piercingxx.xxphone.probe.ANSWER"
        const val ACTION_DECLINE = "com.piercingxx.xxphone.probe.DECLINE"
    }
}
