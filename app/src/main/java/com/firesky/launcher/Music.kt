package com.firesky.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Music playing on the TV, from the music app's own media session: what it is, its cover, and the
 * two colours the cover is known for, which the idle screen's sky takes while it plays.
 */
object Music {
    private const val TAG = "FireSkyMusic"

    /** [key] says which song and which cover: a new song often comes with the last one's cover, and its own a moment later. */
    class Track(val pkg: String, val title: String, val artist: String, val cover: Bitmap?, val colours: IntArray, val key: String = "")

    /** What's playing, or null. Changes on the main thread. */
    @Volatile var now: Track? = null
        private set
    /** Called on the main thread when the music starts, changes or stops. */
    @Volatile var changed: ((Track?) -> Unit)? = null

    /** Apps that only play music. Others count when they say which album a thing is from. */
    private val APPS = setOf(
        "com.spotify.tv.android", "com.amazon.bueller.music", "com.google.android.youtube.tvmusic", "com.pandora.android.atv",
        "com.apple.android.music", "com.aspiro.tidal", "deezer.android.tv", "com.soundcloud.android",
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "music").apply { isDaemon = true } }
    /** Which track the cover being read is for, so a slow one for an old track is dropped. */
    @Volatile private var reading = ""

    internal fun isMusic(pkg: String, album: String?, artist: String?) = pkg in APPS || (!album.isNullOrBlank() && !artist.isNullOrBlank())

    /** How long a player has to stop changing what it says before the screen follows it. */
    internal const val SETTLE_MS = 1_000L
    /** But no longer than this, for a player that never stops. */
    private const val SETTLE_MAX_MS = 3_000L
    /** A song whose only picture is the app's icon waits this long for its own cover, which Spotify sends a second or so later. */
    internal const val ICON_WAIT_MS = 4_000L
    /** Each player's latest report, held until it settles, and when its first unsettled one came. */
    private val waiting = HashMap<String, Runnable>()
    private val since = HashMap<String, Long>()

    /**
     * A player reported a change. Main thread. Players say several things in a row: Spotify now and
     * then gives its own logo as the cover for a moment, then the song's again, and a new song comes
     * with the last one's cover first. So the screen follows once the player has said nothing new for a second.
     */
    fun report(pkg: String, st: PlaybackState?, meta: MediaMetadata?) {
        waiting.remove(pkg)?.let(main::removeCallbacks)
        val t = SystemClock.uptimeMillis()
        val first = since.getOrPut(pkg) { t }
        val r = Runnable { waiting.remove(pkg); since.remove(pkg); take(pkg, st, meta) }
        waiting[pkg] = r
        main.postDelayed(r, if (iconOnly(meta)) (first + ICON_WAIT_MS - t).coerceAtLeast(0) else (first + SETTLE_MAX_MS - t).coerceIn(0, SETTLE_MS))
    }

    /** Whether the only picture [meta] has is the display icon, which for Spotify is its logo. */
    private fun iconOnly(meta: MediaMetadata?) = meta != null &&
        meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) == null && meta.getBitmap(MediaMetadata.METADATA_KEY_ART) == null &&
        meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) == null && meta.getString(MediaMetadata.METADATA_KEY_ART_URI) == null &&
        (meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) != null || meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) != null)

    /** What a player says once it's settled. Pausing or stopping ends it, if it was the music. */
    private fun take(pkg: String, st: PlaybackState?, meta: MediaMetadata?) {
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.ifBlank { null }
        val artist = (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.ifBlank { null }
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val playing = st?.state == PlaybackState.STATE_PLAYING
        if (!playing || title == null || !isMusic(pkg, album, artist)) {
            if (now?.pkg == pkg) set(null)
            return
        }
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val artUri = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
        // The display icon is often just the app's own; the song already showing keeps its cover over it.
        val same = now?.let { it.pkg == pkg && it.title == title && it.artist == artist.orEmpty() && it.cover != null } == true
        if (art == null && artUri == null && same) return
        val bitmap = art ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = artUri ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val from = when { art != null -> "art"; bitmap != null -> "icon"; artUri != null -> "art link"; uri != null -> "icon link"; else -> "nothing" }
        val key = "$pkg|$title|$artist|${bitmap?.let(::print) ?: uri.orEmpty()}"
        if (now?.key == key || reading == key) return
        reading = key
        // The cover is read and its colours worked out off the main thread; a big one takes a moment.
        val copy = bitmap?.let { runCatching { Bitmap.createScaledBitmap(it, min(it.width, 320), min(it.height, 320), true) }.getOrNull() }
        io.execute {
            val cover = copy ?: uri?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { u ->
                runCatching { URL(u).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            val colours = cover?.let { colours(it) } ?: intArrayOf(NEUTRAL_A, NEUTRAL_B)
            val t = Track(pkg, title, artist.orEmpty(), cover, colours, key)
            main.post {
                if (reading != key) return@post
                reading = ""
                Log.i(TAG, "playing in $pkg, cover from $from${cover?.let { ", ${it.width}x${it.height}" } ?: ""}")
                set(t)
            }
        }
    }

    /** A cover's fingerprint: its size and a scatter of its pixels, enough to tell one cover from another. */
    internal fun print(b: Bitmap): String {
        if (b.isRecycled || b.width == 0 || b.height == 0) return "0"
        var h = b.width * 31 + b.height
        for (i in 0 until 25) h = h * 31 + b.getPixel((i % 5 * 2 + 1) * b.width / 10, (i / 5 * 2 + 1) * b.height / 10)
        return "${b.width}x${b.height}:${Integer.toHexString(h)}"
    }

    private fun set(t: Track?) {
        if (t == null && now == null) return
        if (t == null) reading = ""
        now = t
        changed?.invoke(t)
    }

    /** A soft violet and blue, for music with no cover to take colours from. */
    private const val NEUTRAL_A = 0xFF7A5CFF.toInt()
    private const val NEUTRAL_B = 0xFF3FA9F5.toInt()

    /**
     * The two colours a cover is known for: its strongest hue, and the next strongest well apart from
     * it, each the average of its own vivid pixels. A cover of one colour gives that colour twice,
     * the second a little deeper. Greys and near-blacks don't count; a cover of nothing else gives the soft default.
     */
    fun colours(b: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(b, 48, 48, true)
        val px = IntArray(48 * 48).also { small.getPixels(it, 0, 48, 0, 0, 48, 48) }
        if (small !== b) small.recycle()
        val n = 18
        val weight = DoubleArray(n); val r = DoubleArray(n); val g = DoubleArray(n); val bl = DoubleArray(n)
        val hsv = FloatArray(3)
        for (p in px) {
            Color.colorToHSV(p, hsv)
            val w = hsv[1] * hsv[1] * hsv[2]
            if (hsv[2] < .18f || hsv[1] < .2f) continue
            val k = ((hsv[0] / 360f) * n).toInt().coerceIn(0, n - 1)
            weight[k] += w; r[k] += (p shr 16 and 255) * w; g[k] += (p shr 8 and 255) * w; bl[k] += (p and 255) * w
        }
        // Each hue counts with its neighbours, so one spread across two buckets isn't split in half.
        val near = DoubleArray(n) { weight[it] + .5 * (weight[(it + 1) % n] + weight[(it + n - 1) % n]) }
        val first = near.indices.maxByOrNull { near[it] } ?: return intArrayOf(NEUTRAL_A, NEUTRAL_B)
        if (near[first] <= 0.0) return intArrayOf(NEUTRAL_A, NEUTRAL_B)
        fun colour(k: Int): Int {
            var w = 0.0; var rr = 0.0; var gg = 0.0; var bb = 0.0
            for (d in -1..1) { val j = (k + d + n) % n; w += weight[j]; rr += r[j]; gg += g[j]; bb += bl[j] }
            return Color.rgb((rr / w).toInt(), (gg / w).toInt(), (bb / w).toInt())
        }
        val apart = near.indices.filter { min(abs(it - first), n - abs(it - first)) >= 3 }
        val second = apart.maxByOrNull { near[it] }?.takeIf { near[it] > near[first] * .12 }
        val a = vivid(colour(first))
        val c = if (second != null) vivid(colour(second)) else Color.rgb((Color.red(a) * .6).toInt(), (Color.green(a) * .6).toInt(), max(40, (Color.blue(a) * .8).toInt()))
        return intArrayOf(a, c)
    }

    /**
     * The row's card for [t]: its two colours across it, the cover on the left, and the song and who
     * it's by beside it, so the card says what's playing at a glance.
     */
    fun card(t: Track, font: android.graphics.Typeface?): Bitmap {
        val w = 448; val h = 252
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        fun dark(c: Int, k: Float) = Color.rgb((Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())
        p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), dark(t.colours[0], .55f), dark(t.colours[1], .3f), android.graphics.Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val pad = 22f; val side = h - 2 * pad
        t.cover?.let { c ->
            val s = min(c.width, c.height)
            val src = android.graphics.Rect((c.width - s) / 2, (c.height - s) / 2, (c.width + s) / 2, (c.height + s) / 2)
            val dst = android.graphics.RectF(pad, pad, pad + side, pad + side)
            cv.save()
            cv.clipPath(android.graphics.Path().apply { addRoundRect(dst, 14f, 14f, android.graphics.Path.Direction.CW) })
            cv.drawBitmap(c, src, dst, p)
            cv.restore()
        }
        val x = if (t.cover != null) pad * 2 + side else pad
        val room = w - x - pad
        p.color = Color.WHITE; p.typeface = font
        fun fit(text: String, size: Float, weight: Int): String {
            p.textSize = size
            if (android.os.Build.VERSION.SDK_INT >= 28 && font != null) p.typeface = android.graphics.Typeface.create(font, weight, false)
            if (p.measureText(text) <= room) return text
            var n = text.length
            while (n > 1 && p.measureText(text.take(n) + "…") > room) n--
            return text.take(n).trimEnd() + "…"
        }
        val title = fit(t.title, 34f, 600)
        cv.drawText(title, x, h / 2f + 4f, p)
        if (t.artist.isNotEmpty()) {
            val by = fit(t.artist, 24f, 400)
            p.alpha = 200
            cv.drawText(by, x, h / 2f + 40f, p)
            p.alpha = 255
        }
        return b
    }

    /** A cover's colour made as rich as a tile's: averaging pixels greys it, so it's given back its saturation. */
    private fun vivid(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 1.35f + .1f).coerceAtMost(.92f)
        hsv[2] = hsv[2].coerceIn(.55f, .95f)
        return Color.HSVToColor(hsv)
    }

    /** For trying it out over adb: music as if it were playing, with these colours, or none. */
    fun test(title: String?, a: Int, b: Int, artist: String = "Test artist", pkg: String = "test") = main.post {
        set(title?.let { Track(pkg, it, artist, null, intArrayOf(a, b), "test|$it") })
    }
}
