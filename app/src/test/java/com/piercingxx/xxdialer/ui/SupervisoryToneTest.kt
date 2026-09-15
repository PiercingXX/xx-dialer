package com.piercingxx.xxdialer.ui

import android.content.ContextWrapper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * InCallActivity field-inits SupervisoryTone with `this` before attach.
 * Touching applicationContext in that constructor crashed every call UI
 * launch (stock dialer then took the live call).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupervisoryToneTest {

    @Test
    fun constructor_does_not_touch_application_context() {
        SupervisoryTone(ContextWrapper(null))
    }

    @Test
    fun in_call_activity_constructs_before_attach() {
        InCallActivity()
    }
}
