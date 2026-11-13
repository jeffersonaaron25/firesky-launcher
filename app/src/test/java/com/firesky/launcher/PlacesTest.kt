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
        for (kind in Places.KINDS - Places.MOUNTAINS - Places.SPACE) {
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

    @Test fun waterIslandsAndCitiesHaveAWaterline() {
        for (id in apps) {
            assertNotNull(Places.land(Places.WATER, id)!!.water)
            assertNotNull(Places.land(Places.ISLANDS, id)!!.water)
            // Plains and forest have a lake at the foot of their nearest land; the desert stays dry.
            for (k in listOf(Places.PLAINS, Places.FOREST)) Places.land(k, id)!!.let { assertEquals(k, it.base[3], it.water) }
            assertNull(Places.land(Places.DESERT, id)!!.water)
        }
        // Every city stands on its river, right at the foot of its nearest buildings.
        for (id in apps) Places.land(Places.CITY, id)!!.let { assertEquals(it.base[2], it.water) }
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
            for (k in listOf(Places.WATER, Places.PLAINS, Places.ISLANDS, Places.FOREST)) {
                val l = Places.land(k, id)!!
                assertTrue(l.windows.all { it == 0f })
                assertTrue(l.seed.all { s -> s.all { it == 0f } })
            }
            assertTrue(Places.land(Places.DESERT, id)!!.windows.all { it == 0f })
        }
    }

    /** The sun sets on a place's own horizon: water and plains lie well above the mountains' feet. */
    @Test fun eachPlaceHasItsOwnHorizon() {
        for (id in apps) {
            for (k in listOf(Places.WATER, Places.ISLANDS)) assertTrue(k, Places.land(k, id)!!.horizon <= -Places.WATERLINE)
            // One horizon for the sky whichever the place, and every place's farthest ground on it or in front of it.
            for (k in Places.KINDS - Places.MOUNTAINS) {
                val l = Places.land(k, id)!!
                assertEquals(k, Places.HORIZON, l.horizon)
                assertTrue(k, l.base.filter { it < 500f }.all { it >= Places.HORIZON - 12f })
            }
            // Space has no ground for anything to set behind: the sky keeps the mountains' horizon.
            assertEquals(Places.HORIZON, Places.land(Places.SPACE, id)!!.horizon)
        }
    }

    /** Space is the stars alone: no layer comes on screen, and the apps look out in different directions. */
    @Test fun spaceHasNoLandAndEachAppLooksItsOwnWay() {
        val lands = apps.map { Places.land(Places.SPACE, it)!! }
        for (l in lands) {
            assertTrue(l.base.all { it > 720f })
            assertNull(l.water)
            assertTrue(l.facing in 0.0..315.0)
        }
        assertTrue("several directions among ${lands.map { it.facing }}", lands.map { it.facing }.toSet().size >= 3)
        // Nothing else looks anywhere but south.
        for (k in Places.KINDS - Places.MOUNTAINS - Places.SPACE) assertEquals(180.0, Places.land(k, "com.hulu.plus")!!.facing, 0.0)
    }

    /** The land says which way each dune faces, so the sun lights one side: right where it should be, and smoothly. */
    @Test fun dunesHaveLongSlopesAndSteepFaces() {
        for (id in apps) {
            val d = Places.land(Places.DESERT, id)!!
            for (i in 0..3) {
                // Facing right where it falls to the right, and turning gradually, never jumping.
                val agree = (2 until Places.DETAIL - 2).count { (d.seed[i][it] > .5f) == (d.height[i][it + 2] < d.height[i][it - 2]) }
                assertTrue("$id rank $i agree $agree", agree > Places.DETAIL * .8f)
                assertTrue((1 until Places.DETAIL).all { kotlin.math.abs(d.seed[i][it] - d.seed[i][it - 1]) < .25f })
            }
        }
    }

    @Test fun nearTreesStandTallerThanFarOnes() {
        for (id in apps) {
            val f = Places.land(Places.FOREST, id)!!
            for (i in 1..3) assertTrue("$id rank $i", f.height[i].max() > f.height[i - 1].max())
        }
    }

    /** Each app has its own place, and a new one every morning: never the same two days running, every place in turn. */
    @Test fun shuffleGivesEachAppItsOwnPlaceANewOneEachDay() {
        val today = apps.map { Places.kind(Places.SHUFFLE, it, 100) }
        assertTrue("more than two kinds among ${today.toSet()}", today.toSet().size > 2)
        // The same all day.
        assertEquals(today, apps.map { Places.kind(Places.SHUFFLE, it, 100) })
        for (id in apps + (0 until 50).map { "app.$it" }) {
            val days = (100 until 108).map { Places.kind(Places.SHUFFLE, id, it.toLong()) }
            assertEquals(id, Places.KINDS.toSet(), days.toSet())
            for (d in 1 until days.size) assertNotEquals(id, days[d - 1], days[d])
        }
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

    /** Space can be chosen on its own, but shuffling keeps to the landscapes. */
    @Test fun spaceIsChosenNeverShuffled() {
        assertTrue(Places.SPACE !in Places.KINDS)
        assertEquals(Places.SPACE, Places.kind(Places.SPACE, "com.hulu.plus", 0))
        for (d in 0L until 30L) for (id in apps) {
            assertNotEquals(Places.SPACE, Places.kind(Places.SHUFFLE, id, d))
            assertNotEquals(Places.SPACE, Places.kind(Places.DAILY, id, d))
        }
    }
}
