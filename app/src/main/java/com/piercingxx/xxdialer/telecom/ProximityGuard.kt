package com.piercingxx.xxdialer.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Holder for the platform's PROXIMITY_SCREEN_OFF_WAKE_LOCK — the whole of
 * "dim the screen when the phone is at my ear", handed to the display
 * subsystem rather than re-implemented.
 *
 * Why the platform lock and not a `Sensor.TYPE_PROXIMITY` listener that dims
 * the window: the platform lock also suppresses TOUCH INPUT while the sensor
 * reads near, and that is the actual point. A phone against a face is a phone
 * being pressed by a cheek; a brightness-only imitation leaves a live
 * touchscreen under that cheek, which mutes, adds calls and hangs up on
 * people. It is also the display owner's own state, so it interacts correctly
 * with the screen timeout, the lockscreen and the power button instead of
 * fighting them.
 *
 * Everything here degrades silently. A device without the wake-lock level
 * (isWakeLockLevelSupported false — no proximity sensor, or a display stack
 * that refuses the level) simply never blanks; that is a missing convenience,
 * never a failure worth surfacing, and never a crash.
 */
class ProximityGuard(context: Context) {

    private val power: PowerManager? =
        runCatching { context.applicationContext.getSystemService(PowerManager::class.java) }
            .getOrNull()

    /**
     * Asked once, at construction: the answer is a property of the hardware
     * and the display stack, and re-asking it per state change would put a
     * binder call on the path of every call-state transition.
     */
    val isSupported: Boolean =
        runCatching {
            power?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true
        }.getOrDefault(false)

    /**
     * Created lazily and kept: a WakeLock object is cheap to keep and awkward
     * to recreate, and reference counting is turned OFF so acquire/release are
     * idempotent — the service calls [apply] on every state and route change,
     * which means many more calls than transitions.
     */
    private val lock: PowerManager.WakeLock? by lazy {
        if (!isSupported) {
            null
        } else {
            runCatching {
                power?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, LOCK_TAG)
                    ?.apply { setReferenceCounted(false) }
            }.onFailure { Log.w(LOG_TAG, "proximity wake lock unavailable", it) }.getOrNull()
        }
    }

    /** True only when this process is actually holding the display down. */
    val isHeld: Boolean
        get() = runCatching { lock?.isHeld == true }.getOrDefault(false)

    /**
     * Single entry point, driven by [ProximityPolicy.decide]. Synchronized
     * because Telecom's callbacks and the service's own coroutine scope can
     * both land here, and an acquire racing a release is precisely how a
     * dark screen gets stranded.
     */
    @Synchronized
    @SuppressLint("WakelockTimeout") // Deliberate: the sensor, not a clock, ends this lock.
    fun apply(action: ProximityAction) {
        val wakeLock = lock ?: return // unsupported: degrade silently, per the class doc
        runCatching {
            when (action) {
                ProximityAction.HOLD -> if (!wakeLock.isHeld) wakeLock.acquire()
                ProximityAction.RELEASE_IMMEDIATE -> if (wakeLock.isHeld) wakeLock.release()
                ProximityAction.RELEASE_WHEN_FAR ->
                    if (wakeLock.isHeld) {
                        wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                    }
            }
        }.onFailure { Log.w(LOG_TAG, "proximity lock $action failed", it) }
    }

    /**
     * Unconditional teardown, for onDestroy and for any path that must not
     * leave the display down. Deliberately NOT the wait-for-far release: the
     * service is going away, so nothing of ours will be watching the sensor,
     * and a screen whose return is conditional on an observer that no longer
     * exists is exactly the stranded-dark-screen failure this guards against.
     *
     * Releasing an already-released lock must not throw — reference counting
     * is off and the isHeld check plus runCatching make the double release a
     * no-op rather than the classic "under-locked" IllegalStateException.
     */
    @Synchronized
    fun releaseNow() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.w(LOG_TAG, "proximity lock teardown failed", it) }
    }

    private companion object {
        const val LOG_TAG = "ProximityGuard"

        /**
         * Wake-lock tag convention is "package:reason" — it is what shows up
         * in dumpsys power and in battery blame, so it names the feature.
         */
        const val LOCK_TAG = "com.piercingxx.xxdialer:proximity"
    }
}
