package com.firesky.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AlmanacTest {
    private val sun = Sun(7 * 60, 17 * 60)

    /** A day of hours from 3 PM, with the same cloud all through, except where [at] says otherwise. */
    private fun clouds(all: Int, vararg at: Pair<Int, Int>) = List(24) { i -> ((15 + i) % 24).let { h -> h to (at.toMap()[h] ?: all) } }

    private fun utc(y: Int, m: Int, d: Int, h: Int = 12) =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    @Test fun saysHowLongTheDayIsAndWhichWayItsGoing() {
        assertEquals("10 h 1 min of daylight · 2 min less tomorrow", Almanac.daylight(601, 599))
        assertEquals("14 h 30 min of daylight · 1 min more tomorrow", Almanac.daylight(870, 871))
        assertEquals("12 h 0 min of daylight", Almanac.daylight(720, 720))
        assertEquals("12 h 0 min of daylight", Almanac.daylight(720, null))
    }

    @Test fun namesTheMoonsPhase() {
        // New on 9 November 2026 and full on the 24th.
        assertEquals("New moon", Almanac.phase(utc(2026, 11, 9, 7)))
        assertEquals("Full moon", Almanac.phase(utc(2026, 11, 24, 15)))
        assertEquals("Waxing crescent", Almanac.phase(utc(2026, 11, 13)))
        assertEquals("Waxing gibbous", Almanac.phase(utc(2026, 11, 21)))
        assertEquals("Waning gibbous", Almanac.phase(utc(2026, 11, 28)))
        assertEquals("Waning crescent", Almanac.phase(utc(2026, 12, 5)))
    }

    @Test fun saysWhenTheMoonNextRisesOrSets() {
        val line = Almanac.moon(utc(2026, 11, 21, 22), 51.5, -.1)
        assertTrue(line, line.startsWith("Waxing gibbous · ") && (" sets " in line || " rises " in line))
    }

    @Test fun theNightIsOnlyItsDarkHours() {
        val night = Almanac.night(clouds(0), sun)
        // Dark from three quarters of an hour after sunset until the same before sunrise.
        assertEquals(18, night.first().first)
        assertEquals(5, night.last().first)
        assertEquals(12, night.size)
    }

    @Test fun saysWhenTheSkyIsClear() {
        assertEquals("Clear all night", Almanac.stars(clouds(10), sun, 10)!!.main)
        assertEquals("Cloudy all night", Almanac.stars(clouds(95), sun, 95)!!.main)
        assertEquals("Patchy cloud all night", Almanac.stars(clouds(60), sun, 60)!!.main)
        assertEquals("Clear from ${Prefs.hour(23)}", Almanac.stars(clouds(10, 18 to 90, 19 to 90, 20 to 80, 21 to 70, 22 to 50), sun, 90)!!.main)
        assertEquals("Clear until ${Prefs.hour(2)}", Almanac.stars(clouds(10, 2 to 70, 3 to 90, 4 to 90, 5 to 90), sun, 10)!!.main)
        assertEquals("Cloud now 42%", Almanac.stars(clouds(10), sun, 42)!!.sub)
    }

    @Test fun aNightThatNeverGetsDarkHasNoStars() {
        assertNull(Almanac.stars(clouds(0), Sun(60, 23 * 60 + 30), 0))
        assertNull(Almanac.stars(emptyList(), sun, 0))
    }

    @Test fun saysWhenTheStationNextPasses() {
        val now = System.currentTimeMillis()
        val pass = Orbit.Pass(now + 600_000, now + 900_000, 54.2, 315.0, 135.0)
        val soon = Almanac.station(listOf(pass), now, true)
        assertEquals("NW to SE · 54° high", soon.sub)
        assertEquals("Passing now", Almanac.station(listOf(pass), now + 700_000, true).main)
        assertEquals("No pass to see soon", Almanac.station(listOf(pass), now + 1_000_000, true).main)
        assertEquals("Not known yet", Almanac.station(emptyList(), now, false).main)
        assertTrue(Almanac.station(listOf(Orbit.Pass(now + 30 * 3_600_000L, now + 30 * 3_600_000L + 1, 30.0, 0.0, 90.0)), now, true).main.let { it.startsWith("Tomorrow ") || !it.contains("Tomorrow") })
    }

    @Test fun aShowerNearItsPeakComesBeforeThePlanets() {
        val n = Almanac.extra(utc(2026, 12, 14), 51.5, -.1, 0.0, Almanac.night(clouds(0), sun))
        assertEquals("Meteors", n.label)
        assertEquals("Geminids", n.main)
    }

    @Test fun namesThePlanetsUpTonightOrSaysThereAreNone() {
        val n = Almanac.extra(utc(2026, 9, 20), 51.5, -.1, 0.0, Almanac.night(clouds(0), sun))
        assertEquals("Planets", n.label)
        assertTrue(n.main, n.main == "None in view tonight" || Regex("(Mercury|Venus|Mars|Jupiter|Saturn)").containsMatchIn(n.main))
    }

    @Test fun northernLightsComeFirstWhenTheyreLikely() {
        val n = Almanac.extra(utc(2026, 12, 14), 64.1, -21.9, 7.0, Almanac.night(clouds(0), sun))
        assertEquals("Aurora", n.label)
        assertEquals("Northern lights possible", n.main)
    }

    /** The saved sample, with what the forecast now also asks for added to it. */
    private fun fuller(): String {
        val j = JSONObject(javaClass.classLoader!!.getResourceAsStream("open-meteo.json")!!.bufferedReader().readText())
        j.getJSONObject("current").put("relative_humidity_2m", 82).put("wind_gusts_10m", 23.6).put("wind_direction_10m", 225).put("snow_depth", 0.04)
        val hours = j.getJSONObject("hourly").getJSONArray("time").length()
        j.getJSONObject("hourly").put("cloud_cover", JSONArray(List(hours) { it * 5 }))
        j.getJSONObject("daily").put("uv_index_max", JSONArray(listOf(2.4, 3.0))).put("daylight_duration", JSONArray(listOf(36_480.0, 36_350.0)))
        return j.toString()
    }

    @Test fun readsTheSmallerReadings() {
        val m = Weather.parse(fuller(), """{"current":{"us_aqi":42}}""").more
        assertEquals(82, m.humidity)
        assertEquals(24, m.gusts)
        assertEquals(225, m.windAt)
        assertEquals(2.4, m.uv!!, 1e-9)
        assertEquals(608, m.daylight)
        assertEquals(606, m.daylightNext)
        assertEquals(42, m.air)
        assertEquals(18 to 0, m.clouds.first())
        assertEquals(if (Prefs.fahrenheit) "2 in" else "4 cm", m.snowLying)
    }

    @Test fun aForecastSavedBeforeTheseWereAskedForStillReads() {
        val wx = Weather.parse(javaClass.classLoader!!.getResourceAsStream("open-meteo.json")!!.bufferedReader().readText())
        assertNull(wx.more.humidity)
        assertNull(wx.more.air)
        assertNull(wx.more.snowLying)
        assertTrue(wx.more.clouds.isEmpty())
        assertEquals(wx.rainAt ?: "Dry for the next 12 hours", wx.rainLine)
    }

    @Test fun airThatCouldntBeReadLeavesTheRestAlone() {
        assertNull(Weather.parse(fuller(), "not json").more.air)
        assertNull(Weather.parse(fuller(), null).more.air)
        assertEquals(82, Weather.parse(fuller(), "{}").more.humidity)
    }

    @Test fun snowLyingJoinsTheDryLine() {
        val wx = Weather.Wx(0, 0, 0, 0, true, null, emptyList(), emptyList(), emptyList(), Sky.DEFAULT_SUN, more = Weather.More(snow = .12))
        assertEquals("Dry for the next 12 hours · ${if (Prefs.fahrenheit) "5 in" else "12 cm"} of snow lying", wx.rainLine)
    }

    @Test fun wordsForUvAndAir() {
        assertEquals("Low", Weather.uvWord(2.4)); assertEquals("Moderate", Weather.uvWord(3.0)); assertEquals("Extreme", Weather.uvWord(11.0))
        assertEquals("Good", Weather.airWord(50)); assertEquals("Moderate", Weather.airWord(51)); assertEquals("Hazardous", Weather.airWord(301))
    }

    @Test fun asksForTheNewReadingsAndTheAir() {
        val here = Place.Where(40.71, -74.01, "New York", "test")
        val url = Weather.url(here)
        for (k in listOf("relative_humidity_2m", "wind_gusts_10m", "wind_direction_10m", "snow_depth", "cloud_cover&forecast_hours=24", "uv_index_max", "daylight_duration")) assertTrue(k, k in url)
        assertTrue(Weather.airUrl(here).contains("current=us_aqi"))
    }
}
