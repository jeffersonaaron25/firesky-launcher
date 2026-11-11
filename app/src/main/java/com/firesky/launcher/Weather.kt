package com.firesky.launcher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Open-Meteo: free, no key, asked directly from the TV, for wherever [Place] says the TV is. */
object Weather {
    private const val MAX_AGE_MS = 10 * 60_000L
    private const val AIR_AGE_MS = 3 * 3_600_000L
    /** Hours asked for, to know the cloud through the coming night, and how many of them the panel lists. */
    private const val AHEAD = 24
    private const val SHOWN = 12
    /** Goes up whenever the forecast asks the service for more than it used to. */
    private const val ASKED = 2

    class Hour(val hour: Int, val temp: Int, val code: Int, val rainChance: Int)
    class Day(val date: String, val low: Int, val high: Int, val code: Int, val rainChance: Int)
    class Wx(
        val temp: Int, val feels: Int, val wind: Int, val code: Int, val isDay: Boolean, val rainAt: String?,
        val rain: List<Double>, val hours: List<Hour>, val days: List<Day>, val sun: Sun,
        /** How much of the sky is cloud, 0 to 100. */
        val cloud: Int = 0,
        /** Where the wind at cloud height comes from, in degrees: the way weather is moving in from. */
        val windFrom: Int? = null,
        val more: More = More(),
    ) {
        val kind get() = Sky.kindOf(code)
        val condition get() = cond(code)

        /** The wind in km/h, whichever units it was asked for in. */
        val windKmh get() = if (Prefs.mph) wind * 1.609f else wind.toFloat()

        /** Thunder with it, which the backdrop shows as a darker sky and, left alone, lightning. */
        val storm get() = code in 95..99

        /** Whether the next two hours have any rain to chart. */
        val rainSoon get() = rain.any { it > 0 }

        /** The first line of the panel: the same words as the chip on the home screen. */
        val rainLine get() = rainAt ?: ("Dry for the next 12 hours" + (more.snowLying?.let { " · $it of snow lying" } ?: ""))
    }

    /**
     * The rest of what the panel shows. Each can be missing: a forecast saved by an older FireSky
     * has none of them, and the air quality comes from a second request that can fail on its own.
     */
    class More(
        val humidity: Int? = null,
        val gusts: Int? = null,
        /** Where the wind at the ground comes from, in degrees. */
        val windAt: Int? = null,
        /** The highest the UV index gets today. */
        val uv: Double? = null,
        /** Snow on the ground, in metres. */
        val snow: Double? = null,
        /** Minutes of daylight today and tomorrow. */
        val daylight: Int? = null, val daylightNext: Int? = null,
        /** How much of the sky is cloud each hour from now, as hour of the day to percent, for a day ahead. */
        val clouds: List<Pair<Int, Int>> = emptyList(),
        /** The US air quality index. */
        val air: Int? = null,
    ) {
        /** "4 cm" or "2 in", once there's enough to see. */
        val snowLying: String? get() = snow?.takeIf { it >= .01 }?.let {
            if (Prefs.fahrenheit) "${(it * 39.37).roundToInt().coerceAtLeast(1)} in" else "${(it * 100).roundToInt()} cm"
        }
    }

    fun uvWord(uv: Double) = when { uv < 3 -> "Low"; uv < 6 -> "Moderate"; uv < 8 -> "High"; uv < 11 -> "Very high"; else -> "Extreme" }

    fun airWord(aqi: Int) = when { aqi <= 50 -> "Good"; aqi <= 100 -> "Moderate"; aqi <= 150 -> "Poor"; aqi <= 200 -> "Unhealthy"; aqi <= 300 -> "Very poor"; else -> "Hazardous" }

    fun hourName(h: Int) = Prefs.hour(h)

    private fun wet(code: Int) = Sky.kindOf(code).let { it == Sky.RAIN || it == Sky.SNOW }
    private fun snow(code: Int) = Sky.kindOf(code) == Sky.SNOW

    /**
     * The one answer to when rain is next, so the home screen and the panel can't disagree: the
     * 15-minute forecast as far as it reaches, then the first hour the panel shows as wet.
     */
    internal fun next(raining: Boolean, code: Int, rain: List<Double>, hours: List<Hour>): String? {
        val first = rain.indexOfFirst { it > 0 }
        // The first entry is the hour already under way.
        val hour = hours.drop(1).firstOrNull { wet(it.code) }
        val word = if (hour != null && snow(hour.code)) "Snow" else "Rain"
        return when {
            raining || first == 0 -> if (snow(code)) "Snowing now" else "Raining now"
            first > 0 -> "$word in ${first * 15} min"
            hour != null -> "$word at ${hourName(hour.hour)}"
            else -> null
        }
    }

