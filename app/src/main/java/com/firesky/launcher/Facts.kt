package com.firesky.launcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * What TMDB knows about a title that picks are made from: who made it and who's in it, what it's
 * about, what TMDB recommends after it, which of the TV's services carry it, and the series of
 * films it belongs to. One request a title, kept for two weeks.
 */
object Facts {
    private const val TAG = "FireSkyFacts"
    private const val MAX_AGE_MS = 14 * 86_400_000L
    private const val KEEP = 1000
    /** A title that couldn't be found by its name is looked for again after this. */
    private const val MISS_MS = 7 * 86_400_000L

    /** Something a title shares with others: a person in a role, a keyword or a genre. */
    class Tag(val role: String, val name: String) {
        /** What's compared between titles: a person is the same person whatever they did. */
        val id get() = when (role) { "kw" -> "k:${name.lowercase(Locale.ROOT)}"; "genre" -> "g:$name"; else -> "p:$name" }
    }

    class Info(
        /** "title:m329865" for a film, "title:t95396" for a show: the same key as a title's card. */
        val key: String,
        val title: String,
        val year: Int,
        val tags: List<Tag>,
        /** What TMDB recommends after it, by key. */
        val recs: List<String>,
        /** The installed-or-not apps that carry it where the TV is, as package names. */
        val pkgs: List<String>,
        val collection: Int,
        val vote: Double,
        val votes: Int,
        val popularity: Double,
        val backdrop: String?,
        val at: Long,
    ) {
        val movie get() = key.startsWith("title:m")
        val id get() = key.substringAfter("title:").drop(1).toInt()
    }

    /** One of a series of films, in the order they came out. */
    class Part(val key: String, val title: String, val date: String)

    // ---------- Finding a title by its name ----------

    private fun matchFile(ctx: Context) = File(ctx.filesDir, "matches.json")

    internal fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace("&", "and").replace(Regex("[^a-z0-9]+"), " ").trim()

    /** TMDB's search; a test puts its own answers here. */
    internal var search: (path: String, query: String) -> JSONObject = { path, q -> Tmdb.api(path, "query" to q) }

    @Volatile private var matchesHeld: JSONObject? = null

    /** What's been looked up by name, read once. */
    @Synchronized
    private fun matches(ctx: Context): JSONObject = matchesHeld
        ?: runCatching { JSONObject(matchFile(ctx).readText()) }.getOrDefault(JSONObject()).also { matchesHeld = it }

    private fun memoOf(name: String, series: Boolean?) = "${if (series == true) "t" else if (series == false) "m" else "?"}|${norm(name)}"

    /**
     * What's already known of [name] without asking: its key, "" when TMDB had no such title in the
     * past week, or null when it's yet to be asked.
     */
    fun known(ctx: Context, name: String, series: Boolean?): String? {
        if (norm(name).isEmpty()) return ""
        return synchronized(this) { matches(ctx).optJSONObject(memoOf(name, series)) }?.let { o ->
            o.optString("k").takeIf { it.isNotEmpty() || System.currentTimeMillis() - o.optLong("at") < MISS_MS }
        }
    }

    /**
     * The title called [name], if TMDB has one by exactly that name: its key, "" when it has none,
     * or null when TMDB couldn't be asked, so it's tried again. Near misses are left alone: a wrong
     * match would teach the wrong taste. [series] narrows it to shows or to films.
     */
    fun match(ctx: Context, name: String, series: Boolean?): String? {
        known(ctx, name, series)?.let { return it }
        val want = norm(name)
        val memo = memoOf(name, series)
        if (!Tmdb.on) return null
        val found = runCatching {
            val path = when (series) { true -> "search/tv"; false -> "search/movie"; null -> "search/multi" }
            val r = search(path, name).getJSONArray("results")
            (0 until r.length()).map(r::getJSONObject).firstOrNull { o ->
                val type = o.optString("media_type").ifEmpty { if (series == true) "tv" else "movie" }
                type in setOf("movie", "tv") && listOf("title", "name", "original_title", "original_name").any { norm(o.optString(it)) == want }
            }?.let { o ->
                val tv = o.optString("media_type").ifEmpty { if (series == true) "tv" else "movie" } == "tv"
                Details.key(!tv, o.getInt("id"))
            }
        }.onFailure { Log.w(TAG, "couldn't look up $name: ${it.message}") }.getOrElse { return null }
        synchronized(this) {
            val all = matches(ctx)
            all.put(memo, JSONObject().put("k", found ?: "").put("at", System.currentTimeMillis()))
            matchFile(ctx).writeText(all.toString())
        }
        return found ?: ""
    }

    // ---------- A title's facts ----------

    private fun file(ctx: Context) = File(ctx.filesDir, "facts.json")
    @Volatile private var held: JSONObject? = null
    private var dirty = false

