package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class SkyTest {
    private val sun = Sun(6 * 60 + 59, 17 * 60 + 7)

    /** Reference values printed by the approved mock's own JavaScript. */
    @Test fun ridgesMatchTheApprovedPaintings() {
        fun check(k: String, a: Double, b: Double, c: Double) {
            val r = Sky.KEYS.getValue(k).ridges
            assertEquals(k, a, r[0][100].toDouble(), .01)
            assertEquals(k, b, r[1][37].toDouble(), .01)
            assertEquals(k, c, r[3][200].toDouble(), .01)
        }
        check("dusk", 168.134, 144.317, 13.924)
        check("alpine", 84.247, 152.269, 78.042)
        check("night", 82.998, 37.772, 35.900)
        check("amber", 103.100, 116.444, 40.263)
    }

    @Test fun middayIsTheDayScene() {
        val v = FloatArray(Sky.N); Sky.pack(720f, sun, v)
        assertEquals(Sky.HORIZON, v[Sky.HZ], .001f)
        assertEquals(0f, v[Sky.WATER], .001f)
        assertEquals(0f, v[Sky.STARS], .001f)
    }

    @Test fun midnightHasStarsAndNoSun() {
        val v = FloatArray(Sky.N); Sky.pack(0f, sun, v)
        assertEquals(1f, v[Sky.STARS], .001f)
        assertEquals(0f, v[Sky.SUN_A], .001f)
    }

    @Test fun sunRisesLeftAndSetsRight() {
        val v = FloatArray(Sky.N)
        Sky.pack(sun.rise.toFloat(), sun, v); val riseX = v[Sky.SUN_X]; val riseY = v[Sky.SUN_Y]
        Sky.pack((sun.rise + sun.set) / 2f, sun, v); val noonY = v[Sky.SUN_Y]
        Sky.pack(sun.set.toFloat(), sun, v); val setX = v[Sky.SUN_X]
        assertTrue(riseX < .2f && setX > .8f)
        assertTrue("noon is higher than sunrise", noonY < riseY - .4f)
    }

    @Test fun theDayNeverJumps() {
        val a = FloatArray(Sky.N); val b = FloatArray(Sky.N)
        Sky.pack(0f, sun, a)
        for (m in 1..1439) {
            Sky.pack(m.toFloat(), sun, b)
            for (i in 0 until 25) assertTrue("colour $i at minute $m", abs(a[i] - b[i]) < 8f)
            b.copyInto(a)
        }
    }

    private val red = floatArrayOf(229f, 9f, 20f)
    private val midnight = 0f

    @Test fun tintKeepsTheSkyDark() {
        val v = FloatArray(Sky.N); Sky.pack(midnight, sun, v)
        fun lum(o: Int) = .3f * v[o] + .59f * v[o + 1] + .11f * v[o + 2]
        val before = lum(0)
        Sky.tint(v, red)
        assertEquals(before, lum(0), 3f)
    }

    @Test fun tintMakesTheLandShowAtNight() {
        val v = FloatArray(Sky.N); Sky.pack(midnight, sun, v)
        Sky.tint(v, red)
        assertTrue("the far ridge is properly red, not maroon: ${v[9]}", v[9] > 170f)
        assertTrue("it isn't pink", v[10] < 40f)
        assertTrue("nearer ridges stay darker, so the land keeps its depth", v[9] > v[12] && v[12] > v[15] && v[15] > v[18])
    }

    @Test fun tintLeavesGreyTilesQuiet() {
        val v = FloatArray(Sky.N); Sky.pack(midnight, sun, v)
        Sky.tint(v, floatArrayOf(90f, 96f, 112f))
        assertTrue("Settings doesn't light up: ${v[9]}", v[9] < 70f)
    }

    @Test fun tintNeverLeavesTheColourRange() {
        for (m in listOf(0f, 420f, 720f, 1027f)) for (c in listOf(red, floatArrayOf(255f, 255f, 255f), floatArrayOf(0f, 0f, 0f))) {
            val v = FloatArray(Sky.N); Sky.pack(m, sun, v)
            Sky.tint(v, c)
            for (i in 0 until 24) assertTrue("colour $i at $m", v[i] in 0f..255f)
        }
    }

    @Test fun weatherCodesMapToTheRightScene() {
        assertEquals(Sky.CLEAR, Sky.kindOf(0))
        assertEquals(Sky.CLOUDY, Sky.kindOf(3))
        assertEquals(Sky.FOG, Sky.kindOf(45))
        assertEquals(Sky.RAIN, Sky.kindOf(61))
        assertEquals(Sky.SNOW, Sky.kindOf(75))
        assertEquals(Sky.RAIN, Sky.kindOf(95))
    }

    @Test fun aTileGivesTheSceneItsLandAndColour() {
        val f = Flow(); val shape = Sky.shape("com.netflix.ninja")
        f.target(shape, floatArrayOf(176f, 16f, 27f))
        f.snap(720f, sun)
        assertEquals(shape[1][40], f.ridge[1][40], .001f)
        assertTrue("the land turned red", f.col[15] > f.col[17])
    }

    @Test fun withNoTileTheLandFollowsTheDay() {
        val f = Flow(); f.snap(720f, sun)
        assertEquals(Sky.KEYS.getValue("alpine").ridges[1][40], f.ridge[1][40], .001f)
    }

    @Test fun driftMovesTheLandWithoutJumps() {
        val f = Flow(); f.snap(720f, sun)
        val start = f.ridge[0].copyOf()
        f.startDrift()
        assertTrue(f.drifting)
        var worst = 0f; var prev = f.ridge[0].copyOf()
        repeat(60 * 12) {
            f.drift(1 / 60f)
            for (j in 0 until POINTS) worst = max(worst, abs(f.ridge[0][j] - prev[j]))
            prev = f.ridge[0].copyOf()
        }
        assertNotEquals(start[128], f.ridge[0][128])
        assertTrue("worst step $worst px", worst < 3f)
        f.stopDrift()
        assertFalse(f.drifting)
    }

    @Test fun knowsWhenItIsNight() {
        assertTrue(Sky.isNight(60f, sun))
        assertFalse(Sky.isNight(720f, sun))
    }
}
