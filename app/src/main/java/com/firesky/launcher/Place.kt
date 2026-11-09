package com.firesky.launcher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone

/**
 * Where this TV is, so its sky and weather are its own and a friend's TV needs no setup. Fire TV
 * has no GPS, so it asks a free service which city its internet connection is in, and failing that
 * guesses from its time zone. Kept for a week, so a TV that moves house notices within days.
 */
object Place {
    class Where(val lat: Double, val lon: Double, val city: String, val from: String) {
        /** Same place for a forecast: within a few kilometres. */
        val key get() = "%.2f,%.2f".format(java.util.Locale.US, lat, lon)
    }

    private const val MAX_AGE_MS = 7 * 86_400_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("place", Context.MODE_PRIVATE)

    @Volatile private var known: Where? = null

    /** What's known now, never waiting on the network. */
    fun get(ctx: Context): Where = known ?: saved(ctx)?.also { known = it } ?: fromZone(TimeZone.getDefault().id).also { known = it }

    fun stale(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("at", 0) > MAX_AGE_MS

    /** Asks the network, off the main thread. Keeps what it had if no service answers. */
    fun refresh(ctx: Context): Where? {
        val w = listOf(
            { parseGeojs(get("https://get.geojs.io/v1/ip/geo.json")) },
            { parseIpwho(get("https://ipwho.is/")) },
        ).firstNotNullOfOrNull { runCatching { it() }.getOrNull() } ?: return null
        prefs(ctx).edit().putString("lat", w.lat.toString()).putString("lon", w.lon.toString())
            .putString("city", w.city).putLong("at", System.currentTimeMillis()).apply()
        known = w
        return w
    }

    private fun saved(ctx: Context): Where? {
        val p = prefs(ctx)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        return Where(lat, lon, p.getString("city", "") ?: "", "saved")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "FireSky")
        return c.inputStream.use { it.bufferedReader().readText() }
    }

    internal fun parseGeojs(text: String): Where? {
        val j = JSONObject(text)
        val lat = j.optString("latitude").toDoubleOrNull() ?: return null
        val lon = j.optString("longitude").toDoubleOrNull() ?: return null
        return Where(lat, lon, j.optString("city"), "geojs")
    }

    internal fun parseIpwho(text: String): Where? {
        val j = JSONObject(text)
        if (!j.optBoolean("success", false)) return null
        return Where(j.getDouble("latitude"), j.getDouble("longitude"), j.optString("city"), "ipwho")
    }

    /** A city in the TV's own time zone: wrong by a few hundred kilometres at worst, which a sky forgives. */
    internal fun fromZone(tz: String): Where {
        ZONES[tz]?.let { (lat, lon, city) -> return Where(lat, lon, city, "zone") }
        // Unknown zone: its offset gives the longitude, and a middling latitude for its hemisphere.
        val off = TimeZone.getTimeZone(tz).rawOffset / 3_600_000.0
        val south = tz.startsWith("Australia") || tz.startsWith("Pacific/Auckland") || tz.startsWith("America/Argentina") || tz.startsWith("America/Sao_Paulo")
        return Where(if (south) -30.0 else 35.0, off * 15, "", "zone")
    }

    private val ZONES = mapOf(
        "America/New_York" to Triple(40.71, -74.01, "New York"),
        "America/Detroit" to Triple(42.33, -83.05, "Detroit"),
        "America/Toronto" to Triple(43.65, -79.38, "Toronto"),
        "America/Chicago" to Triple(41.88, -87.63, "Chicago"),
        "America/Denver" to Triple(39.74, -104.99, "Denver"),
        "America/Phoenix" to Triple(33.45, -112.07, "Phoenix"),
        "America/Los_Angeles" to Triple(34.05, -118.24, "Los Angeles"),
        "America/Vancouver" to Triple(49.28, -123.12, "Vancouver"),
        "America/Anchorage" to Triple(61.22, -149.9, "Anchorage"),
        "Pacific/Honolulu" to Triple(21.31, -157.86, "Honolulu"),
        "America/Mexico_City" to Triple(19.43, -99.13, "Mexico City"),
        "America/Sao_Paulo" to Triple(-23.55, -46.63, "São Paulo"),
        "Europe/London" to Triple(51.51, -0.13, "London"),
        "Europe/Dublin" to Triple(53.35, -6.26, "Dublin"),
        "Europe/Paris" to Triple(48.86, 2.35, "Paris"),
        "Europe/Berlin" to Triple(52.52, 13.4, "Berlin"),
        "Europe/Madrid" to Triple(40.42, -3.7, "Madrid"),
        "Europe/Rome" to Triple(41.9, 12.5, "Rome"),
        "Europe/Amsterdam" to Triple(52.37, 4.9, "Amsterdam"),
        "Europe/Stockholm" to Triple(59.33, 18.07, "Stockholm"),
        "Atlantic/Reykjavik" to Triple(64.15, -21.94, "Reykjavík"),
        "Asia/Dubai" to Triple(25.2, 55.27, "Dubai"),
        "Asia/Kolkata" to Triple(19.08, 72.88, "Mumbai"),
        "Asia/Calcutta" to Triple(19.08, 72.88, "Mumbai"),
        "Asia/Karachi" to Triple(24.86, 67.0, "Karachi"),
        "Asia/Dhaka" to Triple(23.81, 90.41, "Dhaka"),
        "Asia/Singapore" to Triple(1.35, 103.82, "Singapore"),
        "Asia/Tokyo" to Triple(35.68, 139.69, "Tokyo"),
        "Asia/Shanghai" to Triple(31.23, 121.47, "Shanghai"),
        "Asia/Hong_Kong" to Triple(22.32, 114.17, "Hong Kong"),
        "Australia/Sydney" to Triple(-33.87, 151.21, "Sydney"),
        "Australia/Melbourne" to Triple(-37.81, 144.96, "Melbourne"),
        "Pacific/Auckland" to Triple(-36.85, 174.76, "Auckland"),
    )
}
