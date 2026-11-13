package com.firesky.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The real sky over this TV, as the backdrop shows it: every star brighter than the eye's limit in
 * a dark garden, the moon in its phase, the planets, the space station when it passes and meteor
 * showers on their nights, all where they really are, and in space the Milky Way too. The backdrop
 * is a window facing south that holds still; only the northern lights turn it north.
 */
object Heavens {
    const val W = 1280f
    private const val D = PI / 180

    /* ---------- for testing from adb only: see the sky as it isn't right now (LiveView.test) ---------- */

    /** Added to the clock, so the sky shows a moment to come. Zero normally. */
    @Volatile var ahead = 0L
    /** Cloud cover, and storm index, to show instead of the real ones. */
    @Volatile var cloudTest: Int? = null
    @Volatile var kpTest: Double? = null
    fun now() = System.currentTimeMillis() + ahead

    /* ---------- the catalogue: loaded once, then lives on the graphics chip ---------- */

    /**
     * [names] are the better-known constellations, placed at [namePos] (ra, dec in radians). [deep]
     * holds the galaxies, nebulae and clusters space shows, [DEEP_STRIDE] floats each.
     */
    class Catalog(val stars: FloatArray, val milky: FloatArray, val lines: FloatArray, val names: List<String>, val namePos: FloatArray, val deep: FloatArray)

    /** ra, dec (radians), magnitude, then colour r g b, per star; the Milky Way's points are laid out alike, with a brightness for the magnitude. */
    const val STAR_STRIDE = 6

    /** ra, dec (radians), size drawn (degrees), colour r g b, then axis ratio, angle (degrees east of north), kind and brightness. */
    const val DEEP_STRIDE = 10

    /** The kinds of thing in [DEEP], as the shader draws them. */
    const val SPIRAL = 0f; const val ELLIPTICAL = 1f; const val GLOWING = 2f; const val DUSTY = 3f; const val GLOBULAR = 4f; const val IRREGULAR = 5f

    private class Deep(val ra: Double, val dec: Double, val size: Double, val ratio: Double, val pa: Double, val kind: Float, val bright: Float, val col: Int)

    /**
     * Galaxies, clouds of gas and star clusters, where they really are. Most are far too small or faint
     * to see from a garden, so they're drawn larger than life, as a long exposure shows them: true size
     * times the boost given, and never smaller than a smudge. Positions and shapes from NED and SEDS.
     */
    private val DEEP = listOf(
        Deep(10.6847, 41.2690, 3.17 * 2.6, .31, 35.0, SPIRAL, 1f, 0xC8D2FF), // Andromeda
        Deep(23.4621, 30.6599, 1.18 * 3, .59, 23.0, SPIRAL, .7f, 0xC0D0FF), // Triangulum
        Deep(80.8939, -69.7561, 10.75, .85, 170.0, IRREGULAR, .8f, 0xD8DCFF), // Large Magellanic Cloud
        Deep(13.1867, -72.8286, 5.33 * 1.3, .58, 45.0, IRREGULAR, .65f, 0xD8DCFF), // Small Magellanic Cloud
        Deep(148.8882, 69.0653, .45 * 5, .52, 157.0, SPIRAL, .7f, 0xD0D8FF), // Bode's Galaxy
        Deep(148.9697, 69.6797, .19 * 6, .38, 65.0, SPIRAL, .6f, 0xFFD8C8), // Cigar Galaxy
        Deep(202.4696, 47.1952, .19 * 7, .62, 163.0, SPIRAL, .65f, 0xC8D4FF), // Whirlpool
        Deep(210.8023, 54.3490, .48 * 4, .93, 0.0, SPIRAL, .6f, 0xC8D4FF), // Pinwheel
        Deep(189.9976, -11.6231, .145 * 8, .4, 90.0, SPIRAL, .65f, 0xFFE6CC), // Sombrero
        Deep(201.3651, -43.0191, .43 * 5, .78, 35.0, ELLIPTICAL, .6f, 0xFFE2C8), // Centaurus A
        Deep(11.8880, -25.2882, .46 * 5, .25, 52.0, SPIRAL, .65f, 0xD0D8FF), // Sculptor Galaxy
        Deep(187.7059, 12.3911, .14 * 8, .8, 0.0, ELLIPTICAL, .5f, 0xFFE2C8), // M87
        Deep(83.8221, -5.3911, 1.1 * 4, .75, 0.0, GLOWING, 1f, 0xFF7A9A), // Orion Nebula
        Deep(270.9042, -24.3867, 1.5 * 3.5, .45, 90.0, GLOWING, .9f, 0xFF7090), // Lagoon Nebula
        Deep(161.2650, -59.8667, 2.0 * 3, .9, 0.0, GLOWING, .95f, 0xFF7898), // Carina Nebula
        Deep(314.7500, 44.3300, 2.0 * 2.6, .83, 0.0, GLOWING, .7f, 0xFF6A88), // North America Nebula
        Deep(97.9800, 4.9500, 1.3 * 3, 1.0, 0.0, GLOWING, .65f, 0xFF6A88), // Rosette Nebula
        Deep(56.8500, 24.1167, 1.8 * 2, 1.0, 0.0, DUSTY, .55f, 0x8CB4FF), // Pleiades
        Deep(201.6968, -47.4795, .6 * 4, 1.0, 0.0, GLOBULAR, .7f, 0xFFF0D8), // Omega Centauri
        Deep(250.4235, 36.4613, .33 * 5, 1.0, 0.0, GLOBULAR, .6f, 0xFFF0D8), // Hercules Cluster
    )

