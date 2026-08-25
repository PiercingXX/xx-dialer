package com.piercingxx.xxdialer.ring

import android.content.Context

/**
 * FLAGGED DEVIATION from the dictated shape: `SilencedNotifier` is an object
 * with non-suspend, context-free methods (`postSilenced(entry)` as consumed
 * by XxInCallService), yet it needs a Context for notifications, settings,
 * and the digest stasher. ServiceLocator and XxApplication are outside this
 * workstream's file ownership, so the holder is seeded by [ChannelRegistry]'s
 * constructor — the registry is always built first on any ring path (it is a
 * constructor dependency of RingRouter and of the ServiceLocator wiring).
 *
 * If it reads null (pathological ordering), SilencedNotifier skips rather
 * than crashes — losing a record card is acceptable; blocking the ring path
 * is not (todo rule #2).
 */
internal object AppContextHolder {

    @Volatile
    var appContext: Context? = null
        private set

    fun set(context: Context) {
        appContext = context.applicationContext ?: context
    }
}