    @Synchronized
    private fun all(ctx: Context): JSONObject = held ?: runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject()).also { held = it }

    @Synchronized
    private fun keep(ctx: Context, i: Info) {
        val a = all(ctx)
        a.put(i.key, toJson(i))
        if (a.length() > KEEP) {
            val old = a.keys().asSequence().toList().sortedBy { a.getJSONObject(it).optLong("at") }.take(a.length() - KEEP)
            old.forEach(a::remove)
        }
        dirty = true
    }

    /** Writes what's been learned since last time. Once at the end of a refresh, not after each title. */
    @Synchronized
    fun flush(ctx: Context) {
        if (!dirty) return
        val tmp = File(file(ctx).path + ".part")
        tmp.writeText(all(ctx).toString())
        tmp.renameTo(file(ctx))
        dirty = false
    }

    fun cached(ctx: Context, key: String): Info? = synchronized(this) { all(ctx).optJSONObject(key) }?.let { fromJson(key, it) }

    /** What's kept, or else asked for. An old answer still serves if TMDB can't be reached. Off the main thread. */
    fun get(ctx: Context, key: String): Info? {
        val old = cached(ctx, key)
        if (old != null && System.currentTimeMillis() - old.at < MAX_AGE_MS) return old
        return fetch(ctx, key) ?: old
    }

    fun fetch(ctx: Context, key: String): Info? = runCatching {
        if (!Tmdb.on) return null
        val movie = key.startsWith("title:m")
        val id = key.substringAfter("title:").drop(1).toInt()
        val j = Tmdb.api(
            "${if (movie) "movie" else "tv"}/$id", "language" to Prefs.language,
            "append_to_response" to "credits,keywords,recommendations,watch/providers",
        )
        parse(key, j, movie, Prefs.region).also { keep(ctx, it) }
    }.onFailure { Log.w(TAG, "couldn't get $key: ${it.message}") }.getOrNull()

    internal fun parse(key: String, j: JSONObject, movie: Boolean, region: String): Info {
        val tags = ArrayList<Tag>()
        val credits = j.optJSONObject("credits")
        credits?.optJSONArray("crew")?.let { crew ->
            var writers = 0
            for (n in 0 until crew.length()) {
                val c = crew.getJSONObject(n)
                val who = c.optString("name").ifBlank { continue }
                when (c.optString("job")) {
                    "Director" -> tags += Tag("director", who)
                    "Director of Photography" -> tags += Tag("dp", who)
                    "Original Music Composer" -> tags += Tag("music", who)
                    "Screenplay", "Writer" -> if (writers++ < 2) tags += Tag("writer", who)
                }
            }
        }
        j.optJSONArray("created_by")?.let { a -> for (n in 0 until a.length()) a.getJSONObject(n).optString("name").takeIf { it.isNotBlank() }?.let { tags += Tag("creator", it) } }
        credits?.optJSONArray("cast")?.let { cast -> for (n in 0 until minOf(4, cast.length())) cast.getJSONObject(n).optString("name").takeIf { it.isNotBlank() }?.let { tags += Tag("actor", it) } }
        val kws = j.optJSONObject("keywords")?.let { it.optJSONArray("keywords") ?: it.optJSONArray("results") }
        if (kws != null) for (n in 0 until minOf(16, kws.length())) kws.getJSONObject(n).optString("name").takeIf { it.isNotBlank() }?.let { tags += Tag("kw", it) }
        j.optJSONArray("genres")?.let { g -> for (n in 0 until g.length()) g.getJSONObject(n).optString("name").takeIf { it.isNotBlank() }?.let { tags += Tag("genre", it) } }
        val recs = ArrayList<String>()
        j.optJSONObject("recommendations")?.optJSONArray("results")?.let { r ->
            for (n in 0 until minOf(12, r.length())) {
                val o = r.getJSONObject(n)
                val tv = o.optString("media_type").ifEmpty { if (movie) "movie" else "tv" } == "tv"
                recs += Details.key(!tv, o.getInt("id"))
            }
        }
        val pkgs = ArrayList<String>()
        j.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(region)?.optJSONArray("flatrate")?.let { f ->
            for (n in 0 until f.length()) Tmdb.pkgOf(f.getJSONObject(n).optInt("provider_id"))?.let { if (it !in pkgs) pkgs += it }
        }
        val date = j.optString(if (movie) "release_date" else "first_air_date")
        return Info(
            key, j.optString(if (movie) "title" else "name"), date.take(4).toIntOrNull() ?: 0,
            tags.distinctBy { it.role + it.name }, recs, pkgs,
            j.optJSONObject("belongs_to_collection")?.optInt("id") ?: 0,
            j.optDouble("vote_average", 0.0), j.optInt("vote_count", 0), j.optDouble("popularity", 0.0),
            j.optString("backdrop_path").takeIf { it.startsWith("/") }, System.currentTimeMillis(),
        )
    }

    private fun toJson(i: Info) = JSONObject()
        .put("t", i.title).put("y", i.year)
        .put("g", JSONArray().apply { i.tags.forEach { put(JSONArray().put(it.role).put(it.name)) } })
        .put("r", JSONArray(i.recs)).put("p", JSONArray(i.pkgs)).put("c", i.collection)
        .put("v", i.vote).put("n", i.votes).put("pop", i.popularity).put("b", i.backdrop ?: "").put("at", i.at)

    private fun strings(a: JSONArray?) = List(a?.length() ?: 0) { a!!.getString(it) }

    private fun fromJson(key: String, o: JSONObject): Info {
        val g = o.optJSONArray("g") ?: JSONArray()
        return Info(
            key, o.optString("t"), o.optInt("y"),
            List(g.length()) { g.getJSONArray(it).let { t -> Tag(t.getString(0), t.getString(1)) } },
            strings(o.optJSONArray("r")), strings(o.optJSONArray("p")), o.optInt("c"),
            o.optDouble("v"), o.optInt("n"), o.optDouble("pop"), o.optString("b").ifEmpty { null }, o.optLong("at"),
        )
    }

    // ---------- Series of films, keywords and lists by keyword ----------

    private fun extraFile(ctx: Context) = File(ctx.filesDir, "facts-extra.json")

    /** Small answers kept by name for a week: series of films, keyword ids, lists by keyword. */
    private fun <T> memo(ctx: Context, name: String, read: (JSONObject) -> T, ask: () -> JSONObject?): T? {
        val all = synchronized(this) { runCatching { JSONObject(extraFile(ctx).readText()) }.getOrDefault(JSONObject()) }
        all.optJSONObject(name)?.takeIf { System.currentTimeMillis() - it.optLong("at") < MAX_AGE_MS / 2 }?.let { return read(it) }
        val got = runCatching { ask() }.onFailure { Log.w(TAG, "couldn't get $name: ${it.message}") }.getOrNull()
            ?: return all.optJSONObject(name)?.let(read)
        got.put("at", System.currentTimeMillis())
        synchronized(this) {
            val a = runCatching { JSONObject(extraFile(ctx).readText()) }.getOrDefault(JSONObject())
            a.put(name, got)
            extraFile(ctx).writeText(a.toString())
        }
        return read(got)
    }

    /** The films in a series, released ones only, in the order they came out. */
    fun collection(ctx: Context, id: Int): List<Part> = memo(ctx, "col:$id", { o ->
        val a = o.optJSONArray("parts") ?: JSONArray()
        List(a.length()) { a.getJSONObject(it).let { p -> Part(p.getString("k"), p.getString("t"), p.getString("d")) } }
    }) {
        val r = Tmdb.api("collection/$id", "language" to Prefs.language).getJSONArray("parts")
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val parts = (0 until r.length()).map(r::getJSONObject)
            .filter { it.optString("release_date").let { d -> d.isNotEmpty() && d <= today } }
            .sortedBy { it.optString("release_date") }
        JSONObject().put("parts", JSONArray().apply {
            parts.forEach { put(JSONObject().put("k", Details.key(true, it.getInt("id"))).put("t", it.optString("title")).put("d", it.optString("release_date"))) }
        })
    }.orEmpty()

    /** TMDB's id for a keyword, by its exact name. */
    fun keyword(ctx: Context, name: String): Int? = memo(ctx, "kw:$name", { it.optInt("id").takeIf { id -> id > 0 } }) {
        val r = Tmdb.api("search/keyword", "query" to name).getJSONArray("results")
        val hit = (0 until r.length()).map(r::getJSONObject).firstOrNull { it.optString("name").equals(name, ignoreCase = true) }
        JSONObject().put("id", hit?.optInt("id") ?: 0)
    }

    /** Popular films about any of [keywords] on the given services, by key. */
    fun about(ctx: Context, keywords: List<Int>, providers: List<Int>): List<String> {
        if (keywords.isEmpty() || providers.isEmpty()) return emptyList()
        val name = "about:${keywords.joinToString("|")}:${providers.sorted().joinToString("|")}:${Prefs.region}"
        return memo(ctx, name, { o -> strings(o.optJSONArray("k")) }) {
            val r = Tmdb.api(
                "discover/movie", "with_keywords" to keywords.joinToString("|"), "with_watch_providers" to providers.joinToString("|"),
                "watch_region" to Prefs.region, "sort_by" to "popularity.desc", "include_adult" to "false", "vote_count.gte" to "200",
            ).getJSONArray("results")
            JSONObject().put("k", JSONArray().apply { for (n in 0 until minOf(8, r.length())) put(Details.key(true, r.getJSONObject(n).getInt("id"))) })
        }.orEmpty()
    }

    /** Drops what's kept, after the key or the country changes. */
    fun forget(ctx: Context) = synchronized(this) { held = null; matchesHeld = null; dirty = false; file(ctx).delete(); extraFile(ctx).delete() }
}
