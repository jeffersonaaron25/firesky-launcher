package com.firesky.launcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import kotlin.concurrent.thread

/**
 * What you're partway through, by name. Netflix hands its Continue Watching row to Fire TV for
 * Amazon's home screen, and that hand-over is written to the log, which this app may read. Netflix
 * reveals nothing else about what is playing, so this is the only way to know the title.
 */
object Watching {
    private const val TAG = "FTVIntegrationSDK"
    private const val SET_ITEMS = "setContinueWatchingItems"
    private const val SET_META = "setContinueWatchingItemMetadata"
    /** Older than this, it's no longer what you're watching. */
    private const val MAX_AGE_MS = 7 * 86_400_000L

    class Item(
        val pkg: String,
        val id: String,
        val title: String,
        val season: Int?,
        val episode: Int?,
        val art: String?,
        val link: String?,
        /** The app's own link stops working after about a day. */
        val linkUntil: Long,
        /** The title's page, which never expires. */
        val page: String?,
        val progressMs: Long,
        val durationMs: Long,
        val watchedAt: Long,
    ) {
        val key get() = "t-w-$id"

        val progress: Float? get() = if (durationMs > 0 && progressMs > 0) (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

        /** "S2 E1 · 8 min left", or as much of it as is known. */
        val meta: String get() = listOfNotNull(
            if (season != null && episode != null) "S$season E$episode" else null,
            if (progressMs > 0 && durationMs > progressMs) left(durationMs - progressMs) else null,
        ).joinToString(" · ")

        fun linkAt(now: Long): String? = if (link != null && now < linkUntil) link else page
    }

    private fun left(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(1)
        return if (m < 60) "$m min left" else "${m / 60} h ${m % 60} min left"
    }

    /**
     * Reads the log one line at a time (`logcat -v brief`). Each hand-over is a header naming the
     * app, then one line per item: first the list with progress, then the titles and pictures, once
     * per language. [done] gets an app's whole list each time one is complete.
     */
    class Reader(private val locale: String, private val done: (String, List<Item>) -> Unit) {
        private class Prog(val durationMs: Long, val progressMs: Long, val watchedAt: Long)
        private class Meta(
            val title: String, val season: Int?, val episode: Int?, val art: String?,
            val link: String?, val linkUntil: Long, val page: String?, val locale: String?,
        )

        private val pkgOf = HashMap<String, String>()
        private val fnOf = HashMap<String, String>()
        private val progs = HashMap<String, LinkedHashMap<String, Prog>>()
        private val metas = HashMap<String, HashMap<String, Meta>>()

        fun line(l: String) {
            val m = LINE.find(l) ?: return
            val pid = m.groupValues[1]
            val msg = m.groupValues[2]
            HEADER.find(msg)?.let { h ->
                val fn = h.groupValues[1]
                val pkg = h.groupValues[2]
                pkgOf[pid] = pkg
                fnOf[pid] = fn
                if (fn == SET_ITEMS) {
                    progs[pkg] = LinkedHashMap()
                    // Nothing left to continue.
                    if (msg.trimEnd().endsWith("List()")) done(pkg, emptyList())
                }
                return
            }
            val n = ITEM.find(msg) ?: return
            val pkg = pkgOf[pid] ?: return
            val body = msg.substring(n.range.last + 1)
            when (fnOf[pid]) {
                SET_ITEMS -> prog(body)?.let { (id, p) -> progs.getOrPut(pkg) { LinkedHashMap() }[id] = p }
                SET_META -> meta(body)?.let { (id, mm) ->
                    val known = metas.getOrPut(pkg) { HashMap() }
                    val old = known[id]
                    // Each title comes once per language; yours wins.
                    if (old == null || mm.locale == locale || old.locale != locale) known[id] = mm
                }
                else -> return
            }
            if (n.groupValues[1] == n.groupValues[2]) {
                val list = items(pkg)
                // Titles for a new item come just after its progress: wait for them rather than
                // briefly losing the card.
                if (list.isNotEmpty() || progs[pkg].isNullOrEmpty()) done(pkg, list)
            }
        }

        private fun items(pkg: String): List<Item> = progs[pkg].orEmpty().mapNotNull { (id, p) ->
            metas[pkg]?.get(id)?.let { m ->
                Item(pkg, id, m.title, m.season, m.episode, m.art, m.link, m.linkUntil, m.page, p.progressMs, p.durationMs, p.watchedAt)
            }
        }

        private fun prog(body: String): Pair<String, Prog>? {
            val id = OPAQUE.find(body)?.groupValues?.get(1) ?: return null
            fun num(k: String) = Regex("""\b$k=(\d+)""").find(body)?.groupValues?.get(1)?.toLongOrNull()
            val at = num("lastWatchedTimestampMs") ?: return null
            return id to Prog(num("durationMs") ?: 0, num("progressMs") ?: 0, at)
        }

        private fun meta(body: String): Pair<String, Meta>? {
            val f = fields(body)
            val id = f["opaqueId"] ?: return null
            val title = f["title"]?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            val link = f["deeplinkUri"]?.takeIf { it.startsWith("http") }
            val payload = link?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()
            val until = EXPIRES.find(payload)?.groupValues?.get(1)?.toLongOrNull()?.let { it * 1000 - 10 * 60_000 } ?: Long.MAX_VALUE
            // Netflix's link names the title; its page opens long after the link itself expires.
            val page = if (link != null && "netflix.com" in link) NETFLIX_ID.find(payload)?.let { "https://www.netflix.com/title/${it.groupValues[1]}" } else null
            val art = f["tileImage"]?.let { IMAGE.find(it)?.groupValues?.get(1) }
            return id to Meta(
                title, f["seasonNumber"]?.toIntOrNull(), f["episodeNumber"]?.toIntOrNull(), art,
                link, until, page, f["locale"],
            )
        }
    }

    private val LINE = Regex("""^\w/$TAG\(\s*(\d+)\):\s?(.*)$""")
    private val HEADER = Regex("""function: (\w+) invoked by package: ([\w.]+)""")
    private val ITEM = Regex("""^\s*(\d+)/(\d+): \w+\(""")
    private val OPAQUE = Regex("""opaqueId=([^,)\s]+)""")
    private val EXPIRES = Regex("""expireAt=(\d+)""")
    private val NETFLIX_ID = Regex("""[?&]m=(\d+)""")
    private val IMAGE = Regex("""imageUri=([^,)\s]+)""")

    /** The fields Fire TV's item description has, in the order it writes them. */
    private val KEYS = listOf(
        "opaqueId", "title", "description", "shortDescription", "deeplinkUri", "locale", "tileImage", "heroImage",
        "maturityRatingSystem", "maturityRating", "statusFlags", "releaseYear", "progressMs", "durationMs",
        "seasonNumber", "episodeNumber", "episodeTitle", "actors", "directors", "isOriginal", "removeIntentUri",
    )

    /**
     * Splits `Name(a=1, b=one, two, c=x)`. A value can hold ", " itself, so each runs up to the next
     * field this knows. The log cuts long lines short, so the last value found may be cut too, and
     * is left out unless it's the final field.
     */
    internal fun fields(body: String): Map<String, String> {
        val found = KEYS.mapNotNull { k ->
            val i = if (k == KEYS.first()) body.indexOf("$k=") else body.indexOf(", $k=").let { if (it < 0) -1 else it + 2 }
            if (i < 0) null else Triple(k, i, i + k.length + 1)
        }.sortedBy { it.second }
        val out = HashMap<String, String>()
        found.forEachIndexed { n, (k, _, start) ->
            val next = found.getOrNull(n + 1)
            when {
                next != null -> out[k] = body.substring(start, next.second - 2)
                k == KEYS.last() && body.endsWith(")") -> out[k] = body.substring(start, body.length - 1)
            }
        }
        return out
    }

    private fun localeTag() = Locale.getDefault().let { "${it.language}_${it.country}" }

    private fun file(ctx: Context) = File(ctx.filesDir, "watching.json")

    /** Called on the log thread whenever the list changes. */
    @Volatile var changed: (() -> Unit)? = null

    /** Follows the log from this moment, after reading what it still holds. Returns the logcat process. */
    fun watch(ctx: Context): Process? = runCatching {
        val app = ctx.applicationContext
        val p = ProcessBuilder("logcat", "-v", "brief", "-b", "main", "-s", "$TAG:I").redirectErrorStream(true).start()
        val r = Reader(localeTag()) { pkg, items -> save(app, pkg, items) }
        thread(name = "watching", isDaemon = true) {
            runCatching { p.inputStream.bufferedReader().forEachLine(r::line) }.onFailure { Log.w("FireSkyWatching", "stopped", it) }
        }
        p
    }.onFailure { Log.w("FireSkyWatching", "can't read the log", it) }.getOrNull()

    @Synchronized
    fun save(ctx: Context, pkg: String, items: List<Item>) {
        val all = runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())
        all.put(pkg, JSONArray().apply { items.forEach { put(toJson(it)) } })
        val f = file(ctx)
        val tmp = File(f.path + ".part")
        tmp.writeText(all.toString())
        tmp.renameTo(f)
        Log.i("FireSkyWatching", "$pkg: ${items.size} to continue")
        changed?.invoke()
    }