    fun cond(c: Int) = when (c) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        in 4..48 -> "Fog"
        in 49..57 -> "Drizzle"
        in 58..67 -> "Rain"
        in 68..77 -> "Snow"
        in 78..82 -> "Showers"
        in 83..86 -> "Snow showers"
        else -> "Storms"
    }

    internal fun url(w: Place.Where) = "https://api.open-meteo.com/v1/forecast?latitude=${w.lat}&longitude=${w.lon}" +
        "&current=temperature_2m,apparent_temperature,weather_code,is_day,precipitation,wind_speed_10m,cloud_cover,wind_direction_700hPa" +
        ",relative_humidity_2m,wind_gusts_10m,wind_direction_10m,snow_depth" +
        "&minutely_15=precipitation&forecast_minutely_15=8" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,cloud_cover&forecast_hours=$AHEAD" +
        "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,sunrise,sunset,uv_index_max,daylight_duration&forecast_days=7" +
        "&temperature_unit=${if (Prefs.fahrenheit) "fahrenheit" else "celsius"}&wind_speed_unit=${if (Prefs.mph) "mph" else "kmh"}&timezone=auto"

    internal fun airUrl(w: Place.Where) =
        "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${w.lat}&longitude=${w.lon}&current=us_aqi"

    private fun minutes(iso: String) = iso.substring(11, 13).toInt() * 60 + iso.substring(14, 16).toInt()

    /** [air] is the air quality service's answer, when there is one. */
    fun parse(text: String, air: String? = null): Wx {
        val j = JSONObject(text)
        val cur = j.getJSONObject("current")
        val hourly = j.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val prob = hourly.getJSONArray("precipitation_probability")
        val m15 = j.getJSONObject("minutely_15").getJSONArray("precipitation")
        val rain = List(m15.length()) { m15.optDouble(it, 0.0) }
        // The panel's row is the next 12 hours. The cloud for the whole night comes from the rest.
        val hours = List(minOf(SHOWN, times.length())) { Hour(times.getString(it).substring(11, 13).toInt(), temps.getDouble(it).roundToInt(), codes.optInt(it), prob.optInt(it, 0)) }
        val daily = j.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val hi = daily.getJSONArray("temperature_2m_max")
        val lo = daily.getJSONArray("temperature_2m_min")
        val dc = daily.getJSONArray("weather_code")
        val dp = daily.getJSONArray("precipitation_probability_max")
        return Wx(
            cur.getDouble("temperature_2m").roundToInt(),
            cur.getDouble("apparent_temperature").roundToInt(),
            cur.getDouble("wind_speed_10m").roundToInt(),
            cur.getInt("weather_code"), cur.getInt("is_day") == 1,
            next(cur.optDouble("precipitation", 0.0) > .1, cur.getInt("weather_code"), rain, hours),
            rain, hours,
            List(dates.length()) { Day(dates.getString(it), lo.getDouble(it).roundToInt(), hi.getDouble(it).roundToInt(), dc.optInt(it), dp.optInt(it, 0)) },
            Sun(minutes(daily.getJSONArray("sunrise").getString(0)), minutes(daily.getJSONArray("sunset").getString(0))),
            cur.optInt("cloud_cover", 0),
            if (cur.has("wind_direction_700hPa") && !cur.isNull("wind_direction_700hPa")) cur.getInt("wind_direction_700hPa") else null,
            more(cur, hourly, daily, air),
        )
    }

    private fun num(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() } else null
    private fun num(o: JSONObject, k: String, i: Int): Double? = o.optJSONArray(k)?.takeIf { i < it.length() && !it.isNull(i) }?.optDouble(i)?.takeIf { !it.isNaN() }

    private fun more(cur: JSONObject, hourly: JSONObject, daily: JSONObject, air: String?): More {
        val times = hourly.getJSONArray("time")
        val cloud = hourly.optJSONArray("cloud_cover")
        return More(
            num(cur, "relative_humidity_2m")?.roundToInt(),
            num(cur, "wind_gusts_10m")?.roundToInt(),
            num(cur, "wind_direction_10m")?.roundToInt(),
            num(daily, "uv_index_max", 0),
            num(cur, "snow_depth"),
            num(daily, "daylight_duration", 0)?.let { (it / 60).roundToInt() },
            num(daily, "daylight_duration", 1)?.let { (it / 60).roundToInt() },
            if (cloud == null) emptyList() else List(minOf(times.length(), cloud.length())) { times.getString(it).substring(11, 13).toInt() to cloud.optInt(it, 0) },
            air?.let { runCatching { num(JSONObject(it).getJSONObject("current"), "us_aqi")?.roundToInt() }.getOrNull() },
        )
    }

    /** Named for the units, so a forecast saved in other units is never shown. */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(
        "weather_" + (if (Prefs.fahrenheit) "fahrenheit" else "celsius") + (if (Prefs.mph) "" else "_kmh"), Context.MODE_PRIVATE,
    )

    /** The last forecast, so the screen has weather the instant it opens. */
    fun cached(ctx: Context): Wx? = prefs(ctx).getString("json", null)?.let { runCatching { parse(it, air(ctx)) }.getOrNull() }

    /** The air quality as last read, while it's recent enough to still be about now. */
    private fun air(ctx: Context) = prefs(ctx).takeIf { System.currentTimeMillis() - it.getLong("airAt", 0) < AIR_AGE_MS }?.getString("air", null)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return c.inputStream.use { it.bufferedReader().readText() }
    }

    /** How long ago the saved forecast was fetched. */
    fun age(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("at", 0)

    /** Old, or for somewhere the TV no longer is. */
    fun stale(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("at", 0) > MAX_AGE_MS ||
        // A forecast saved before more was asked for is replaced at once, not when it ages.
        prefs(ctx).getInt("asked", 1) < ASKED ||
        prefs(ctx).getString("where", null) != Place.get(ctx).key

    fun fetch(ctx: Context): Wx? = runCatching {
        val where = Place.get(ctx)
        val text = get(url(where))
        // The air is a second service. Without it the forecast is still shown, with the last reading if that's recent.
        runCatching { get(airUrl(where)) }.getOrNull()?.let {
            prefs(ctx).edit().putString("air", it).putLong("airAt", System.currentTimeMillis()).apply()
        }
        val wx = parse(text, air(ctx))
        prefs(ctx).edit().putString("json", text).putLong("at", System.currentTimeMillis()).putString("where", where.key).putInt("asked", ASKED).apply()
        wx
    }.getOrNull()
}
