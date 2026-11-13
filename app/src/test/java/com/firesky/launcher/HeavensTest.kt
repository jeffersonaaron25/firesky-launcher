package com.firesky.launcher

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HeavensTest {
    private val cat by lazy { Heavens.catalog(ApplicationProvider.getApplicationContext()) }
    private fun deg(r: Float) = r * 180 / PI.toFloat()

    @Test fun theCatalogueHasTheFainterStarsNamesAndTheDeepSky() {
        assertEquals(5044, cat.stars.size / Heavens.STAR_STRIDE)
        // Brightest first, and none fainter than 6.
        assertTrue((0 until cat.stars.size / 6).all { cat.stars[it * 6 + 2] <= 6f })
        assertEquals(cat.names.size * 2, cat.namePos.size)
        assertTrue(cat.names.containsAll(listOf("Orion", "Cassiopeia", "Ursa Major", "Crux")))
        assertEquals(20, cat.deep.size / Heavens.DEEP_STRIDE)
    }

    /** It once stood about 70 degrees out, from a rotation applied the wrong way round. */
    @Test fun theMilkyWayIsBrightestInSagittarius() {
        val m = cat.milky
        val n = m.size / Heavens.STAR_STRIDE
        // The warmest light, and among it the brightest.
        val best = (0 until n).sortedByDescending { m[it * 6 + 3] + .02f * m[it * 6 + 2] }
        for (i in best.take(5)) {
            val ra = deg(m[i * 6]); val dec = deg(m[i * 6 + 1])
            assertTrue("ra $ra dec $dec", ra in 255f..285f && dec in -40f..-15f)
        }
        // And it runs through Cygnus and Cassiopeia, nowhere near Ursa Major.
        fun near(raD: Float, decD: Float, r: Float) = (0 until n).any { kotlin.math.abs(deg(m[it * 6]) - raD) < r && kotlin.math.abs(deg(m[it * 6 + 1]) - decD) < r }
        assertTrue(near(305f, 40f, 4f))
        assertTrue(near(15f, 60f, 4f))
        assertFalse(near(165f, 56f, 8f))
    }

    @Test fun spaceIsAlwaysNightWithNamesAndNoSun() {
        val f = Heavens.Frame()
        // Noon in mid November, at 40 north, in New York.
        val noon = 1794502800000L
        Heavens.frame(f, noon, 40.71, -74.01, 540.0, 100, Heavens.View(), 0f, space = true, facing = 90.0, names = cat)
        assertTrue(f.space)
        assertEquals(1f, f.dark)
        assertEquals(1f, f.clear)
        assertEquals(6.2f, f.lim)
        assertFalse(f.sunIn)
        assertEquals(cat.names.size * 3, f.labels.size)
        assertTrue((0 until cat.names.size).any { f.labels[it * 3 + 2] > .5f })
        // The same moment from the ground is daylight under cloud.
        val g = Heavens.Frame()
        Heavens.frame(g, noon, 40.71, -74.01, 540.0, 100, Heavens.View(), 0f)
        assertEquals(0f, g.dark)
        assertTrue(g.clear < .1f)
        assertFalse(g.space)
    }
}
