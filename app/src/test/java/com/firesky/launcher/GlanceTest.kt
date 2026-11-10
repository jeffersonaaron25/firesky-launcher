package com.firesky.launcher

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GlanceTest {
    private val D = KeyEvent.ACTION_DOWN
    private val U = KeyEvent.ACTION_UP

    @Test fun aShortPressOfMenuIsLeftForTheApp() {
        val h = MenuHold()
        assertEquals(MenuHold.Act.PASS, h.on(D, 0, true))
        assertEquals(MenuHold.Act.PASS, h.on(U, 0, true))
    }

    @Test fun holdingMenuOpensTheGlanceAndKeepsTheRestOfThePress() {
        val h = MenuHold()
        assertEquals(MenuHold.Act.PASS, h.on(D, 0, true))
        assertEquals(MenuHold.Act.OPEN, h.on(D, 1, true))
        assertEquals(MenuHold.Act.EAT, h.on(D, 2, true))
        assertEquals(MenuHold.Act.EAT, h.on(D, 3, true))
        assertEquals(MenuHold.Act.EAT, h.on(U, 0, true))
        // The next press starts afresh.
        assertEquals(MenuHold.Act.PASS, h.on(D, 0, true))
        assertEquals(MenuHold.Act.PASS, h.on(U, 0, true))
    }

    /** What happens on the TV, where a held key's repeats never reach the service. */
    @Test fun holdingMenuWithoutRepeatsOpensItWhenTheTimeIsUp() {
        val h = MenuHold()
        assertEquals(MenuHold.Act.PASS, h.on(D, 0, true))
        assertTrue(h.timeUp())
        assertEquals(MenuHold.Act.EAT, h.on(U, 0, true))
        // Let go before the time is up, and it was the app's press.
        assertEquals(MenuHold.Act.PASS, h.on(D, 0, true))
        assertEquals(MenuHold.Act.PASS, h.on(U, 0, true))
        assertTrue(!h.timeUp())
        // Where the glance isn't wanted, the time running out does nothing.
        h.on(D, 0, false)
        assertTrue(!h.timeUp())
    }

    @Test fun whereTheGlanceIsntWantedHoldingMenuIsTheApps() {
        val h = MenuHold()
        h.on(D, 0, false)
        assertEquals(MenuHold.Act.PASS, h.on(D, 1, false))
        assertEquals(MenuHold.Act.PASS, h.on(U, 0, false))
    }

    private val pass = Orbit.Pass(1_000_000L + 3 * 60_000, 1_000_000L + 8 * 60_000, 60.0, 315.0, 135.0)

    @Test fun theStationIsMentionedAFewMinutesBefore() {
        val n = Nudges.station(listOf(pass), 1_000_000L, 10, 0L)!!
        assertEquals("Space station overhead in 3 min · look NW", n.text)
        assertEquals(pass.start, n.pass)
        // Not too early, not once it's begun, not under cloud, and not twice.
        assertNull(Nudges.station(listOf(pass), 1_000_000L - 10 * 60_000, 10, 0L))
        assertNull(Nudges.station(listOf(pass), pass.start + 1000, 10, 0L))
        assertNull(Nudges.station(listOf(pass), 1_000_000L, 80, 0L))
        assertNull(Nudges.station(listOf(pass), 1_000_000L, 10, pass.start))
    }

    private fun wx(rainAt: String?) = Weather.Wx(0, 0, 0, 3, true, rainAt, emptyList(), emptyList(), emptyList(), Sky.DEFAULT_SUN)

    @Test fun rainIsMentionedWhenItsClose() {
        assertEquals("Rain in 15 min", Nudges.rain(wx("Rain in 15 min"), 10 * 3_600_000L, 0L)!!.text)
        assertEquals("Snow in 30 min", Nudges.rain(wx("Snow in 30 min"), 10 * 3_600_000L, 0L)!!.text)
        assertNull(Nudges.rain(wx("Rain in 90 min"), 10 * 3_600_000L, 0L))
        assertNull(Nudges.rain(wx("Raining now"), 10 * 3_600_000L, 0L))
        assertNull(Nudges.rain(wx("Rain at 10 PM"), 10 * 3_600_000L, 0L))
        assertNull(Nudges.rain(wx(null), 10 * 3_600_000L, 0L))
        // Once, not every half hour of a wet evening.
        assertNull(Nudges.rain(wx("Rain in 15 min"), 10 * 3_600_000L, 9 * 3_600_000L))
    }

    @Test fun whenWhatsPlayingEnds() {
        val now = 50_000_000L
        val paused = Playing.Now("com.netflix.ninja", "Episode 4", false, 20 * 60_000L, 50 * 60_000L, now - 5_000, 0f)
        assertEquals(30 * 60_000L, paused.leftMs(now))
        assertEquals(.4f, paused.progress!!, .01f)
        val playing = Playing.Now("com.netflix.ninja", "Episode 4", true, 20 * 60_000L, 50 * 60_000L, now - 60_000, 1f)
        assertEquals(29 * 60_000L, playing.leftMs(now))
        val wall = java.util.Calendar.getInstance().apply { set(2026, 10, 10, 21, 0, 0) }.timeInMillis
        assertEquals("ends ${Prefs.timeOfDay(21, 30)} if you carry on", Playing.ends(paused, wall))
        // Without a length there's nothing to say, and nearly at the end there's nothing worth saying.
        assertNull(Playing.ends(Playing.Now("x", null, false, 1000, 0, now, 0f), wall))
        assertNull(Playing.ends(Playing.Now("x", null, false, 49 * 60_000L + 30_000, 50 * 60_000L, now, 0f), wall))
        assertNull(Playing.ends(null, wall))
    }

    @Test fun whatsPlayingFollowsTheApps() {
        val heard = mutableListOf<Playing.Now?>()
        Playing.now = null
        Playing.changed = { heard += it }
        fun st(state: Int) = android.media.session.PlaybackState.Builder().setState(state, 120_000L, 1f).build()
        Playing.report("com.netflix.ninja", st(android.media.session.PlaybackState.STATE_PLAYING), null)
        assertTrue(Playing.now!!.playing)
        Playing.report("com.netflix.ninja", st(android.media.session.PlaybackState.STATE_PAUSED), null)
        assertTrue(!Playing.now!!.playing)
        // Another app stopping doesn't clear what this one is doing; this one stopping does.
        Playing.report("com.hulu.plus", st(android.media.session.PlaybackState.STATE_STOPPED), null)
        assertEquals("com.netflix.ninja", Playing.now!!.pkg)
        Playing.report("com.netflix.ninja", st(android.media.session.PlaybackState.STATE_STOPPED), null)
        assertNull(Playing.now)
        // FireSky's own sounds aren't something you're watching.
        Playing.report(BuildConfig.APPLICATION_ID, st(android.media.session.PlaybackState.STATE_PLAYING), null)
        assertNull(Playing.now)
        assertEquals(3, heard.size)
        Playing.changed = null
    }
}