    private fun deep(): FloatArray {
        val out = FloatArray(DEEP.size * DEEP_STRIDE)
        DEEP.forEachIndexed { i, d ->
            val o = i * DEEP_STRIDE
            out[o] = (d.ra * D).toFloat(); out[o + 1] = (d.dec * D).toFloat(); out[o + 2] = d.size.toFloat()
            out[o + 3] = (d.col shr 16 and 255) / 255f; out[o + 4] = (d.col shr 8 and 255) / 255f; out[o + 5] = (d.col and 255) / 255f
            out[o + 6] = d.ratio.toFloat(); out[o + 7] = d.pa.toFloat(); out[o + 8] = d.kind; out[o + 9] = d.bright
        }
        return out
    }

    @Volatile private var held: Catalog? = null

    /** Read once and kept: the stars don't change, and reading them takes a moment the first frame can't spare. */
    @Synchronized
    fun catalog(ctx: Context): Catalog = held ?: read(ctx).also { held = it }

    private fun read(ctx: Context): Catalog {
        val j = JSONObject(ctx.assets.open("sky.json").bufferedReader().readText())
        val s = j.getJSONArray("stars")
        val stars = FloatArray(s.length() * STAR_STRIDE)
        for (i in 0 until s.length()) {
            val r = s.getJSONArray(i)
            val c = colour(r.getDouble(3))
            stars[i * 6] = (r.getDouble(0) * D).toFloat(); stars[i * 6 + 1] = (r.getDouble(1) * D).toFloat()
            stars[i * 6 + 2] = r.getDouble(2).toFloat(); stars[i * 6 + 3] = c[0]; stars[i * 6 + 4] = c[1]; stars[i * 6 + 5] = c[2]
        }
        val n = j.getJSONArray("names")
        val names = ArrayList<String>(n.length()); val pos = FloatArray(n.length() * 2)
        for (i in 0 until n.length()) {
            val r = n.getJSONArray(i)
            pos[i * 2] = (r.getDouble(0) * D).toFloat(); pos[i * 2 + 1] = (r.getDouble(1) * D).toFloat(); names += r.getString(2)
        }
        return Catalog(stars, milkyWay(j.getJSONArray("milky")), lines(j.getJSONArray("lines")), names, pos, deep())
    }

    private fun colour(bv: Double): FloatArray {
        val t = bv.coerceIn(-.3, 1.8)
        return when {
            t < .3 -> { val k = (t + .3) / .6; floatArrayOf(((170 + 85 * k) / 255).toFloat(), ((196 + 50 * k) / 255).toFloat(), 1f) }
            t < .9 -> { val k = (t - .3) / .6; floatArrayOf(1f, ((246 - 16 * k) / 255).toFloat(), ((236 - 46 * k) / 255).toFloat()) }
            else -> { val k = (t - .9) / .9; floatArrayOf(1f, ((230 - 50 * k) / 255).toFloat(), ((190 - 80 * k) / 255).toFloat()) }
        }
    }

