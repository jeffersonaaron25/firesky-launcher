package com.firesky.launcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * What's been watched on this TV, kept for good: how far each title got, whether it was finished or
 * watched again, and for a show how many episodes. Nothing is asked of you. It comes from Netflix's
 * continue-watching list, which FireSky already reads, and from the name and length other apps give
 * while they play. It stays on the TV; only a title's name is looked up, on TMDB.
 */
object Taste {
    private const val TAG = "FireSkyTaste"
    /** Past this, a film counts as watched to the end. */
    const val END = .85f
    /** Below this and untouched for [LEFT_MS], it was given up on. */
    const val EARLY = .3f
    const val LEFT_MS = 14 * 86_400_000L
    /** Shorter than this, what's playing is a clip or a trailer, not a film or an episode. */
    private const val MIN_LENGTH_MS = 20 * 60_000L
    /** Shorter than this and it's an episode of something; longer, a film. */
    private const val FILM_MS = 70 * 60_000L
    /** Getting to the end again this soon is skipping through, not watching it again. */
    private const val AGAIN_MS = 3_600_000L
    /** Only these apps play the films and shows TMDB knows. */
    val VIDEO = setOf("com.netflix.ninja", "com.amazon.firebat", "com.amazon.avod", "com.hulu.plus", "com.hbo.hbonow", "com.apple.atve.amazon.appletv", "com.disney.disneyplus", "com.peacocktv.peacockandroid", "com.cbs.ott", "com.plexapp.android", "org.xbmc.kodi")

    enum class Verdict(val weight: Float) { TWICE(2f), FINISHED(1f), WATCHING(0f), LEFT(-1f) }

    class Seen(
        val title: String,
        val series: Boolean,
        var pkg: String,
        /** The furthest it got, 0 to 1. For a show, in the episode last seen. */
        var max: Float,
        /** Where it was last, so starting again from the top can be told apart. */
        var last: Float,
        /** Times watched to the end, for a film. */
        var ends: Int,
        /** "1x3" for each episode seen. */
        val episodes: MutableSet<String>,
        var firstAt: Long,
        var lastAt: Long,
        /** TMDB's key once it's been found. */
        var key: String? = null,
        /** When it was last watched to the end, for a film. */
        var endAt: Long = 0,
    ) {
        val id get() = "${if (series) "t" else "m"}|${Facts.norm(title)}"
    }

    /** How a title went, from what was seen of it. */
    fun verdict(s: Seen, now: Long): Verdict {
        val stale = now - s.lastAt > LEFT_MS
        return if (s.series) when {
            s.episodes.size >= 10 -> Verdict.TWICE
            s.episodes.size >= 3 -> Verdict.FINISHED
            s.episodes.size <= 1 && s.max < .5f && stale -> Verdict.LEFT
            else -> Verdict.WATCHING
        } else when {
            s.ends >= 2 -> Verdict.TWICE
            s.ends >= 1 -> Verdict.FINISHED
            s.max < EARLY && stale -> Verdict.LEFT
            else -> Verdict.WATCHING
        }
    }

    /** Where watching has got to. Returns true if that changed anything worth keeping. */
    internal fun step(s: Seen, progress: Float, season: Int?, episode: Int?, at: Long): Boolean {
        var changed = false
        if (s.series && season != null && episode != null) {
            // Being on an episode means having watched the ones before it, and the seasons before.
            for (e in 1..episode) changed = s.episodes.add("${season}x$e") || changed
            for (p in 1 until season) changed = s.episodes.add("${p}x*") || changed
        }
        // A show from an app that doesn't say which episode: each day with most of one watched counts as one.
        if (s.series && season == null && progress >= .5f) changed = s.episodes.add("d${at / 86_400_000L}") || changed
        if (!s.series) {
            // Finished before, and now from the start again: a second time, once it gets to the end.
            if (s.ends > 0 && s.last >= END && progress < .15f) { s.last = progress; changed = true }
            if (progress >= END && s.last < END && at - s.endAt > AGAIN_MS) { s.ends++; s.endAt = at; changed = true }
        }
        if (progress > s.max + .05f || (progress >= END && s.max < END)) { s.max = maxOf(s.max, progress); changed = true }
        if (at - s.lastAt > 3_600_000L) changed = true
        s.last = progress
        s.lastAt = maxOf(s.lastAt, at)
        return changed
    }

    // ---------- Kept on disk ----------

    @Volatile private var app: Context? = null
    private val io = Executors.newSingleThreadExecutor { Thread(it, "taste").apply { isDaemon = true } }
    private var seen: LinkedHashMap<String, Seen>? = null

    fun init(c: Context) { if (app == null) app = c.applicationContext }

    private fun file(c: Context) = File(c.filesDir, "taste.json")

    @Synchronized
    private fun load(c: Context): LinkedHashMap<String, Seen> = seen ?: LinkedHashMap<String, Seen>().also { m ->
        runCatching {
            val a = JSONArray(file(c).readText())
            for (n in 0 until a.length()) fromJson(a.getJSONObject(n)).let { m[it.id] = it }
        }
        seen = m
    }

