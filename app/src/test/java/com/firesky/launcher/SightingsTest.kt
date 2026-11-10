package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SightingsTest {
    private val sun = Sun(7 * 60, 17 * 60)

    @After fun clean() { Balloon.test = false; Fireworks.test = false }

    @Test fun aLightBreezeMovesThingsAsTheyAlwaysMoved() {
        assertEquals(1f, Wind.factor(10f), .01f)
        assertTrue(Wind.factor(40f) > 2f)
        assertEquals(.45f, Wind.factor(0f), 0f)
        assertEquals(3f, Wind.factor(200f), 0f)
        // Rain leans the way the clouds go, and further in a stronger wind.
        assertEquals(.21f, Wind.slant(1f, 1f), .001f)
        assertEquals(-.21f, Wind.slant(-.4f, 1f), .001f)
        assertTrue(Wind.slant(1f, 3f) > Wind.slant(1f, 1f))
    }

    @Test fun windIsInKilometresWhicheverUnitsItCameIn() {
        val wx = Weather.Wx(0, 0, 10, 0, true, null, emptyList(), emptyList(), emptyList(), sun)
        assertEquals(if (Prefs.mph) 16.09f else 10f, wx.windKmh, .01f)
    }

    private fun day(y: Int, d: Int) = Calendar.getInstance().apply { clear(); set(Calendar.YEAR, y); set(Calendar.DAY_OF_YEAR, d) }

    @Test fun balloonsGoUpOnSomeCalmMorningsOnly() {
        val mornings = (1..365).count { Balloon.morning(day(2026, it), sun.rise + 30f, sun, Sky.CLEAR, 10, 8f) }
        assertTrue("$mornings mornings", mornings in 90..170)
        val yes = (1..365).first { Balloon.morning(day(2026, it), sun.rise + 30f, sun, Sky.CLEAR, 10, 8f) }
        assertFalse(Balloon.morning(day(2026, yes), 13 * 60f, sun, Sky.CLEAR, 10, 8f))
        assertFalse(Balloon.morning(day(2026, yes), sun.rise + 30f, sun, Sky.RAIN, 10, 8f))
        assertFalse(Balloon.morning(day(2026, yes), sun.rise + 30f, sun, Sky.CLEAR, 10, 30f))
        Balloon.test = true
        assertTrue(Balloon.morning(day(2026, yes), 13 * 60f, sun, Sky.RAIN, 100, 50f))
    }

    @Test fun theBalloonDriftsAcrossWithTheWindAndAwayAgain() {
        val a = FloatArray(4)
        Balloon.at(0f, 1f, a); assertEquals(0f, a[3], 0f); assertTrue(a[0] > 1280f)
        Balloon.at(Balloon.CROSS_S / 2, 1f, a); assertEquals(1f, a[3], 0f); assertTrue(a[0] in 400f..900f)
        Balloon.at(Balloon.CROSS_S + 1f, 1f, a); assertEquals(0f, a[3], 0f)
        Balloon.at(Balloon.CROSS_S / 2, -1f, a); assertTrue(a[0] in 400f..900f)
        Balloon.at(10f, -1f, a); assertTrue(a[0] < 200f)
        // Another comes along later.
        Balloon.at(Balloon.EVERY_S + Balloon.CROSS_S / 2, 1f, a); assertEquals(1f, a[3], 0f)
    }

    private fun at(m: Int, d: Int, h: Int, min: Int) = Calendar.getInstance().apply { clear(); set(2026, m, d, h, min) }

    @Test fun fireworksAreForNewYearsMidnight() {
        assertTrue(Fireworks.on(at(Calendar.DECEMBER, 31, 23, 59)))
        assertTrue(Fireworks.on(at(Calendar.JANUARY, 1, 0, 5)))
        assertFalse(Fireworks.on(at(Calendar.DECEMBER, 31, 23, 40)))
        assertFalse(Fireworks.on(at(Calendar.JANUARY, 1, 0, 30)))
        assertFalse(Fireworks.on(at(Calendar.JULY, 4, 0, 0)))
    }

    @Test fun fireworksAreShadedLikeTheBackdropUnderTheWords() {
        assertEquals(1f, Fireworks.shade(800f, 250f), 0f)
        assertTrue(Fireworks.shade(100f, 250f) < .7f)
        assertTrue(Fireworks.shade(800f, 650f) < .5f)
    }

    @Test fun rocketsGoUpBurstAndFadeAway() {
        var most = 0
        repeat(30 * 20) {
            var n = 0
            Fireworks.step(1 / 30f, true) { x, y, _, _, _, _, a -> n++; assertTrue(a in 0f..1f); assertTrue(y < 600f && x in -200f..1500f) }
            most = maxOf(most, n)
        }
        assertTrue("$most sparks at once", most in 120..Fireworks.MAX)
        assertTrue(Fireworks.busy)
        repeat(30 * 8) { Fireworks.step(1 / 30f, false) { _, _, _, _, _, _, _ -> } }
        assertFalse(Fireworks.busy)
    }
}
