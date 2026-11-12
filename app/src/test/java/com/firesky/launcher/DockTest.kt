package com.firesky.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DockTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val s = HomeState()
    private val act = object : Actions {
        override fun open(c: Card) {}
        override fun amazonHome() {}
    }
    private val playStation = listOf(Hdmi.Dev("PlayStation 5", 2, true))

    @Before fun setUp() { Prefs.init(ctx); s.load(ctx) }

    @After fun tearDown() {
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        Prefs.forget()
    }

    private fun ps5Row() = settingRows(s, act).first { it.key == Prefs.PS5 }

    /** AirPlay and screen mirroring work without opening Fire TV's AirPlay app, which FireSky isn't allowed to open anyway. */
    @Test fun theAirPlayReceiverIsNeverATile() {
        assertFalse(Apps.shown("com.apple.airplay.amazon.airplay"))
        assertTrue(Apps.shown("com.netflix.ninja"))
    }

    /** A TV without a PlayStation never shows a tile for one. */
    @Test fun noPlayStationNoTile() {
        Prefs.set(Prefs.PS5, "0")
        assertTrue(s.dock().none { it.dockId == "ps5" })
        assertTrue(s.tonight().none { it.kind == Kind.PS5 })
    }

    /** Hiding the tile turns it off in the settings, where it can be brought back, and takes its card too. */
    @Test fun hidingThePlayStationTurnsItOff() {
        Prefs.set(Prefs.PS5, "0")
        s.hdmi = playStation
        val tile = s.dock().first { it.dockId == "ps5" }
        assertTrue(s.tonight().any { it.kind == Kind.PS5 })

        s.hide(tile, null)
        assertTrue(Prefs.ps5Off)
        assertTrue(s.dock().none { it.dockId == "ps5" })
        assertTrue(s.tonight().none { it.kind == Kind.PS5 })
        assertEquals("Off", ps5Row().value)
        assertTrue(s.toast!!.contains("settings"))

        Prefs.set(Prefs.PS5, "0")
        assertTrue(s.dock().any { it.dockId == "ps5" })
        assertEquals("When it's plugged in", ps5Row().value)
    }

    /** A fresh start shows placeholders until everything is in, then remembers how many tiles there were. */
    @Test fun placeholdersMatchWhatWasThereLastTime() {
        assertTrue(s.loading)
        assertEquals(7, s.expectDock)
        assertEquals(5, s.expectCards)
        s.apps = listOf("com.netflix.ninja", "com.hulu.plus", "com.amazon.firebat").map { AppEntry(it, it, android.content.Intent(), null, null, androidx.compose.ui.graphics.Color.Gray) }
        // Fewer cards than last time: the placeholders wait for the rest, until it's been long enough.
        s.loaded()
        if (s.tonight().size < s.expectCards) { assertTrue(s.loading); s.loaded(force = true) }
        assertFalse(s.loading)
        assertEquals(s.dock().count { it.kind == Kind.APP }, s.expectDock)
        assertTrue(s.expectCards in 1..HomeState.MAX_CARDS)
        // A later start, in a new state, expects the same.
        val again = HomeState()
        assertEquals(s.expectDock, again.expectDock)
    }

    /** Off holds even for a PlayStation kept in the dock at a set port. */
    @Test fun offBeatsAlways() {
        s.hdmi = playStation
        Prefs.set(Prefs.PS5, "2")
        assertTrue(s.dock().any { it.dockId == "ps5" })
        Prefs.set(Prefs.PS5, "off")
        assertTrue(s.dock().none { it.dockId == "ps5" })
        assertEquals(0, Prefs.ps5Port)
    }
}
