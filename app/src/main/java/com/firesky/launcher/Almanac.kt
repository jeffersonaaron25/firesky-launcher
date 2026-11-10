package com.firesky.launcher

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The forecast panel's words about the sky: the length of the day, the moon, and what tonight has
 * to show. All of it is worked out on the TV from what the backdrop already knows.
 */
object Almanac {
    /** One thing worth knowing about tonight: a small heading, the news, and a quieter line under it. */
    class Note(val label: String, val main: String, val sub: String)

    /** "10 h 1 min of daylight · 2 min less tomorrow". */
    fun daylight(today: Int, tomorrow: Int?): String {
        val len = "${today / 60} h ${today % 60} min of daylight"
        val d = (tomorrow ?: return len) - today
        return len + when {
            d == 0 -> ""
            else -> " · ${abs(d)} min ${if (d > 0) "more" else "less"} tomorrow"
        }
    }

    fun phase(ms: Long): String {
        val lit = Astro.moonLit(ms); val waxing = Astro.moonWaxing(ms)
        return when {
            lit < .03 -> "New moon"
            lit > .97 -> "Full moon"
            lit in .45..(.55) -> if (waxing) "First quarter" else "Last quarter"
            lit < .5 -> if (waxing) "Waxing crescent" else "Waning crescent"
            else -> if (waxing) "Waxing gibbous" else "Waning gibbous"
        }
    }

    private fun moonAlt(ms: Long, lat: Double, lon: Double) =
        Astro.moonFromHere(Astro.horizon(Astro.moon(ms).eq, Astro.lst(ms, lon), lat)).alt

    private fun clock(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return Prefs.timeOfDay(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** "Waning gibbous · sets 3:12 AM", or when it next rises if it's down. */
    fun moon(ms: Long, lat: Double, lon: Double): String {
        val up = moonAlt(ms, lat, lon) > 0
        var t = ms
        // The moon crosses the horizon about twice a day; near the poles it can stay one side for days.
        while (t < ms + 26 * 3_600_000L) {
            t += STEP_MS
            if ((moonAlt(t, lat, lon) > 0) != up) return "${phase(ms)} · ${if (up) "sets" else "rises"} ${clock(t - STEP_MS / 2)}"
        }
        return "${phase(ms)} · ${if (up) "up all night" else "below the horizon"}"
    }

    private fun dark(hour: Int, sun: Sun) = hour * 60 >= sun.set + TWILIGHT_MIN || hour * 60 + 60 <= sun.rise - TWILIGHT_MIN

    /** The cloud for each dark hour of the night under way or coming, in order. */
    internal fun night(clouds: List<Pair<Int, Int>>, sun: Sun): List<Pair<Int, Int>> =
        clouds.dropWhile { !dark(it.first, sun) }.takeWhile { dark(it.first, sun) }

    /** Whether tonight is one for looking up, and when. Null where the night never gets dark. */
    fun stars(clouds: List<Pair<Int, Int>>, sun: Sun, cloudNow: Int): Note? {
        val hours = night(clouds, sun)
        if (hours.isEmpty()) return null
        val clear = hours.map { it.second <= CLEAR_BELOW }
        val main = when {
            clear.all { it } -> "Clear all night"
            clear.none { it } -> if (hours.all { it.second >= 80 }) "Cloudy all night" else "Patchy cloud all night"
            clear[0] -> "Clear until ${Prefs.hour(hours[clear.indexOf(false)].first)}"
            else -> "Clear from ${Prefs.hour(hours[clear.indexOf(true)].first)}"
        }
        return Note("Stars", main, "Cloud now $cloudNow%")
    }

    /** The next time the space station can be seen from here. */
    fun station(passes: List<Orbit.Pass>, ms: Long, known: Boolean): Note {
        val p = passes.firstOrNull { it.end >= ms }
            ?: return Note("Space station", if (known) "No pass to see soon" else "Not known yet", if (known) "Nothing in the next day" else "Its orbit is still loading")
        val path = "${Heavens.compass(p.fromAz)} to ${Heavens.compass(p.toAz)} · ${p.maxAlt.roundToInt()}° high"
        if (ms >= p.start) return Note("Space station", "Passing now", path)
        val now = Calendar.getInstance().apply { timeInMillis = ms }
        val at = Calendar.getInstance().apply { timeInMillis = p.start }
        val day = if (at.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) "" else "Tomorrow "
        return Note("Space station", day + clock(p.start), path)
    }

    /** Northern lights when they're likely, a meteor shower near its peak, or else the bright planets up tonight. */
    fun extra(ms: Long, lat: Double, lon: Double, kp: Double, hours: List<Pair<Int, Int>>): Note {
        if (Heavens.auroraChance(lat, lon, kp) > .25) {
            return Note("Aurora", if (lat >= 0) "Northern lights possible" else "Southern lights possible", "Look ${if (lat >= 0) "north" else "south"} once it's dark")
        }
        Heavens.SHOWERS.map { it to Heavens.rate(it, ms) }.filter { it.second >= 5 }.maxByOrNull { it.second }?.let { (s, rate) ->
            return Note("Meteors", s.name, "About ${rate.roundToInt()} an hour under a dark sky")
        }
        // Each dark hour of the night, as a moment, to see which planets stand clear of the horizon in it.
        val start = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.MINUTE, 30) }
        val seen = LinkedHashMap<String, Int>()
        val mags = HashMap<String, Double>()
        var offset = -1
        for ((i, h) in hours.withIndex()) {
            // The hours run on from the one under way, so each is an hour after the last.
            if (offset < 0) offset = ((h.first - start.get(Calendar.HOUR_OF_DAY)) + 24) % 24
            val t = start.timeInMillis + (offset + i) * 3_600_000L
            val lst = Astro.lst(t, lon)
            for (p in Astro.planets(t)) {
                if (p.mag < BRIGHT_MAG && Astro.horizon(p.eq, lst, lat).alt > 10 && p.name !in seen) { seen[p.name] = h.first; mags[p.name] = p.mag }
            }
        }
        if (seen.isEmpty()) return Note("Planets", "None in view tonight", "They're up by day for now")
        // The two brightest are named, which is what fits and what catches the eye.
        val names = seen.keys.sortedBy { mags.getValue(it) }.take(2)
        val first = names.minOf { n -> hours.indexOfFirst { it.first == seen.getValue(n) } }
        return Note("Planets", names.joinToString(" and "), if (first == 0) "Up as it gets dark" else "From ${Prefs.hour(hours[first].first)}")
    }

    /** Everything for the panel's "Tonight's sky" row. Empty where there's no night to speak of. */
    fun tonight(w: Weather.Wx, ms: Long, lat: Double, lon: Double): List<Note> {
        val hours = night(w.more.clouds, w.sun)
        val stars = stars(w.more.clouds, w.sun, w.cloud) ?: return emptyList()
        return listOf(stars, station(Heavens.passes, ms, Heavens.orbit != null), extra(ms, lat, lon, Heavens.kp, hours))
    }

    private const val STEP_MS = 5 * 60_000L
    /** How long after sunset, and before sunrise, the sky is still too light for stars. */
    private const val TWILIGHT_MIN = 45
    private const val CLEAR_BELOW = 30
    private const val BRIGHT_MAG = 1.6
}
