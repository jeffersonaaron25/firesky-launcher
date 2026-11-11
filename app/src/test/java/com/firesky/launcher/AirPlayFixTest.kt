package com.firesky.launcher

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AirPlayFixTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After fun tearDown() { Stopped.forget(); ctx.getSharedPreferences("media", 0).edit().clear().commit() }

    /** Most TVs never show the crash, and nothing changes for them: no session of FireSky's own. */
    @Test fun nothingHappensUntilTheCrashIsSeen() {
        Stopped.attach(ctx)
        Stopped.saw("I/EniraService(10297): onStartCommand Intent { cmp=com.apple.airplay.amazon.airplay/.EniraService } flags 0 startId 1")
        Stopped.saw("I/EniraService.Nsd(10297): onServiceRegistered(_hap._tcp): requested service name: FireTV Stick")
        idle()
        assertFalse(Stopped.holding)
        assertFalse(Stopped.needed(ctx))
    }

    @Test fun aTvThatShowedTheCrashKeepsTheFix() {
        Stopped.attach(ctx)
        Stopped.saw("I/EniraDaemon.HAP(10211): [com.apple.mfi.HomeKit.Core:Characteristic] [0000000000000001 FireTV Stick] " +
            "[000000000000003A media-state.current] Value out of range: 4 (constraints: minimumValue = 0 / maximumValue = 3 / stepValue = 1).")
        idle()
        assertTrue(Stopped.holding)
        assertTrue(Stopped.needed(ctx))
        // Remembered, so it's in place from the start next time, before the AirPlay app first starts.
        Stopped.forget(); Stopped.attach(ctx)
        assertTrue(Stopped.needed(ctx))
    }

    /** A Fire TV without the AirPlay app, like a Stick, doesn't follow its log at all. */
    @Test fun withoutTheAirPlayAppThereIsNothingToWatch() {
        Stopped.watch(ctx)
        idle()
        assertFalse(Stopped.holding)
    }
}
