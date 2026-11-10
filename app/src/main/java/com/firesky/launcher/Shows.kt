package com.firesky.launcher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What TMDB knows about a show you're watching: how long each episode of the season runs, and when
 * the next one comes out. Looked up by name, kept for half a day.
 */
object Shows {
    class Ep(val season: Int, val episode: Int, val date: String)
    class Info(
        val id: Int, val runtimes: Map<Int, List<Int>>, val next: Ep?, val last: Ep?,
        /** The show's name in your language. The app's own can be in whichever language its profile is set to. */
        val name: String = "",
    )

    private const val MAX_AGE_MS = 12 * 3_600_000L
    private fun file(ctx: Context) = File(ctx.filesDir, "shows.json")

    @Synchronized
    private fun all(ctx: Context): JSONObject = runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())

    fun cached(ctx: Context, title: String): Info? = all(ctx).optJSONObject(title)?.let(::parse)

    /** Fetches what's stale, off the main thread. Returns what it has for [title], if anything. */
    fun refresh(ctx: Context, title: String, season: Int?): Info? {
        if (!Tmdb.on) return null
        val store = all(ctx)
        val old = store.optJSONObject(title)
        if (old != null && System.currentTimeMillis() - old.optLong("at") < MAX_AGE_MS && (season == null || old.optJSONObject("runtimes")?.has("$season") == true) && old.has("name")) return parse(old)
        return runCatching {
            val id = old?.optInt("id")?.takeIf { it > 0 }
                ?: Tmdb.api("/search/tv", "query" to title).getJSONArray("results").optJSONObject(0)?.getInt("id") ?: return null
            val d = Tmdb.api("/tv/$id", "language" to Prefs.language)
            val o = JSONObject().put("id", id).put("at", System.currentTimeMillis()).put("name", d.optString("name"))
            d.optJSONObject("next_episode_to_air")?.let { o.put("next", ep(it)) }
            d.optJSONObject("last_episode_to_air")?.let { o.put("last", ep(it)) }
            val runtimes = old?.optJSONObject("runtimes") ?: JSONObject()
            if (season != null) {
                val eps = Tmdb.api("/tv/$id/season/$season").getJSONArray("episodes")
                runtimes.put("$season", org.json.JSONArray().apply { for (i in 0 until eps.length()) put(eps.getJSONObject(i).optInt("runtime", 0)) })
            }
            o.put("runtimes", runtimes)
            synchronized(this) { val s = all(ctx); s.put(title, o); file(ctx).writeText(s.toString()) }
            parse(o)
        }.getOrElse { old?.let(::parse) }
    }

    private fun ep(j: JSONObject) = JSONObject().put("s", j.optInt("season_number")).put("e", j.optInt("episode_number")).put("date", j.optString("air_date"))

    private fun parse(o: JSONObject): Info {
        val rt = o.optJSONObject("runtimes") ?: JSONObject()
        val map = rt.keys().asSequence().associate { k -> k.toInt() to rt.getJSONArray(k).let { a -> List(a.length()) { a.getInt(it) } } }
        fun e(n: String) = o.optJSONObject(n)?.let { Ep(it.getInt("s"), it.getInt("e"), it.getString("date")) }
        return Info(o.getInt("id"), map, e("next"), e("last"), o.optString("name"))
    }

    /** "ends 9:47 PM" if you start or carry on now. */
    fun endsAt(now: Long, leftMs: Long): String =
        Calendar.getInstance().apply { timeInMillis = now + leftMs }.let { "ends " + Prefs.timeOfDay(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

    /** "6 more this season · about 5 h", from the episode after this one. */
    fun rest(i: Info, season: Int, episode: Int): String? {
        val r = i.runtimes[season] ?: return null
        val after = r.drop(episode)
        if (after.isEmpty()) return "Last of the season"
        val mins = after.sum()
        val time = if (mins >= 90) "about ${(mins / 60.0).roundToInt()} h" else "$mins min"
        return "${after.size} more this season · $time"
    }

    /** "Season 3 on Nov 20", or "Next episode Friday", for what's coming. */
    fun coming(i: Info, season: Int, today: Calendar = Calendar.getInstance()): String? {
        val n = i.next ?: return null
        val day = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(n.date) }.getOrNull() ?: return null
        val days = ((day.time - startOfDay(today)) / 86_400_000.0).roundToInt()
        if (days < 0) return null
        val whenText = when {
            days == 0 -> "today"
            days == 1 -> "tomorrow"
            days < 7 -> SimpleDateFormat("EEEE", Locale.US).format(day)
            else -> "on " + SimpleDateFormat("MMM d", Locale.US).format(day)
        }
        return if (n.season > season && n.episode <= 1) "Season ${n.season} $whenText" else "Next episode $whenText"
    }

    /** Out in the last two days and newer than what you've watched: worth a card of its own. */
    fun fresh(i: Info, season: Int?, episode: Int?, today: Calendar = Calendar.getInstance()): Ep? {
        val l = i.last ?: return null
        val day = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(l.date) }.getOrNull() ?: return null
        val days = ((startOfDay(today) - day.time) / 86_400_000.0).roundToInt()
        if (days !in 0..2) return null
        val newer = season == null || l.season > season || (l.season == season && l.episode > (episode ?: 0))
        return if (newer) l else null
    }

    private fun startOfDay(c: Calendar) = (c.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
}
