package com.firesky.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * What a title's own page says about it: TMDB's description, score, age rating, cast and trailer.
 * Asked for once and kept for a week, so the page opens with everything already there.
 */
object Details {
    private const val MAX_AGE_MS = 7 * 86_400_000L
    /** Kept on the TV at most. The oldest go first. */
    private const val KEEP = 60

    class Info(
        /** The same key as the title's card: "title:m27205" for a film, "title:t1399" for a show. */
        val key: String,
        val title: String,
        val overview: String,
        /** "2010", or "2011–2019" for a show that has ended. */
        val years: String,
        /** The age rating where the TV is, such as TV-MA or 12A. Empty when TMDB has none. */
        val rated: String,
        /** "2 h 28 min" for a film, "8 seasons" for a show. */
        val length: String,
        val genres: List<String>,
        /** Out of 10, from TMDB's members. */
        val score: Double,
        val votes: Int,
        val cast: List<String>,
        /** "Directed by Christopher Nolan", "Created by …", or empty. */
        val by: String,
        /** The trailer's YouTube ID, when there is one. */
        val trailer: String?,
        /** How long a film runs, or one episode of a show. 0 when TMDB doesn't say. */
        val minutes: Int = 0,
        /** TMDB's keywords for it, which say among other things where it's set. */
        val keywords: List<String> = emptyList(),
        /** The title's own lettering from TMDB, a transparent picture's path, when it has one. */
        val logo: String? = null,
    ) {
        val movie get() = key.startsWith("title:m")
        val trailerLink get() = trailer?.let { "https://www.youtube.com/watch?v=$it" }
    }

    fun key(movie: Boolean, id: Int) = "title:${if (movie) "m" else "t"}$id"

    private fun names(a: JSONArray?, n: Int) = List(minOf(n, a?.length() ?: 0)) { a!!.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }

    private fun and(l: List<String>) = if (l.size <= 1) l.joinToString() else l.dropLast(1).joinToString(", ") + " and " + l.last()

    /**
     * The trailer to offer: a real trailer before a teaser, the studio's own before anyone else's,
     * in your language before English and English before any other, and the newest of those, which for a show is the latest season's.
     */
    internal fun trailer(videos: JSONArray?, language: String): String? {
        val lang = language.substringBefore('-').lowercase(Locale.ROOT)
        return (0 until (videos?.length() ?: 0)).map { videos!!.getJSONObject(it) }
            .filter { it.optString("site") == "YouTube" && it.optString("type") in setOf("Trailer", "Teaser") && it.optString("key").isNotBlank() }
            .sortedWith(
                compareByDescending<JSONObject> { it.optString("type") == "Trailer" }
                    .thenByDescending { it.optString("iso_639_1") == lang }
                    .thenByDescending { it.optString("iso_639_1") == "en" }
                    .thenByDescending { it.optBoolean("official") }
                    .thenByDescending { it.optString("published_at") },
            )
            .firstOrNull()?.getString("key")
    }

    /** The age rating for [region], or failing that the American one, which most titles have. */
    internal fun rated(j: JSONObject, movie: Boolean, region: String): String {
        val all = j.optJSONObject(if (movie) "release_dates" else "content_ratings")?.optJSONArray("results") ?: return ""
        fun of(country: String): String? {
            val r = (0 until all.length()).map(all::getJSONObject).firstOrNull { it.optString("iso_3166_1") == country } ?: return null
            if (!movie) return r.optString("rating").ifBlank { null }
            val dates = r.optJSONArray("release_dates") ?: return null
            // A film has a rating for each kind of release. The cinema's is the one people know.
            return (0 until dates.length()).map(dates::getJSONObject).filter { it.optString("certification").isNotBlank() }
                .minByOrNull { if (it.optInt("type") == 3) 0 else 1 }?.getString("certification")
        }
        return of(region) ?: of("US") ?: ""
    }

