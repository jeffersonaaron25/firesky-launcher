package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceTest {
    @Test fun readsEachService() {
        val g = Place.parseGeojs("""{"city":"Springfield","latitude":"39.7817","longitude":"-89.6501","timezone":"America/Chicago"}""")!!
        assertEquals(39.7817, g.lat, 1e-6); assertEquals(-89.6501, g.lon, 1e-6); assertEquals("Springfield", g.city)
        val w = Place.parseIpwho("""{"success":true,"city":"Chatham","latitude":39.6761,"longitude":-89.7043}""")!!
        assertEquals(39.68, w.lat, .01)
        assertNull(Place.parseIpwho("""{"success":false,"message":"rate limit"}"""))
    }

    @Test fun aTimeZoneIsEnoughToFindTheSky() {
        assertEquals("Mumbai", Place.fromZone("Asia/Kolkata").city)
        // A zone it doesn't know still lands at the right longitude for its hour.
        val tokyoish = Place.fromZone("Asia/Seoul")
        assertTrue(tokyoish.lon in 120.0..140.0)
        assertTrue(Place.fromZone("Australia/Perth").lat < 0)
    }
}