    /** The one most recently watched in each app, if it was this week. */
    fun latest(ctx: Context, now: Long = System.currentTimeMillis()): List<Item> = all(ctx)
        .groupBy { it.pkg }
        .mapNotNull { (_, l) -> l.maxByOrNull { it.watchedAt } }
        .filter { now - it.watchedAt < MAX_AGE_MS }

    @Synchronized
    private fun all(ctx: Context): List<Item> = runCatching {
        val o = JSONObject(file(ctx).readText())
        o.keys().asSequence().flatMap { pkg ->
            val a = o.getJSONArray(pkg)
            (0 until a.length()).map { fromJson(pkg, a.getJSONObject(it)) }
        }.toList()
    }.getOrDefault(emptyList())

    private fun toJson(i: Item) = JSONObject()
        .put("id", i.id).put("title", i.title).put("season", i.season ?: -1).put("episode", i.episode ?: -1)
        .put("art", i.art ?: "").put("link", i.link ?: "").put("until", i.linkUntil).put("page", i.page ?: "")
        .put("pos", i.progressMs).put("dur", i.durationMs).put("at", i.watchedAt)

    private fun fromJson(pkg: String, o: JSONObject) = Item(
        pkg, o.getString("id"), o.getString("title"),
        o.optInt("season", -1).takeIf { it >= 0 }, o.optInt("episode", -1).takeIf { it >= 0 },
        o.optString("art").ifEmpty { null }, o.optString("link").ifEmpty { null }, o.optLong("until"),
        o.optString("page").ifEmpty { null }, o.optLong("pos"), o.optLong("dur"), o.optLong("at"),
    )
}