    private fun length(j: JSONObject, movie: Boolean): String {
        if (movie) {
            val m = j.optInt("runtime")
            return when { m <= 0 -> ""; m < 60 -> "$m min"; m % 60 == 0 -> "${m / 60} h"; else -> "${m / 60} h ${m % 60} min" }
        }
        val n = j.optInt("number_of_seasons")
        return when { n <= 0 -> ""; n == 1 -> "1 season"; else -> "$n seasons" }
    }

    private fun years(j: JSONObject, movie: Boolean): String {
        val first = j.optString(if (movie) "release_date" else "first_air_date").take(4)
        if (movie || first.isEmpty()) return first
        val last = j.optString("last_air_date").take(4)
        return if (j.optString("status") in setOf("Ended", "Canceled") && last.isNotEmpty() && last != first) "$first–$last" else first
    }

    /**
     * The title's own lettering: in your language, else English, else one with no words at all; the
     * best liked of those. Only pictures, not drawings, since the TV can't show those.
     */
    internal fun logo(logos: JSONArray?, language: String): String? {
        val lang = language.substringBefore('-').lowercase(Locale.ROOT)
        fun rank(o: JSONObject) = when (o.optString("iso_639_1").takeIf { it != "null" }.orEmpty()) { lang -> 0; "en" -> 1; "" -> 2; else -> 9 }
        return (0 until (logos?.length() ?: 0)).map { logos!!.getJSONObject(it) }
            .filter { it.optString("file_path").endsWith(".png") && rank(it) < 9 }
            .sortedWith(compareBy<JSONObject> { rank(it) }.thenByDescending { it.optDouble("vote_average", 0.0) })
            .firstOrNull()?.optString("file_path")
    }