    @Synchronized
    private fun save(c: Context) {
        val a = JSONArray().apply { load(c).values.forEach { put(toJson(it)) } }
        val tmp = File(file(c).path + ".part")
        tmp.writeText(a.toString())
        tmp.renameTo(file(c))
    }

    /** Everything seen, as kept. */
    @Synchronized
    fun all(c: Context): List<Seen> = load(c).values.toList()

    /** Its TMDB key, once found, so it's looked up only once. */
    fun found(c: Context, s: Seen, key: String) = io.execute {
        synchronized(this) { load(c)[s.id]?.key = key }
        save(c)
    }

    private fun note(c: Context, title: String, series: Boolean, pkg: String, progress: Float, season: Int?, episode: Int?, at: Long) {
        if (title.isBlank()) return
        val changed = synchronized(this) {
            val m = load(c)
            val id = "${if (series) "t" else "m"}|${Facts.norm(title)}"
            val s = m.getOrPut(id) { Seen(title, series, pkg, 0f, 0f, 0, HashSet(), at, at) }
            s.pkg = pkg
            step(s, progress, season, episode, at)
        }
        if (changed) save(c)
    }

    /**
     * Netflix's list as it now is, with what it was. A title that drops off having got near the end
     * was finished: Netflix takes finished ones off.
     */
    fun list(c: Context, pkg: String, was: List<Watching.Item>, now: List<Watching.Item>) {
        val ctx = c.applicationContext
        io.execute {
            runCatching {
                for (i in now) note(ctx, i.title, i.season != null, pkg, i.progress ?: 0f, i.season, i.episode, i.watchedAt)
                val gone = was.filter { w -> now.none { it.id == w.id } }
                for (w in gone) if (w.season == null && (w.progress ?: 0f) >= .7f) note(ctx, w.title, false, pkg, 1f, null, null, System.currentTimeMillis())
            }.onFailure { Log.w(TAG, "couldn't note the list", it) }
        }
    }

    /**
     * A head start from what the TV already holds: Netflix's list as last saved, and everything
     * Kodi's library says was watched. Safe to do again; nothing is counted twice.
     */
    fun seed(c: Context) {
        val changed = synchronized(this) {
            val m = load(c)
            var any = false
            for (i in Watching.everything(c)) {
                val series = i.season != null
                val id = "${if (series) "t" else "m"}|${Facts.norm(i.title)}"
                val s = m.getOrPut(id) { any = true; Seen(i.title, series, i.pkg, 0f, 0f, 0, HashSet(), i.watchedAt, i.watchedAt) }
                any = step(s, i.progress ?: 0f, i.season, i.episode, i.watchedAt) || any
            }
            for (k in Kodi.watched(c)) {
                val id = "${if (k.series) "t" else "m"}|${Facts.norm(k.title)}"
                val s = m.getOrPut(id) { any = true; Seen(k.title, k.series, Kodi.PKG, 0f, 0f, 0, HashSet(), k.at, k.at) }
                if (k.series) { for (e in k.episodes) any = s.episodes.add(e) || any }
                else if (s.ends < k.plays) { s.ends = k.plays; s.max = 1f; s.last = 1f; any = true }
                s.lastAt = maxOf(s.lastAt, k.at)
            }
            any
        }
        if (changed) save(c)
    }

    /** What an app says is playing: kept when it's a film or an episode of something, not a clip. */
    fun playing(pkg: String, title: String?, positionMs: Long, durationMs: Long) {
        val c = app ?: return
        if (title.isNullOrBlank() || durationMs < MIN_LENGTH_MS || positionMs <= 0 || Resume.alias(pkg) !in VIDEO || pkg == "com.netflix.ninja") return
        val progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val at = System.currentTimeMillis()
        // Other apps don't say whether it's a film or which episode, so the length decides. An episode's
        // own name won't be found as a show, so it teaches nothing rather than something wrong.
        io.execute { runCatching { note(c, title, durationMs < FILM_MS, pkg, progress, null, null, at) } }
    }

    /** For tests: start again from nothing. */
    @Synchronized
    internal fun forget(c: Context) { seen = null; file(c).delete() }

    private fun toJson(s: Seen) = JSONObject()
        .put("t", s.title).put("tv", s.series).put("p", s.pkg).put("max", s.max.toDouble()).put("last", s.last.toDouble())
        .put("ends", s.ends).put("eps", JSONArray(s.episodes.toList())).put("first", s.firstAt).put("at", s.lastAt).put("k", s.key).put("end", s.endAt)

    private fun fromJson(o: JSONObject): Seen {
        val e = o.optJSONArray("eps") ?: JSONArray()
        return Seen(
            o.getString("t"), o.optBoolean("tv"), o.optString("p"), o.optDouble("max").toFloat(), o.optDouble("last").toFloat(),
            o.optInt("ends"), HashSet(List(e.length()) { e.getString(it) }), o.optLong("first"), o.optLong("at"),
            if (o.has("k") && !o.isNull("k")) o.optString("k") else null,
            o.optLong("end"),
        )
    }
}
