package com.firesky.launcher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Open-Meteo: free, no key, asked directly from the TV, for wherever [Place] says the TV is. */
object Weather {
    private const val MAX_AGE_MS = 10 * 60_000L

    class Hour(val hour: Int, val temp: Int, val code: Int, val rainChance: Int)
    class Day(val date: String, val low: Int, val high: Int, val code: Int, val rainChance: Int)
    class Wx(
        val temp: Int, val feels: Int, val wind: Int, val code: Int, val isDay: Boolean, val rainAt: String?,
        val rain: List<Double>, val hours: List<Hour>, val days: List<Day>, val sun: Sun,
        /** How much of the sky is cloud, 0 to 100. */
        val cloud: Int = 0,
        /** Where the wind at cloud height comes from, in degrees: the way weather is moving in from. */
        val windFrom: Int? = null,
    ) {
        val kind get() = Sky.kindOf(code)
        val condition get() = cond(code)

        /** Whether the next two hours have any rain to chart. */
        val rainSoon get() = rain.any { it > 0 }

        /** The first line of the panel: the same words as the chip on the home screen. */
        val rainLine get() = rainAt ?: "Dry for the next 12 hours"
    }

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
        "&minutely_15=precipitation&forecast_minutely_15=8" +
        "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=12" +
        "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,sunrise,sunset&forecast_days=7" +
        "&temperature_unit=${if (Prefs.fahrenheit) "fahrenheit" else "celsius"}&wind_speed_unit=${if (Prefs.mph) "mph" else "kmh"}&timezone=auto"

    private fun minutes(iso: String) = iso.substring(11, 13).toInt() * 60 + iso.substring(14, 16).toInt()

    fun parse(text: String): Wx {
        val j = JSONObject(text)
        val cur = j.getJSONObject("current")
        val hourly = j.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val prob = hourly.getJSONArray("precipitation_probability")
        val m15 = j.getJSONObject("minutely_15").getJSONArray("precipitation")
        val rain = List(m15.length()) { m15.optDouble(it, 0.0) }
        val hours = List(times.length()) { Hour(times.getString(it).substring(11, 13).toInt(), temps.getDouble(it).roundToInt(), codes.optInt(it), prob.optInt(it, 0)) }
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
        )
    }

    /** Named for the units, so a forecast saved in other units is never shown. */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(
        "weather_" + (if (Prefs.fahrenheit) "fahrenheit" else "celsius") + (if (Prefs.mph) "" else "_kmh"), Context.MODE_PRIVATE,
    )

    /** The last forecast, so the screen has weather the instant it opens. */
    fun cached(ctx: Context): Wx? = prefs(ctx).getString("json", null)?.let { runCatching { parse(it) }.getOrNull() }

    /** Old, or for somewhere the TV no longer is. */
    fun stale(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("at", 0) > MAX_AGE_MS ||
        prefs(ctx).getString("where", null) != Place.get(ctx).key

    fun fetch(ctx: Context): Wx? = runCatching {
        val where = Place.get(ctx)
        val c = URL(url(where)).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        val text = c.inputStream.use { it.bufferedReader().readText() }
        val wx = parse(text)
        prefs(ctx).edit().putString("json", text).putLong("at", System.currentTimeMillis()).putString("where", where.key).apply()
        wx
    }.getOrNull()
}
