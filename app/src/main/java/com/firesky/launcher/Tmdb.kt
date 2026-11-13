package com.firesky.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * New and popular films and shows on your services, with their art. TMDB supplies the titles and
 * pictures, and which service has them. Wikidata supplies each service's own ID where it knows one,
 * which is what lets OK land on the show rather than just open the app. Checked once a day.
 */
object Tmdb {
    private const val TAG = "FireSkyTitles"
    private const val API = "https://api.themoviedb.org/3/"
    private const val MAX_AGE_MS = 20 * 3_600_000L
    private const val PER_LIST = 6
    private const val NEUTRAL = 0xFF6A6A78.toInt()
    /** A selected Tonight card is about this many pixels wide on a 1080p screen. */
    private const val CARD_PX = 400
    private const val NEW_DAYS = 60
    /** News, reality, soap and talk: not what a home screen should lead with. */
    private const val SKIP_GENRES = "10763,10764,10766,10767"

    val on get() = Prefs.tmdbKey.isNotBlank()

    class Item(
        val id: Int, val movie: Boolean, val title: String, val isNew: Boolean,
        val pkg: String, val backdrop: String, val popularity: Double, val link: String?,
    ) {
        val key get() = "title:${if (movie) "m" else "t"}$id"
    }

    private class Service(val pkg: String, val provider: Int, val tvProp: String, val movieProp: String, val link: (Boolean, String) -> String)

    private val SERVICES = listOf(
        Service("com.netflix.ninja", 8, "P1874", "P1874") { _, id -> "https://www.netflix.com/title/$id" },
        Service("com.apple.atve.amazon.appletv", 350, "P9751", "P9586") { m, id -> "https://tv.apple.com/${Prefs.region.lowercase()}/${if (m) "movie" else "show"}/x/$id" },
        Service("com.amazon.firebat", 9, "P8055", "P8055") { _, id -> "https://watch.amazon.com/detail?asin=$id" },
        Service("com.hbo.hbonow", 1899, "P8298", "P8298") { _, id -> "https://play.hbomax.com/$id" },
        Service("com.hulu.plus", 15, "P6467", "P6466") { m, id -> "https://www.hulu.com/${if (m) "movie" else "series"}/$id" },
    )

    /**
     * The best few titles to show. Services you use most go first and take turns, so one service
     * can't fill the row. Within a service, what's new comes before what's merely popular.
     */
    fun pick(items: List<Item>, useMinutes: Map<String, Double>, hidden: Set<String>, n: Int): List<Item> {
        val perService = items
            .filter { it.key !in hidden }
            .distinctBy { it.key }
            .groupBy { it.pkg }
            .mapValues { (_, l) -> ArrayDeque(l.sortedWith(compareBy<Item> { !it.isNew }.thenByDescending { it.popularity })) }
        val order = perService.keys.sortedByDescending { useMinutes[it] ?: 0.0 }
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        while (out.size < n) {
            var any = false
            for (s in order) {
                val q = perService.getValue(s)
                while (q.isNotEmpty()) {
                    val i = q.removeFirst()
                    // The same show can be on two services; it appears once.
                    if (seen.add(i.key)) { out += i; any = true; break }
                }
                if (out.size == n) break
            }
            if (!any) break
        }
        return out
    }

    private fun get(url: String, agent: String = "FireSky/0.1"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", agent)
        return c.inputStream.use { it.bufferedReader().readText() }
    }

    /** The app a TMDB watch provider is, if FireSky knows it. Amazon is listed under two numbers. */
    fun pkgOf(provider: Int): String? = if (provider == 119) "com.amazon.firebat" else SERVICES.firstOrNull { it.provider == provider }?.pkg

    /** TMDB's number for an app's service. */
    fun providerOf(pkg: String): Int? = SERVICES.firstOrNull { it.pkg == pkg }?.provider

    /** Direct links into each title's app, from Wikidata, where it knows the app's own ID. */
    fun linksFor(items: List<Item>): Map<String, String> {
        val out = HashMap<String, String>()
        for ((pkg, l) in items.groupBy { it.pkg }) {
            val s = SERVICES.firstOrNull { it.pkg == pkg } ?: continue
            runCatching { out += links(s, l.take(50)) }.onFailure { Log.w(TAG, "no links for $pkg: ${it.message}") }
        }
        return out
    }