    /** Reads TMDB's answer for one title, asked for with its videos, ratings and credits attached. */
    fun parse(j: JSONObject, movie: Boolean, region: String, language: String): Info {
        val credits = j.optJSONObject("credits")
        val crew = credits?.optJSONArray("crew")
        val directors = (0 until (crew?.length() ?: 0)).map { crew!!.getJSONObject(it) }.filter { it.optString("job") == "Director" }.map { it.optString("name") }.take(2)
        val creators = names(j.optJSONArray("created_by"), 2)
        return Info(
            key(movie, j.getInt("id")),
            j.optString(if (movie) "title" else "name"),
            j.optString("overview").trim(),
            years(j, movie), rated(j, movie, region), length(j, movie),
            names(j.optJSONArray("genres"), 2),
            j.optDouble("vote_average", 0.0), j.optInt("vote_count"),
            names(credits?.optJSONArray("cast"), 3),
            when { movie && directors.isNotEmpty() -> "Directed by ${and(directors)}"; !movie && creators.isNotEmpty() -> "Created by ${and(creators)}"; else -> "" },
            trailer(j.optJSONObject("videos")?.optJSONArray("results"), language),
            if (movie) j.optInt("runtime") else j.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 } ?: j.optJSONObject("last_episode_to_air")?.optInt("runtime") ?: 0,
            j.optJSONObject("keywords")?.let { k -> names(k.optJSONArray("keywords") ?: k.optJSONArray("results"), 40) }.orEmpty(),
            logo(j.optJSONObject("images")?.optJSONArray("logos"), language),
        )
    }

    /**
     * The line under the title: when it's from, how many seasons, and what kind of thing it is. The
     * age rating is drawn beside it. A film's length is left for [tonight], which says what it means.
     */
    fun facts(i: Info) = listOf(i.years, if (i.movie && i.minutes > 0) "" else i.length, i.genres.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · ")

    /**
     * What watching it now would mean for your evening: "2 h 28 min · ends 11:42 PM if you start now",
     * or for a show, "Episodes about 55 min · the first ends 9:25 PM". Null when TMDB doesn't know the length.
     */
    fun tonight(i: Info, now: Long): String? {
        if (i.minutes <= 0) return null
        val ends = Shows.endsAt(now, i.minutes * 60_000L)
        return if (i.movie) "${i.length} · $ends if you start now" else "Episodes about ${i.minutes} min · the first $ends"
    }

    /** "8.5". Null until enough people have voted for the number to mean something. */
    fun score(i: Info): String? = if (i.votes < 20 || i.score <= 0) null else "%.1f".format(Locale.US, i.score)

    fun votes(i: Info) = "%,d votes".format(Locale.US, i.votes)

    /* ---------- kept on the TV ---------- */

    private fun file(ctx: Context) = File(ctx.filesDir, "details.json")

    @Volatile private var held: JSONObject? = null

    private fun all(ctx: Context): JSONObject = held
        ?: runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject()).also { held = it }

    private fun toJson(i: Info) = JSONObject()
        .put("title", i.title).put("overview", i.overview).put("years", i.years).put("rated", i.rated).put("length", i.length)
        .put("genres", JSONArray(i.genres)).put("score", i.score).put("votes", i.votes).put("cast", JSONArray(i.cast))
        .put("by", i.by).put("trailer", i.trailer ?: "").put("minutes", i.minutes)
        .put("kw", JSONArray(i.keywords)).put("logo", i.logo ?: "")

    private fun strings(a: JSONArray?) = List(a?.length() ?: 0) { a!!.getString(it) }

    private fun fromJson(key: String, o: JSONObject) = Info(
        key, o.getString("title"), o.getString("overview"), o.getString("years"), o.getString("rated"), o.getString("length"),
        strings(o.optJSONArray("genres")), o.getDouble("score"), o.getInt("votes"), strings(o.optJSONArray("cast")),
        o.getString("by"), o.getString("trailer").ifBlank { null }, o.optInt("minutes"),
        strings(o.optJSONArray("kw")), o.optString("logo").ifBlank { null },
    )

    /** What's known about a title already. Old answers are still shown; [warm] replaces them when it can. */
    fun cached(ctx: Context, key: String): Info? = synchronized(this) {
        all(ctx).optJSONObject(key)?.let { runCatching { fromJson(key, it) }.getOrNull() }
    }

    /** Kept recently, and since keywords and lettering were asked for too. */
    private fun fresh(ctx: Context, key: String) = synchronized(this) {
        val o = all(ctx).optJSONObject(key)
        o != null && o.has("kw") && System.currentTimeMillis() - o.optLong("at") < MAX_AGE_MS
    }

    internal fun keep(ctx: Context, i: Info) = synchronized(this) {
        val a = all(ctx)
        a.put(i.key, toJson(i).put("at", System.currentTimeMillis()))
        val keys = a.keys().asSequence().toList()
        if (keys.size > KEEP) keys.sortedBy { a.getJSONObject(it).optLong("at") }.take(keys.size - KEEP).forEach(a::remove)
        runCatching { file(ctx).writeText(a.toString()) }
    }

    /** Asks TMDB about one title. Off the main thread. */
    fun fetch(ctx: Context, movie: Boolean, id: Int): Info? = runCatching {
        if (!Tmdb.on) return null
        val j = Tmdb.api(
            "${if (movie) "movie" else "tv"}/$id", "language" to Prefs.language,
            "append_to_response" to "videos,credits,keywords,images,${if (movie) "release_dates" else "content_ratings"}",
            // Trailers in your language, and failing that in English; lettering the same, or none with words.
            "include_video_language" to "${Prefs.language.substringBefore('-')},en",
            "include_image_language" to "${Prefs.language.substringBefore('-')},en,null",
        )
        parse(j, movie, Prefs.region, Prefs.language).also { keep(ctx, it) }
    }.getOrNull()

    /**
     * What's known about a title, asked for again if that's missing or a week old, so its page
     * opens with everything there. An old answer still serves if TMDB can't be reached. Off the main thread.
     */
    fun get(ctx: Context, movie: Boolean, id: Int): Info? {
        val key = key(movie, id)
        return if (fresh(ctx, key)) cached(ctx, key) else fetch(ctx, movie, id) ?: cached(ctx, key)
    }

    /** Drops what's kept, after the country or language changes. */
    fun forget(ctx: Context) = synchronized(this) { held = null; file(ctx).delete() }
}