    /**
     * The Milky Way, as soft points spread over its real outline: ra, dec, how many of the outline's
     * five steps of brightness each lies inside, and how much warmer than starlight it is, which it is
     * most towards the centre of the galaxy, in Sagittarius.
     */
    private fun milkyWay(a: JSONArray): FloatArray {
        val out = FloatArray(a.length() * STAR_STRIDE)
        for (i in 0 until a.length()) {
            val r = a.getJSONArray(i); val warm = r.getDouble(3).toFloat(); val o = i * STAR_STRIDE
            out[o] = (r.getDouble(0) * D).toFloat(); out[o + 1] = (r.getDouble(1) * D).toFloat(); out[o + 2] = r.getDouble(2).toFloat()
            out[o + 3] = .74f + .26f * warm; out[o + 4] = .8f + .08f * warm; out[o + 5] = 1f - .3f * warm
        }
        return out
    }

    /** Constellation figures as pairs of points, for GL_LINES. */
    private fun lines(a: JSONArray): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until a.length()) {
            val fig = a.getJSONArray(i)
            for (k in 0 until fig.length()) {
                val seg = fig.getJSONArray(k)
                for (p in 0 until seg.length() - 1) {
                    for (q in listOf(seg.getJSONArray(p), seg.getJSONArray(p + 1))) { out += (q.getDouble(0) * D).toFloat(); out += (q.getDouble(1) * D).toFloat() }
                }
            }
        }
        return out.toFloatArray()
    }

    /* ---------- what's known about tonight, refreshed off the drawing thread ---------- */

    @Volatile var orbit: Orbit? = null
    @Volatile var passes: List<Orbit.Pass> = emptyList()
    @Volatile var kp = 0.0
    @Volatile private var kpAt = 0L
    @Volatile private var passesAt = 0L
    @Volatile private var passesFor = ""

    /** Fetches the station's orbit and the geomagnetic index as they go stale. Off the main thread. */
    fun refresh(ctx: Context) {
        val here = Place.get(ctx)
        val now = System.currentTimeMillis()
        Orbit.iss(ctx)?.let { o ->
            // A day and a half of passes takes thousands of positions to find, so they're found again
            // only for a new orbit, a new place, or every few hours.
            if (o !== orbit || here.key != passesFor || now - passesAt > 3 * 3_600_000 || now < passesAt) {
                orbit = o
                passes = o.passes(now - 15 * 60_000, now + 36 * 3_600_000, here.lat, here.lon)
                passesAt = now
                passesFor = here.key
            }
        }
        if (now - kpAt > 3_600_000) runCatching {
            val c = URL("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val a = JSONArray(c.inputStream.use { it.bufferedReader().readText() })
            val last = a.get(a.length() - 1)
            kp = if (last is JSONObject) last.getDouble("Kp") else (last as JSONArray).getString(1).toDouble()
            kpAt = now
        }
    }

    /** How strongly northern lights could show here tonight: 0 most of the time, more the further north. */
    fun auroraChance(lat: Double, lon: Double, kp: Double): Float {
        val p = 80.8 * D; val pl = -72.6 * D; val la = kotlin.math.abs(lat) * D
        val mag = asin(sin(la) * sin(p) + cos(la) * cos(p) * cos(lon * D - pl)) / D
        val need = (66 - mag) / 1.9
        return ((kp - need + .5) / 1.5).coerceIn(0.0, 1.0).toFloat()
    }

    /* ---------- meteor showers, IMO calendar; peaks fall on about the same dates each year ---------- */

    class Shower(val name: String, val month: Int, val day: Int, val zhr: Double, val ra: Double, val dec: Double, val spread: Double, val fast: Boolean, val slow: Boolean = false)

    val SHOWERS = listOf(
        Shower("Quadrantids", 1, 4, 80.0, 230.0, 49.0, .4, false),
        Shower("Lyrids", 4, 22, 18.0, 271.0, 34.0, .8, true),
        Shower("Eta Aquariids", 5, 6, 50.0, 338.0, -1.0, 2.5, true),
        Shower("Perseids", 8, 12, 100.0, 48.0, 58.0, 2.0, true),
        Shower("Orionids", 10, 21, 20.0, 95.0, 16.0, 2.0, true),
        Shower("Southern Taurids", 11, 5, 5.0, 52.0, 15.0, 8.0, false, slow = true),
        Shower("Northern Taurids", 11, 12, 5.0, 58.0, 22.0, 8.0, false, slow = true),
        Shower("Leonids", 11, 17, 15.0, 152.0, 22.0, 1.2, true),
        Shower("Geminids", 12, 14, 150.0, 112.0, 33.0, 1.0, false),
    )

    fun rate(s: Shower, ms: Long): Double {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        val peak = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(c.get(Calendar.YEAR), s.month - 1, s.day, 12, 0) }
        var dd = kotlin.math.abs(ms - peak.timeInMillis) / 86_400_000.0
        if (dd > 182) dd = 365 - dd
        return if (dd > s.spread * 4) 0.0 else s.zhr * exp(-dd / s.spread)
    }

    /* ---------- one moment of the sky, ready to draw ---------- */

    /** The camera. It faces south, low, or the northern lights when they're likely. */
    class View(var az: Double = 180.0, var pitch: Double = BASE_PITCH)

    const val BASE_PITCH = 25.0
    /** Space looks higher, with no ground to keep in view. */
    const val SPACE_PITCH = 32.0

    class Frame {
        // Projection, in the backdrop's 1280x720 frame.
        var lst = 0f; var sinLat = 0f; var cosLat = 1f
        var viewAz = 0f; var sinP = 0f; var cosP = 1f; var r = 1f; var cx = 640f; var cy = 300f
        /** How far the land moves for the camera: sideways in pixels, and down when it looks up. */
        var pan = 0f; var lift = 0f
        /** Faintest star showing; and how much of the sky is clear of cloud and daylight. */
        var lim = 5.4f; var clear = 1f; var dark = 1f
        var milky = 1f
        var sunX = 0f; var sunY = 0f; var sunIn = false
        var aurora = 0f
        /** Screen-space extras: x, y, size, r, g, b, a, kind (0 glow dot, 1 moon), per point. */
        val dots = FloatArray(MAX_DOTS * 8); var dotCount = 0
        /** The aurora as a curtain of triangles: x, y, height up it (0 to 1), strength, place along it. */
        val curtain = FloatArray(MAX_CURTAIN * CURTAIN_STRIDE); var curtainCount = 0
        var moonLight = floatArrayOf(0f, 0f, 1f)
        /** Out among the stars: no ground, no air, always night. */
        var space = false
        /** The curtain is music's, in its colours, rather than a real aurora. */
        var musicCurtain = false
        /** Where each constellation's name goes, x, y and how much it shows, in space only. */
        var labels = FloatArray(0)
        /** The station is up and lit, so the sky should move. */
        var moving = false
        var issAlt = -90.0; var issAz = 0.0
    }

    const val MAX_DOTS = 256
    const val MAX_CURTAIN = 900
    const val CURTAIN_STRIDE = 5

    fun radius(pitch: Double): Double { val l = 62 * D; val k = 2 / (1 + cos(pitch * D) * cos(l)); return (W / 2) / (k * sin(l)) }

    /** The camera's centre on screen, so that facing south at the usual tilt the horizon lies where the land begins. */
    fun centreY(hz: Double) = hz - radius(BASE_PITCH) * 2 * tan(BASE_PITCH * D / 2)

    fun project(f: Frame, alt: Double, az: Double): FloatArray? {
        val p = alt * D; var l = (az - f.viewAz) % 360; if (l > 180) l -= 360; if (l < -180) l += 360
        val lr = l * D
        val den = 1 + f.sinP * sin(p) + f.cosP * cos(p) * cos(lr)
        if (den < .25) return null
        val k = 2 / den
        return floatArrayOf((f.cx + f.r * k * cos(p) * sin(lr)).toFloat(), (f.cy - f.r * k * (f.cosP * sin(p) - f.sinP * cos(p) * cos(lr))).toFloat())
    }

    /**
     * The aurora: a rippling curtain along the horizon towards the pole, green low down and red up
     * high, taller and brighter the stronger the storm. Returns the vertices written.
     */
    private fun curtain(f: Frame, ms: Long, pole: Double, k: Double): Int {
        val tw = (ms % 3_600_000L) / 1000.0
        val seen = sqrt(k)
        var n = 0; var prev: FloatArray? = null
        fun put(x: Float, y: Float, v: Float, a: Float, ray: Float) {
            val o = n * CURTAIN_STRIDE
            f.curtain[o] = x; f.curtain[o + 1] = y; f.curtain[o + 2] = v; f.curtain[o + 3] = a; f.curtain[o + 4] = ray; n++
        }
        for (i in -70..70) {
            val wave = sin(i * .09 + tw * .35) + .6 * sin(i * .23 - tw * .6) + .3 * sin(i * .51 + tw * 1.1)
            val s = max(0.0, .55 + .45 * wave) * exp(-(i / 60.0) * (i / 60.0))
            // A storm strong enough to reach this far comes higher up the sky, clear of the hills.
            val lo = 6 + 22 * k + 3 * sin(i * .05 + tw * .2)
            val b = project(f, lo, pole + i)
            val t = project(f, lo + 12 + (10 + 18 * k) * s, pole + i)
            val cur = if (b != null && t != null) floatArrayOf(b[0], b[1], t[0], t[1], (s * seen).toFloat(), i.toFloat()) else null
            val p = prev
            if (p != null && cur != null && n + 6 <= MAX_CURTAIN) {
                put(p[0], p[1], 0f, p[4], p[5]); put(p[2], p[3], 1f, p[4], p[5]); put(cur[0], cur[1], 0f, cur[4], cur[5])
                put(cur[0], cur[1], 0f, cur[4], cur[5]); put(p[2], p[3], 1f, p[4], p[5]); put(cur[2], cur[3], 1f, cur[4], cur[5])
            }
            prev = cur
        }
        return n
    }

    private val trail = ArrayDeque<DoubleArray>()

    class Meteor(val x: Float, val y: Float, val dx: Float, val dy: Float, val len: Float, val life: Float, val bright: Float, val col: FloatArray, var age: Float = 0f)
    val meteors = ArrayList<Meteor>()
    private var rnd = java.util.Random()

    /**
     * Everything needed to draw the sky at [ms], seen from [lat],[lon], with the land's horizon at
     * [hz] pixels and [cloud] percent of the sky clouded over. Moves [view] towards the station.
     */
    fun frame(f: Frame, ms: Long, lat: Double, lon: Double, hz: Double, cloud: Int, view: View, dt: Float, space: Boolean = false, facing: Double = 180.0, names: Catalog? = null, music: Float = 0f) {
        val lst = Astro.lst(ms, lon)
        val sun = Astro.horizon(Astro.sun(ms), lst, lat)
        val iss = orbit?.seen(ms, lat, lon)
        val issUp = iss != null && iss.alt > 0 && iss.lit > .05 && sun.alt < -4
        // Space has no daylight, no air for an aurora to light, and no cloud.
        val dark = if (space) 1.0 else ((-sun.alt - 6) / 10).coerceIn(0.0, 1.0)
        val aurora = if (space) 0.0 else auroraChance(lat, lon, kpTest ?: kp) * dark * (1 - .95 * cloud / 100.0)
        @Suppress("NAME_SHADOWING") val cloud = if (space) 0 else cloud
        f.space = space
        // The aurora hangs over the pole's side of the sky.
        val pole = if (lat >= 0) 0.0 else 180.0
        // The view faces the place's own way, or the aurora when it's likely. It never turns: the TV can't
        // draw a turning sky smoothly, and a stuttering one was hard to watch. A change of view shows
        // with the picture's usual fade instead, and the station simply crosses the view when it's in it.
        val rest = if (space) SPACE_PITCH else BASE_PITCH
        view.az = if (aurora > .25) pole else facing
        view.pitch = if (aurora > .25) BASE_PITCH + 14 * aurora else rest
        f.moving = issUp || meteors.isNotEmpty() || aurora > .02
        f.issAlt = iss?.alt ?: -90.0; f.issAz = iss?.az ?: 0.0

        f.lst = lst.toFloat(); f.sinLat = sin(lat * D).toFloat(); f.cosLat = cos(lat * D).toFloat()
        f.viewAz = view.az.toFloat(); f.sinP = sin(view.pitch * D).toFloat(); f.cosP = cos(view.pitch * D).toFloat()
        f.r = radius(view.pitch).toFloat(); f.cy = centreY(hz).toFloat(); f.cx = W / 2
        val horizonY = f.cy + f.r * 2 * tan(view.pitch * D / 2)
        f.lift = (horizonY - hz).toFloat()
        f.pan = ((view.az - 180) * D * f.r * 2 / (1 + cos(view.pitch * D))).toFloat()

        val moon = Astro.moonFromHere(Astro.horizon(Astro.moon(ms).eq, lst, lat))
        val lit = Astro.moonLit(ms)
        val moonUp = if (moon.alt > 0) lit else 0.0
        f.dark = dark.toFloat()
        var lim = when {
            sun.alt < -18 -> 5.4
            sun.alt < -12 -> 4.2 + 1.2 * (-sun.alt - 12) / 6
            sun.alt < -6 -> 1.5 + 2.7 * (-sun.alt - 6) / 6
            sun.alt < -2 -> -.5 + 2 * (-sun.alt - 2) / 4
            else -> -2.0
        }
        lim -= .9 * moonUp
        // Nothing lights the sky out there, so every star in the catalogue shows, the moon or no.
        if (space) lim = 6.2
        f.lim = lim.toFloat()
        f.clear = (1 - .95 * cloud / 100.0).toFloat()
        // Only out in space. Over a place it showed as a white cloud at the view's edge, where the view stretches its patches.
        f.milky = if (space) 1f else 0f
        f.aurora = aurora.toFloat()
        // Music on the idle screen at night hangs a curtain of its own across the view, in its colours.
        val musicK = if (space) 0.0 else music * dark * (1 - .8 * cloud / 100.0)
        f.musicCurtain = aurora <= .02 && musicK > .03
        f.curtainCount = if (aurora > .02) curtain(f, ms, pole, aurora) else if (f.musicCurtain) curtain(f, ms, view.az, .55 * musicK) else 0
        if (f.musicCurtain) f.moving = true

        val sp = project(f, max(sun.alt, -30.0), sun.az)
        // Space is kept to night: the sun would drown everything else.
        f.sunIn = !space && sp != null && sp[0] > -200 && sp[0] < W + 200
        // Below the horizon still shows in space, as far as the camera sees.
        val floor = if (space) -40.0 else 0.0
        if (sp != null) { f.sunX = sp[0] / W; f.sunY = sp[1] / 720f }

        var n = 0
        fun dot(x: Float, y: Float, size: Float, r: Float, g: Float, b: Float, a: Float, kind: Float) {
            if (n >= MAX_DOTS || a <= .004f) return
            val o = n * 8
            f.dots[o] = x; f.dots[o + 1] = y; f.dots[o + 2] = size; f.dots[o + 3] = r; f.dots[o + 4] = g; f.dots[o + 5] = b; f.dots[o + 6] = a; f.dots[o + 7] = kind
            n++
        }
        // Planets.
        for (p in Astro.planets(ms)) {
            val h = Astro.horizon(p.eq, lst, lat); if (h.alt < floor || p.mag > lim) continue
            val q = project(f, h.alt, h.az) ?: continue
            val k = (min(1.0, (lim - p.mag) / 1.5 + .3) * f.clear).toFloat()
            val col = when (p.name) { "Mars" -> floatArrayOf(1f, .67f, .51f); "Saturn" -> floatArrayOf(1f, .9f, .69f); else -> floatArrayOf(1f, .97f, .9f) }
            dot(q[0], q[1], (max(1.6, min(4.0, 2.8 - .45 * p.mag)) * 9).toFloat(), col[0], col[1], col[2], k, 0f)
        }
        // The moon, larger than life so it reads across a room, lit from the sun's side.
        if (moon.alt > (if (space) floor else -1.0)) project(f, moon.alt, moon.az)?.let { q ->
            // The direction to the sun on screen: a short step from the moon along the great circle towards it.
            val mv = vec(moon); val sv = vec(sun)
            val dp = mv[0] * sv[0] + mv[1] * sv[1] + mv[2] * sv[2]
            var tx = sv[0] - dp * mv[0]; var ty = sv[1] - dp * mv[1]; var tz = sv[2] - dp * mv[2]
            val tl = sqrt(tx * tx + ty * ty + tz * tz).takeIf { it > 1e-9 } ?: 1.0; tx /= tl; ty /= tl; tz /= tl
            val step = horiz(mv[0] + .02 * tx, mv[1] + .02 * ty, mv[2] + .02 * tz)
            val q2 = project(f, step.alt, step.az)
            var dx = 0f; var dy = 1f
            if (q2 != null) { dx = q2[0] - q[0]; dy = q2[1] - q[1]; val dl = sqrt(dx * dx + dy * dy).takeIf { it > 1e-6f } ?: 1f; dx /= dl; dy /= dl }
            val i = Math.acos((2 * lit - 1).coerceIn(-1.0, 1.0))
            f.moonLight = floatArrayOf((dx * sin(i)).toFloat(), (dy * sin(i)).toFloat(), cos(i).toFloat())
            dot(q[0], q[1], 34f, .93f, .95f, 1f, ((if (sun.alt > 0 && !space) .55 else 1.0) * (1 - cloud / 100.0 * .75)).toFloat(), 1f)
        }
        // The space station and the trail it leaves.
        if (iss != null && iss.alt > -2 && sun.alt < -4) {
            if (trail.isEmpty() || ms - trail.last()[0] > 400 || ms < trail.last()[0]) trail.addLast(doubleArrayOf(ms.toDouble(), iss.alt, iss.az, iss.lit))
            while (trail.isNotEmpty() && ms - trail.first()[0] > 40_000) trail.removeFirst()
            for (p in trail) {
                val q = project(f, p[1], p[2]) ?: continue
                val k = (1 - (ms - p[0]) / 40_000) * p[3]
                dot(q[0], q[1], 7f, 1f, .84f, .59f, (.28 * k * f.clear).toFloat(), 0f)
            }
            if (iss.alt > 0 && iss.lit > .02) project(f, iss.alt, iss.az)?.let { q ->
                val b = (max(.4, min(1.0, 900 / iss.km)) * iss.lit * f.clear).toFloat()
                dot(q[0], q[1], 30f, 1f, .96f, .9f, b, 0f)
            }
        } else trail.clear()
        // Meteors, drawn as a fading run of points from head to tail. They burn up in air, which space hasn't.
        if (space) meteors.clear() else spawn(f, ms, lst, lat, dt, cloud)
        val it = meteors.iterator()
        while (it.hasNext()) {
            val m = it.next(); m.age += dt
            val k = m.age / m.life; if (k >= 1) { it.remove(); continue }
            val head = m.len * min(1f, k * 1.3f); val tail = max(0f, head - m.len * .55f); val a = sin(PI * k).toFloat() * m.bright
            for (s in 0..14) {
                val u = s / 14f; val at = tail + (head - tail) * u
                dot(m.x + m.dx * at, m.y + m.dy * at, if (m.bright > 1) 6f else 4.5f, m.col[0], m.col[1], m.col[2], a * u * u, 0f)
            }
        }
        f.dotCount = n
        if (space && names != null) label(f, names, lst, lat)
    }

    /** Where each constellation's name sits now, fading out at the edges and over the cards and dock, where the screen is busy. */
    private fun label(f: Frame, c: Catalog, lst: Double, lat: Double) {
        if (f.labels.size != c.names.size * 3) f.labels = FloatArray(c.names.size * 3)
        for (i in c.names.indices) {
            val h = Astro.horizon(Astro.Eq(c.namePos[i * 2].toDouble(), c.namePos[i * 2 + 1].toDouble()), lst, lat)
            val q = project(f, h.alt, h.az)
            val o = i * 3
            if (q == null) { f.labels[o + 2] = 0f; continue }
            val x = q[0]; val y = q[1]
            val edge = min(min(x - 60f, W - 60f - x), min(y - 40f, 690f - y)).coerceAtLeast(0f) / 60f
            val busy = if (y > 400f) .35f else 1f
            f.labels[o] = x; f.labels[o + 1] = y; f.labels[o + 2] = min(1f, edge) * busy
        }
    }

    private fun vec(h: Astro.Hz) = doubleArrayOf(cos(h.alt * D) * cos(h.az * D), cos(h.alt * D) * sin(h.az * D), sin(h.alt * D))
    private fun horiz(x: Double, y: Double, z: Double): Astro.Hz { val l = sqrt(x * x + y * y + z * z); return Astro.Hz(asin(z / l) / D, (atan2(y, x) / D + 360) % 360) }

    /** Which showers are active and how strongly. It changes over days, so it's worked out once a minute, not every frame. */
    private var sources: List<Pair<Shower?, Double>> = emptyList()
    private var sourcesAt = -1L

    private fun spawn(f: Frame, ms: Long, lst: Double, lat: Double, dt: Float, cloud: Int) {
        if (f.dark < .5 || dt <= 0f) return
        val hours = dt / 3600.0
        val minute = ms / 60_000
        if (minute != sourcesAt) { sources = SHOWERS.map { it to rate(it, ms) }.filter { it.second > 0 } + (null to 6.0); sourcesAt = minute }
        for ((s, r) in sources) {
            var rate = r
            var radiant: FloatArray? = null
            if (s != null) {
                val h = Astro.horizon(Astro.Eq(s.ra * D, s.dec * D), lst, lat); if (h.alt < 0) continue
                rate *= sin(h.alt * D); radiant = project(f, h.alt, h.az)
            }
            // About a third of the whole sky is in view.
            if (rnd.nextDouble() >= rate * hours * .35 * (1 - cloud / 100.0)) continue
            val q = project(f, 22 + rnd.nextDouble() * 40, f.viewAz + (rnd.nextDouble() - .5) * 110) ?: continue
            var dx: Float; var dy: Float
            if (radiant != null) { dx = q[0] - radiant[0]; dy = q[1] - radiant[1] } else { val a = rnd.nextDouble() * 2 * PI; dx = cos(a).toFloat(); dy = (kotlin.math.abs(sin(a)) + .2).toFloat() }
            val l = sqrt(dx * dx + dy * dy).takeIf { it > 0 } ?: 1f; dx /= l; dy /= l
            val slow = s?.slow == true; val fast = s?.fast == true
            meteors += Meteor(q[0], q[1], dx, dy, ((if (slow) 180 else 110) + rnd.nextDouble() * 140).toFloat(), if (slow) 1.1f else if (fast) .45f else .7f,
                if (slow && rnd.nextDouble() < .3) 1.4f else (.6 + rnd.nextDouble() * .5).toFloat(),
                if (fast) floatArrayOf(.78f, 1f, .88f) else if (slow) floatArrayOf(1f, .78f, .59f) else floatArrayOf(.92f, .94f, 1f),
                // Born at the start of this step, so it isn't aged by the step that made it.
                age = -dt)
        }
    }

    /* ---------- for the home screen ---------- */

    /** A few words for the status row: the station about to pass or passing, or an aurora worth stepping out for. */
    fun pill(ms: Long, lat: Double, lon: Double, cloud: Int = 0): String? {
        passes.firstOrNull { it.end >= ms }?.let { p ->
            if (ms in p.start..p.end) orbit?.seen(ms, lat, lon)?.let { return "Space station now · ${compass(it.az)}" }
            val mins = kotlin.math.ceil((p.start - ms) / 60_000.0).toInt()
            if (mins in 1..15) return "Space station in $mins min"
        }
        val sun = Astro.horizon(Astro.sun(ms), Astro.lst(ms, lon), lat)
        val k = auroraChance(lat, lon, kpTest ?: kp) * ((-sun.alt - 6) / 10).coerceIn(0.0, 1.0) * (1 - (cloudTest ?: cloud) / 100.0)
        return if (k > .25) (if (lat >= 0) "Northern lights possible · look north" else "Southern lights possible · look south") else null
    }

    private val POINTS16 = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    fun compass(az: Double) = POINTS16[((((az % 360) + 360) % 360) / 22.5 + .5).toInt() % 16]
}
