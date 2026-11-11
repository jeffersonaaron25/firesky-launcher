package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private val apps = listOf(
        "com.netflix.ninja", "com.apple.atve.amazon.appletv", "com.amazon.firebat", "org.xbmc.kodi",
        "com.hbo.hbonow", "com.hulu.plus", "com.amazon.firetv.youtube", "com.apple.airplay.amazon.airplay", "settings", "all",
    )

    @Test fun mountainsAreDrawnAsTheyAlwaysWere() {
        assertNull(Places.land(Places.MOUNTAINS, "com.netflix.ninja"))
        assertEquals(Places.MOUNTAINS, Places.kind("", "com.netflix.ninja", 0))
        assertEquals(Places.MOUNTAINS, Places.kind("nonsense", "com.netflix.ninja", 0))
    }

    @Test fun everyAppGetsItsOwnVersionOfEachPlace() {
        for (kind in Places.KINDS - Places.MOUNTAINS) {
            val lands = apps.map { Places.land(kind, it)!! }
            for (l in lands) {
                assertEquals(kind, l.kind)
                assertEquals(4, l.height.size)
                assertTrue(l.height.all { it.size == Places.DETAIL })
                assertTrue("$kind has land", l.height.any { h -> h.any { it > 1f } })
            }
            // No two apps share a picture.
            assertEquals(kind, lands.size, lands.map { l -> l.height.joinToString { it.contentHashCode().toString() } }.toSet().size)
            // A single NaN makes the shader skip the whole layer.
            for (l in lands) for (a in l.height + l.seed + l.wall) assertTrue(kind, a.all { it.isFinite() && it >= 0f })
            // And the same app always gets the same one.
            assertTrue(Places.land(kind, "com.hulu.plus") === Places.land(kind, "com.hulu.plus"))
        }
    }

    @Test fun waterAndIslandsHaveAWaterlineAndCitiesSometimesARiver() {
        for (id in apps) {
            assertNotNull(Places.land(Places.WATER, id)!!.water)
            assertNotNull(Places.land(Places.ISLANDS, id)!!.water)
            assertNull(Places.land(Places.PLAINS, id)!!.water)
        }
        val rivers = apps.count { Places.land(Places.CITY, it)!!.water != null }
        assertTrue("some cities have a river and some don't: $rivers", rivers in 1 until apps.size)
    }

    /** The left side is where the text sits, so buildings there stay lower than the towers on the right. */
    @Test fun citiesKeepTheLeftLow() {
        for (id in apps) {
            val near = Places.land(Places.CITY, id)!!.height[2]
            val left = (0 until 400).maxOf { near[it] }
            assertTrue("$id left ${left.toInt()}", left <= Places.LEFT_CITY * 1.12f + 1f)
            assertTrue((0 until Places.DETAIL).all { near[it] <= 330f * 1.12f + 340f })
        }
    }

    @Test fun onlyBuildingsHaveWindows() {
        for (id in apps) {
            val city = Places.land(Places.CITY, id)!!
            assertTrue(city.windows[1] > 0f && city.windows[2] > 0f)
            assertTrue(city.seed[2].any { it > 0f })
            // A building's walls never stand above its own roofline.
            for (j in 0 until Places.DETAIL) assertTrue(city.wall[2][j] <= city.height[2][j] + .01f)
            for (k in listOf(Places.WATER, Places.PLAINS, Places.ISLANDS)) {
                val l = Places.land(k, id)!!
                assertTrue(l.windows.all { it == 0f })
                assertTrue(l.seed.all { s -> s.all { it == 0f } })
            }
        }
    }

    /** The sun sets on a place's own horizon: water and plains lie well above the mountains' feet. */
    @Test fun eachPlaceHasItsOwnHorizon() {
        for (id in apps) {
            for (k in listOf(Places.WATER, Places.ISLANDS)) assertTrue(k, Places.land(k, id)!!.horizon <= -Places.WATERLINE)
            for (k in Places.KINDS - Places.MOUNTAINS) assertTrue(k, Places.land(k, id)!!.horizon in -Places.WATERLINE - 20f..-60f)
        }
    }

    @Test fun shuffleGivesEachAppAPlaceOfItsOwnAndSpreadsThemAround() {
        val picks = apps.map { Places.kind(Places.SHUFFLE, it, 0) }
        assertEquals(picks, apps.map { Places.kind(Places.SHUFFLE, it, 99) })
        assertTrue("more than two kinds among ${picks.toSet()}", picks.toSet().size > 2)
    }

    @Test fun theDailyPlaceChangesAtSunriseNotMidnight() {
        val sun = Sun(7 * 60, 17 * 60)
        val day = 20_000L * 1440
        val beforeDawn = Places.day(day + 6 * 60, sun)
        val afterDawn = Places.day(day + 7 * 60 + 1, sun)
        val evening = Places.day(day + 23 * 60, sun)
        assertEquals(beforeDawn + 1, afterDawn)
        assertEquals(afterDawn, evening)
        assertNotEquals(Places.kind(Places.DAILY, "x", beforeDawn), Places.kind(Places.DAILY, "x", afterDawn))
        // Every app shares the day's place.
        assertEquals(Places.kind(Places.DAILY, "com.netflix.ninja", afterDawn), Places.kind(Places.DAILY, "com.hulu.plus", afterDawn))
    }

    @Test fun windowsLightUpAtDuskAndThinOutLate() {
        val sun = Sun(7 * 60, 17 * 60)
        assertEquals(0f, Places.lit(12 * 60f, sun))
        assertEquals(0f, Places.lit(8 * 60f, sun))
        val dusk = Places.lit(17 * 60 + 60f, sun)
        val late = Places.lit(2 * 60f, sun)
        assertTrue("dusk $dusk", dusk > .5f)
        assertTrue("late $late", late in .2f..(dusk - .1f))
        assertTrue(Places.lit(17 * 60f, sun) in .05f..dusk)
        // No jump at midnight.
        assertEquals(Places.lit(23 * 60 + 59.9f, sun), Places.lit(0f, sun), .01f)
    }
}
