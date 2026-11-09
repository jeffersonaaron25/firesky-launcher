package com.firesky.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The TV's sky against Astronomy Engine and satellite.js, the libraries behind the approved mock,
 * at five moments in New York, Bengaluru and Sydney.
 */
class AstroTest {
    private val j = JSONObject(javaClass.classLoader!!.getResourceAsStream("sky-expect.json")!!.bufferedReader().readText())
    private val cases = j.getJSONArray("cases").let { a -> List(a.length()) { a.getJSONObject(it) } }

    private fun close(what: String, want: org.json.JSONArray, alt: Double, az: Double, tol: Double) {
        val da = abs(alt - want.getDouble(0))
        var dz = abs(az - want.getDouble(1)) % 360; if (dz > 180) dz = 360 - dz
        // Azimuth matters less near the zenith, where a degree of it is a short distance.
        val dzSky = dz * Math.cos(Math.toRadians(alt))
        assertTrue("$what alt $alt vs ${want.getDouble(0)}", da < tol)
        assertTrue("$what az $az vs ${want.getDouble(1)}", dzSky < tol)
    }

    @Test fun sunMoonAndPlanetsAreWhereTheMockPutsThem() {
        for (c in cases) {
            val t = c.getLong("t"); val lat = c.getDouble("lat"); val lon = c.getDouble("lon")
            val lst = Astro.lst(t, lon)
            Astro.horizon(Astro.sun(t), lst, lat).let { close("sun $t", c.getJSONArray("sun"), it.alt, it.az, .15) }
            Astro.moonFromHere(Astro.horizon(Astro.moon(t).eq, lst, lat)).let { close("moon $t", c.getJSONArray("moon"), it.alt, it.az, .5) }
            assertEquals("moon lit $t", c.getDouble("lit"), Astro.moonLit(t), .02)
            val pl = c.getJSONObject("pl")
            for (p in Astro.planets(t)) {
                val want = pl.getJSONArray(p.name)
                val h = Astro.horizon(p.eq, lst, lat)
                close("${p.name} $t", want, h.alt, h.az, .5)
                // Saturn's rings brighten it as they open, which this leaves out: under a magnitude.
                assertEquals("${p.name} mag $t", want.getDouble(2), p.mag, if (p.name == "Saturn") .9 else .6)
            }
        }
    }

    @Test fun theSpaceStationIsWhereSatelliteJsPutsIt() {
        val tle = j.getJSONArray("tle")
        val o = Orbit(tle.getString(0), tle.getString(1))
        for (c in cases) {
            val s = o.seen(c.getLong("t"), c.getDouble("lat"), c.getDouble("lon"))!!
            close("iss ${c.getLong("t")}", c.getJSONArray("iss"), s.alt, s.az, .05)
        }
    }

    /** A pass straight over Paris on the evening of 17 November, in the dark and in sunlight. */
    @Test fun findsTheOverheadPassOverParis() {
        val tle = j.getJSONArray("tle")
        val o = Orbit(tle.getString(0), tle.getString(1))
        val from = 1794937040000L - 3_600_000; val to = from + 2 * 3_600_000
        val p = o.passes(from, to, 48.86, 2.35).maxByOrNull { it.maxAlt }!!
        assertTrue("max ${p.maxAlt}", p.maxAlt > 80)
    }
}
