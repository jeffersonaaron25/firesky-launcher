package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StormTest {
    private val dt = 1 / 30f

    /** Ten minutes of storm, a thirtieth of a second at a time: how bright each moment was. */
    private fun run(s: Storm = Storm(), seconds: Int = 600) = List(seconds * 30) { s.step(dt) }

    @Test fun itsDarkMostOfTheTime() {
        val f = run()
        assertTrue(f.all { it in 0f..1f })
        assertTrue(f.count { it > .02f } < f.size / 5)
        assertEquals(0f, f.first(), 0f)
    }

    @Test fun strikesComeEveryFewSeconds() {
        val f = run()
        // A strike begins where the dark gives way to light.
        val starts = f.indices.filter { it > 0 && f[it] > .3f && f.subList(maxOf(0, it - 20), it).all { v -> v < .05f } }
        assertTrue("${starts.size} strikes", starts.size in 35..110)
        val gaps = starts.zipWithNext { a, b -> (b - a) / 30f }
        assertTrue(gaps.all { it >= Storm.GAP_S.toFloat() })
        assertTrue(gaps.all { it <= (Storm.GAP_S + Storm.SPREAD_S + 2).toFloat() })
    }

    @Test fun eachStrikeIsBrightAndBrief() {
        val s = Storm()
        var peak = 0f; var lit = 0; var strikes = 0; var was = false
        repeat(600 * 30) {
            val f = s.step(dt)
            if (s.striking) { peak = maxOf(peak, f); lit++ }
            if (was && !s.striking) { assertTrue(peak > .9f); assertTrue(lit < 45); strikes++; peak = 0f; lit = 0 }
            was = s.striking
        }
        assertTrue(strikes > 30)
    }

    @Test fun someStrikesShowTheBoltAndSomeOnlyLightTheCloud() {
        val s = Storm()
        val bolts = HashSet<Boolean>(); val places = HashSet<Int>()
        var was = false
        repeat(600 * 30) {
            s.step(dt)
            if (s.striking && !was) { bolts += s.bolt; places += (s.x / 100).toInt(); assertTrue(s.x in 160f..1120f) }
            was = s.striking
        }
        assertEquals(setOf(true, false), bolts)
        assertTrue(places.size > 5)
    }

    @Test fun onlyThunderstormsCountAsStorms() {
        fun wx(code: Int) = Weather.Wx(0, 0, 0, code, true, null, emptyList(), emptyList(), emptyList(), Sky.DEFAULT_SUN)
        assertTrue(wx(95).storm); assertTrue(wx(99).storm)
        assertFalse(wx(63).storm); assertFalse(wx(3).storm); assertFalse(wx(82).storm)
        // A storm still falls as rain.
        assertEquals(Sky.RAIN, wx(96).kind)
    }

    @Test fun cloudsMoveOnlyWhileThePictureIsMoving() {
        val d = CloudDrift()
        // Frames a thirtieth of a second apart: ten seconds of them moves the clouds ten seconds' worth.
        var t = 1_000_000L
        d.step(t, 1f)
        repeat(300) { t += 33; d.step(t, 1f) }
        assertEquals(9.9f, d.at, .05f)
        // A still drawn five minutes later leaves them exactly where they were: no leap.
        t += 300_000
        assertEquals(9.9f, d.step(t, 1f), .05f)
        // The forecast changing the wind changes how fast they go, not where they are.
        val before = d.at
        t += 33
        assertEquals(before - .033f * .5f, d.step(t, -.5f), .001f)
    }

    @Test fun cloudsComeRoundWithoutASeam() {
        val d = CloudDrift()
        var t = 1L
        d.step(t, 1f)
        repeat(60_000) { t += 200; d.step(t, 1f) }
        assertTrue(d.at in 0f..CloudDrift.LOOP)
        // Each layer's speed goes a whole number of times into the loop.
        for (speed in listOf(.0012f, .002f, .0009f)) assertEquals(0f, (CloudDrift.LOOP * speed) % 1f, 1e-3f)
        // Blowing the other way, they still stay inside it.
        repeat(100) { t += 200; d.step(t, -1f) }
        assertTrue(d.at in 0f..CloudDrift.LOOP)
    }

    /** The home screen is a still, redrawn every half minute: the water in each must be just as the last left it. */
    @Test fun wavesStayAsTheyWereFromOneStillToTheNext() {
        val w = CloudDrift(CloudDrift.WAVES)
        var t = 5_000_000L
        w.step(t, 1f)
        repeat(90) { t += 33; w.step(t, 1f) }
        val left = w.at
        repeat(3) { t += 30_000; assertEquals(left, w.step(t, 1f), 0f) }
        // The idle screen carries on from there.
        t += 33
        assertEquals(left + .033f, w.step(t, 1f), .001f)
        // And they come round on the hour without a seam.
        for (period in listOf(3f, 4f, 6f, 120f)) assertEquals(0f, CloudDrift.WAVES % period, 0f)
        repeat(100_000) { t += 200; w.step(t, 1f) }
        assertTrue(w.at in 0f..CloudDrift.WAVES)
    }
}
