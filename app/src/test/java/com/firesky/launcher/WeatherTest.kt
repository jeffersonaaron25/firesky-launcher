package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    /** A sample Open-Meteo answer for an evening in early November. */
    private val text = javaClass.classLoader!!.getResourceAsStream("open-meteo.json")!!.bufferedReader().readText()
    private val wx = Weather.parse(text)

    @Test fun asksForTheUnitsYouChose() {
        val url = Weather.url(Place.Where(40.71, -74.01, "New York", "test"))
        assertTrue(url.contains("temperature_unit=" + if (Prefs.fahrenheit) "fahrenheit" else "celsius"))
        assertTrue(url.contains("wind_speed_unit=" + if (Prefs.mph) "mph" else "kmh"))
    }

    @Test fun readsTheCurrentConditions() {
        assertEquals(8, wx.temp)
        assertEquals(4, wx.feels)
        assertEquals(12, wx.wind)
        assertEquals(false, wx.isDay)
    }

    @Test fun readsTodaysRealSunTimes() {
        assertEquals(6 * 60 + 59, wx.sun.rise)
        assertEquals(17 * 60 + 7, wx.sun.set)
    }

    @Test fun readsTheForecasts() {
        assertEquals(8, wx.rain.size)
        assertEquals(12, wx.hours.size)
        assertEquals(7, wx.days.size)
        assertEquals("2026-11-06", wx.days[0].date)
    }

    private val CLOUD = 3
    private val DRIZZLE = 51
    private val SNOW = 71
    private fun hours(vararg codes: Int) = codes.mapIndexed { i, c -> Weather.Hour(20 + i, 5, c, 0) }
    private val dryHours = hours(CLOUD, CLOUD, CLOUD)

    @Test fun saysWhenRainIsDue() {
        assertNull(Weather.next(false, CLOUD, listOf(0.0, 0.0), dryHours))
        assertEquals("Raining now", Weather.next(false, DRIZZLE, listOf(.2, 0.0), dryHours))
        assertEquals("Raining now", Weather.next(true, DRIZZLE, listOf(0.0, 0.0), dryHours))
        assertEquals("Rain in 45 min", Weather.next(false, CLOUD, listOf(0.0, 0.0, 0.0, .3), dryHours))
    }

    /** The evening this was reported: a trace at 9:45, drizzle at 10, and only 3 AM was likely. */
    @Test fun aLightShowerStillCounts() {
        val rain = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, .1, 0.0)
        assertEquals("Rain in 90 min", Weather.next(false, CLOUD, rain, hours(CLOUD, CLOUD, DRIZZLE, SNOW)))
    }

    @Test fun beyondTheShortForecastItNamesTheHour() {
        assertEquals("Rain at 10 PM", Weather.next(false, CLOUD, List(8) { 0.0 }, hours(CLOUD, CLOUD, DRIZZLE)))
        assertEquals("Rain at 12 AM", Weather.next(false, CLOUD, List(8) { 0.0 }, listOf(Weather.Hour(23, 5, CLOUD, 0), Weather.Hour(0, 5, DRIZZLE, 0))))
    }

    @Test fun snowIsCalledSnow() {
        assertEquals("Snow at 10 PM", Weather.next(false, CLOUD, List(8) { 0.0 }, hours(CLOUD, CLOUD, SNOW)))
        assertEquals("Snow in 30 min", Weather.next(false, CLOUD, listOf(0.0, 0.0, .4), hours(CLOUD, SNOW)))
        assertEquals("Snowing now", Weather.next(true, SNOW, listOf(.4), hours(SNOW)))
    }

    @Test fun theHourUnderWayIsntTheNextRain() {
        assertNull(Weather.next(false, CLOUD, List(8) { 0.0 }, hours(DRIZZLE, CLOUD, CLOUD)))
    }

    @Test fun theChipAndThePanelSayTheSameThing() {
        assertEquals(wx.rainAt ?: "Dry for the next 12 hours", wx.rainLine)
        val dry = Weather.Wx(0, 0, 0, 0, true, null, emptyList(), emptyList(), emptyList(), Sky.DEFAULT_SUN)
        assertEquals("Dry for the next 12 hours", dry.rainLine)
    }
}