    internal fun api(path: String, vararg q: Pair<String, String>): JSONObject {
        val query = (q.toList() + ("api_key" to Prefs.tmdbKey))
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return JSONObject(get("$API$path?$query"))
    }

    private fun list(s: Service, movie: Boolean, since: String?): List<Item> {
        val q = mutableListOf(
            "watch_region" to Prefs.region, "with_watch_providers" to s.provider.toString(),
            "sort_by" to "popularity.desc", "include_adult" to "false", "language" to Prefs.language,
        )
        if (since != null) q += (if (movie) "primary_release_date.gte" else "first_air_date.gte") to since
        if (!movie) q += "without_genres" to SKIP_GENRES
        val r = api(if (movie) "discover/movie" else "discover/tv", *q.toTypedArray()).getJSONArray("results")
        val out = ArrayList<Item>()
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            val art = o.optString("backdrop_path").takeIf { it.startsWith("/") } ?: continue
            out += Item(o.getInt("id"), movie, o.optString(if (movie) "title" else "name"), since != null, s.pkg, art, o.optDouble("popularity", 0.0), null)
            if (out.size == PER_LIST) break
        }
        return out
    }

    /** Asks Wikidata which of these titles it knows the service's own ID for. */
    private fun links(s: Service, items: List<Item>): Map<String, String> {
        if (items.isEmpty()) return emptyMap()
        val wiki = "https://www.wikidata.org/w/api.php?format=json&action="
        val search = items.joinToString("|") { "${if (it.movie) "P4947" else "P4983"}=${it.id}" }
        val hits = JSONObject(get("${wiki}query&list=search&srlimit=50&srsearch=${URLEncoder.encode("haswbstatement:$search", "UTF-8")}"))
            .getJSONObject("query").getJSONArray("search")
        if (hits.length() == 0) return emptyMap()
        val ids = List(hits.length()) { hits.getJSONObject(it).getString("title") }.joinToString("|")
        val ents = JSONObject(get("${wiki}wbgetentities&props=claims&ids=${URLEncoder.encode(ids, "UTF-8")}")).getJSONObject("entities")
        val out = HashMap<String, String>()
        for (q in ents.keys()) {
            val c = ents.getJSONObject(q).optJSONObject("claims") ?: continue
            fun first(p: String) = c.optJSONArray(p)?.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")
            val tv = first("P4983"); val movie = first("P4947")
            for (it in items) {
                val mine = if (it.movie) movie == it.id.toString() else tv == it.id.toString()
                if (!mine) continue
                first(if (it.movie) s.movieProp else s.tvProp)?.let { v -> out[it.key] = s.link(it.movie, v) }
            }
        }
        return out
    }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "titles.json")
    private fun artDir(ctx: Context) = File(ctx.filesDir, "art").apply { mkdirs() }

    private fun toJson(items: List<Item>) = JSONArray().apply {
        items.forEach {
            put(JSONObject().put("id", it.id).put("movie", it.movie).put("title", it.title).put("new", it.isNew)
                .put("pkg", it.pkg).put("art", it.backdrop).put("pop", it.popularity).put("link", it.link ?: ""))
        }
    }

    private fun fromJson(a: JSONArray) = List(a.length()) {
        val o = a.getJSONObject(it)
        Item(o.getInt("id"), o.getBoolean("movie"), o.getString("title"), o.getBoolean("new"), o.getString("pkg"),
            o.getString("art"), o.getDouble("pop"), o.getString("link").ifBlank { null })
    }

    /** The titles as last read or fetched, so coming home doesn't read and parse the file each time. */
    @Volatile private var held: List<Item>? = null

    fun cached(ctx: Context): List<Item> = held
        ?: runCatching { fromJson(JSONArray(cacheFile(ctx).readText())) }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) held = it }

    fun stale(ctx: Context) = System.currentTimeMillis() - cacheFile(ctx).lastModified() > MAX_AGE_MS

    /** Drops what's kept, after the key or the country changes. */
    fun forget(ctx: Context) { held = null; cacheFile(ctx).delete() }

    /** Fetches today's titles. One service failing doesn't lose the others. */
    fun refresh(ctx: Context): List<Item>? {
        if (!on) return null
        val since = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - NEW_DAYS * 86_400_000L))
        val all = ArrayList<Item>()
        for (s in SERVICES) {
            runCatching {
                val found = list(s, false, since) + list(s, true, since) + list(s, false, null) + list(s, true, null)
                val one = found.distinctBy { it.key }
                val ids = runCatching { links(s, one) }.getOrDefault(emptyMap())
                one.mapTo(all) { Item(it.id, it.movie, it.title, it.isNew, it.pkg, it.backdrop, it.popularity, ids[it.key]) }
            }.onFailure { Log.w(TAG, "couldn't load ${s.pkg}: ${it.message}") }
        }
        if (all.isEmpty()) return null
        cacheFile(ctx).writeText(toJson(all).toString())
        held = all
        Log.i(TAG, "loaded ${all.size} titles, ${all.count { it.link != null }} with direct links")
        return all
    }

    /** The picture on disk, fetched the first time it's needed. */
    fun art(ctx: Context, backdrop: String): File? = fetch(ctx, "https://image.tmdb.org/t/p/w1280$backdrop", backdrop.trimStart('/'))

    /** Any picture, kept on disk under [name] alongside TMDB's. */
    fun fetch(ctx: Context, url: String, name: String): File? = runCatching {
        val f = File(artDir(ctx), name)
        if (!f.exists()) {
            val tmp = File(f.path + ".part")
            URL(url).openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(f)
            trim(ctx)
        }
        f
    }.getOrNull()

    private fun trim(ctx: Context) {
        artDir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.drop(80)?.forEach { it.delete() }
    }

    class CardArt(val thumb: ImageBitmap, val accent: Int)

    /**
     * Whether a title's lettering will read on a dark page: what's drawn, not the clear around it, has
     * to be bright in some colour. Red lettering is fine; black or navy lettering isn't.
     */
    fun light(b: android.graphics.Bitmap): Boolean {
        val small = android.graphics.Bitmap.createScaledBitmap(b, 64, (64 * b.height / b.width.coerceAtLeast(1)).coerceIn(4, 64), true)
        val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
        if (small !== b) small.recycle()
        var lum = 0.0; var n = 0.0
        for (p in px) {
            val a = (p ushr 24) / 255.0
            if (a < .5) continue
            lum += a * maxOf(p shr 16 and 255, p shr 8 and 255, p and 255) / 255; n += a
        }
        return n > 0 && lum / n > .45
    }

    /** The colour a picture is known for: its most vivid tones, averaged. */
    fun accent(px: IntArray): Int {
        var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
        for (p in px) {
            val pr = p shr 16 and 255; val pg = p shr 8 and 255; val pb = p and 255
            val hi = maxOf(pr, pg, pb); val lo = minOf(pr, pg, pb)
            if (hi < 40) continue
            val sat = (hi - lo) / hi.toDouble()
            val w = sat * sat * (hi / 255.0)
            r += pr * w; g += pg * w; b += pb * w; total += w
        }
        if (total < px.size * .01) return NEUTRAL
        return (0xFF shl 24) or ((r / total).toInt() shl 16) or ((g / total).toInt() shl 8) or (b / total).toInt()
    }

    /** A card-sized copy and its colour. The art stays on the card; the backdrop only takes the colour. */
    fun card(f: File): CardArt? = runCatching {
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, size)
        // Halved only while that still leaves enough for a selected card on a 1080p screen.
        var sample = 1
        while (size.outWidth / (sample * 2) >= CARD_PX) sample *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }) ?: return null
        val small = android.graphics.Bitmap.createScaledBitmap(b, 32, 18, true)
        val px = IntArray(32 * 18)
        small.getPixels(px, 0, 32, 0, 0, 32, 18)
        if (small !== b) small.recycle()
        CardArt(b.asImageBitmap(), accent(px))
    }.getOrNull()

    /** Art for something Kodi is playing, found by its title and remembered. */
    fun find(ctx: Context, title: String): String? {
        if (!on) return null
        val p = ctx.getSharedPreferences("titles", Context.MODE_PRIVATE)
        p.getString("art:$title", null)?.let { return it.ifBlank { null } }
        return runCatching {
            val r = api("search/multi", "query" to title).getJSONArray("results")
            val hit = (0 until r.length()).map(r::getJSONObject).firstOrNull { it.optString("backdrop_path").startsWith("/") }
            val art = hit?.getString("backdrop_path")
            p.edit().putString("art:$title", art ?: "").apply()
            art
        }.getOrNull()
    }
}
