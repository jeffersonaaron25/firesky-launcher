package com.firesky.launcher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** mulberry32. The same seed paints the same ridges as the approved mock. */
class Rng(seed: Int) {
    private var a = seed
    fun next(): Double {
        a += 0x6D2B79F5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

const val POINTS = 257

fun ridge(r: Rng, amp: Float, rough: Double): FloatArray {
    val n = POINTS - 1
    val p = FloatArray(POINTS)
    p[0] = r.next().toFloat(); p[n] = r.next().toFloat()
    var step = n
    var a = 1.0
    while (step > 1) {
        val hf = step shr 1
        var i = hf
        while (i < n) {
            p[i] = ((p[i - hf].toDouble() + p[i + hf]) / 2 + (r.next() - .5) * a).toFloat()
            i += step
        }
        a *= rough; step = hf
    }
    val mn = p.min()
    val span = (p.max() - mn).takeIf { it != 0f } ?: 1f
    return FloatArray(POINTS) { (p[it] - mn) / span * amp }
}

/** Sun times in minutes after midnight. */
class Sun(val rise: Int, val set: Int)

/**
 * The landscape as numbers. The scene is one list of floats, so any moment of the day, and any
 * tile's colour, can blend into any other.
 */
object Sky {
    const val N = 43
    const val HZ = 25; const val WATER = 26; const val STARS = 27; const val AURORA = 28
    const val SUN_X = 29; const val SUN_Y = 30; const val SUN_R = 31; const val CORE = 32; const val GLOW = 35
    const val MOON = 38; const val SUN_A = 39; const val CLOUD = 40

    const val CLEAR = 0; const val CLOUDY = 1; const val RAIN = 2; const val SNOW = 3; const val FOG = 4

    val DEFAULT_SUN = Sun(7 * 60, 17 * 60)

    /**
     * Where the mountains' feet are, as a share of the screen's height, the same at every hour: the
     * real sky is the same whatever the time and whichever app, so nothing under it may move either.
     * Every place stands on it; the sky's own horizon is [Places.HORIZON] above it.
     */
    const val HORIZON = .76f

    class Key(
        val sky: FloatArray, val lay: FloatArray, val haze: FloatArray, val hz: Float, val cloud: FloatArray,
        val water: Float, val stars: Float, val aurora: Float, val ridges: Array<FloatArray>,
    )

    private fun rgb(vararg hex: Int) = FloatArray(hex.size * 3) { i -> ((hex[i / 3] shr (16 - i % 3 * 8)) and 255).toFloat() }

    fun range(seed: Int, height: Float, rough: Double, skip: Int = 0): Array<FloatArray> {
        val r = Rng(seed)
        repeat(skip) { r.next() }
        return Array(4) { i -> ridge(r, (200f - i * 38) * height, rough) }
    }

    private fun key(
        sky: FloatArray, lay: FloatArray, haze: FloatArray, hz: Float, cloud: FloatArray, seed: Int,
        water: Boolean = false, night: Boolean = false,
    ): Key {
        // The painting spends these random numbers on stars and aurora before it draws the ridges.
        val skip = if (night) 320 * 4 + 3 * 2 else 0
        return Key(sky, lay, haze, hz, cloud, if (water) 1f else 0f, if (night) 1f else 0f, if (night) 1f else 0f, range(seed, 1f, .52, skip))
    }

    val KEYS: Map<String, Key> by lazy {
        mapOf(
            "dusk" to key(
                rgb(0x1A1636, 0x6A3A6C, 0xF2A066), rgb(0x6B4470, 0x47305A, 0x2C1F40, 0x170F25),
                floatArrayOf(242f, 160f, 102f, .16f), HORIZON, floatArrayOf(112f, 84f, 108f), 7, water = true,
            ),
            "alpine" to key(
                rgb(0x2C5F9E, 0x86B5DE, 0xE3EEF5), rgb(0xA3BBD0, 0x6D8CA8, 0x46637B, 0x223646),
                floatArrayOf(225f, 238f, 246f, .22f), HORIZON, floatArrayOf(232f, 238f, 245f), 3,
            ),
            "night" to key(
                rgb(0x03060D, 0x0A1930, 0x14404F), rgb(0x123044, 0x0C2132, 0x071520, 0x030A10),
                floatArrayOf(60f, 180f, 160f, .07f), HORIZON, floatArrayOf(26f, 36f, 54f), 11, water = true, night = true,
            ),
            "amber" to key(
                rgb(0x16112B, 0x58385C, 0xE39457), rgb(0x5A3A55, 0x3C2742, 0x24182E, 0x120C19),
                floatArrayOf(227f, 148f, 87f, .14f), HORIZON, floatArrayOf(120f, 92f, 104f), 17, water = true,
            ),
        )
    }

    private fun smooth(v: Float): Float { val t = v.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    class Blend(val a: Key, val b: Key, val t: Float)

    /** Which two scenes the day is between right now, by today's real sunrise and sunset. */
    fun dayAt(m: Float, sun: Sun): Blend {
        val r = sun.rise.toFloat(); val s = sun.set.toFloat()
        val stops = listOf(
            0f to "night", r - 50 to "night", r to "amber", r + 110 to "alpine",
            s - 110 to "alpine", s to "dusk", s + 70 to "night", 1440f to "night",
        )
        for (i in 1 until stops.size) {
            if (m <= stops[i].first) {
                val (t0, a) = stops[i - 1]; val (t1, b) = stops[i]
                val span = t1 - t0
                return Blend(KEYS.getValue(a), KEYS.getValue(b), if (span > 0) smooth((m - t0) / span) else 1f)
            }
        }
        val n = KEYS.getValue("night")
        return Blend(n, n, 0f)
    }

    fun pack(m: Float, sun: Sun, v: FloatArray) {
        val d = dayAt(m, sun); val a = d.a; val b = d.b; val t = d.t
        for (i in 0 until 9) v[i] = lerp(a.sky[i], b.sky[i], t)
        for (i in 0 until 12) v[9 + i] = lerp(a.lay[i], b.lay[i], t)
        for (i in 0 until 4) v[21 + i] = lerp(a.haze[i], b.haze[i], t)
        for (i in 0 until 3) v[CLOUD + i] = lerp(a.cloud[i], b.cloud[i], t)
        v[HZ] = lerp(a.hz, b.hz, t); v[WATER] = lerp(a.water, b.water, t)
        v[STARS] = lerp(a.stars, b.stars, t); v[AURORA] = lerp(a.aurora, b.aurora, t); v[MOON] = v[STARS]
        // The sun takes today's real path: up on the left at sunrise, down on the right at sunset.
        val s = (m - sun.rise) / (sun.set - sun.rise)
        val alt = sin(PI * s.coerceIn(0f, 1f)).toFloat(); val low = 1 - alt
        val out = if (s < 0) -s else if (s > 1) s - 1 else 0f
        v[SUN_X] = lerp(.1f, .9f, s); v[SUN_Y] = .7f - alt * .54f + out * .6f; v[SUN_R] = lerp(24f, 30f, low)
        val core0 = floatArrayOf(255f, 251f, 232f); val core1 = floatArrayOf(255f, 227f, 184f)
        val glow0 = floatArrayOf(255f, 250f, 225f); val glow1 = floatArrayOf(255f, 190f, 130f)
        for (i in 0 until 3) { v[CORE + i] = lerp(core0[i], core1[i], low); v[GLOW + i] = lerp(glow0[i], glow1[i], low) }
        v[SUN_A] = smooth((s + .12f) / .12f) * smooth((1.12f - s) / .12f)
    }

    /**
     * Out among the stars it is always night: a deep, nearly black sky, a little lighter low down where
     * the tile's colour shows, the stars at full strength, and no sun, painted moon, water or aurora.
     */
    fun space(v: FloatArray) {
        val sky = rgb(0x03040B, 0x0A0C20, 0x22254A)
        for (i in 0 until 9) v[i] = sky[i]
        for (i in 0 until 4) v[21 + i] = 0f
        v[WATER] = 0f; v[STARS] = 1f; v[AURORA] = 0f; v[MOON] = 0f; v[SUN_A] = 0f
    }

    /** How bright each ridge gets in a vivid tile's colour, far to near. */
    private val LAND = floatArrayOf(.84f, .64f, .45f, .27f)

    /**
     * Colours the scene toward a tile's colour. The sky only leans that way and keeps its brightness,
     * so it stays dark behind the text. The land takes the colour fully, and bright enough to show at
     * night, when its own colours are nearly black. A greyer tile gets less of this, so Settings stays
     * quiet.
     */
    fun tint(v: FloatArray, c: FloatArray) {
        val hi = maxOf(c[0], c[1], c[2]).takeIf { it > 0 } ?: 1f
        val vivid = (hi - minOf(c[0], c[1], c[2])) / hi
        val hl = (.3f * c[0] + .59f * c[1] + .11f * c[2]).takeIf { it > 0 } ?: 1f
        fun wash(o: Int, amt: Float, least: Float = 0f) {
            val s = (.3f * v[o] + .59f * v[o + 1] + .11f * v[o + 2]) / hl
            for (i in 0 until 3) {
                val soft = min(255f, c[i] * s)
                v[o + i] = lerp(v[o + i], lerp(soft, max(soft, c[i] / hi * 255f * least), vivid), amt)
            }
        }
        for (o in 0 until 9 step 3) wash(o, .56f)
        for (i in 0 until 4) wash(9 + i * 3, lerp(.72f, .94f, vivid), LAND[i])
        // The haze is a pale veil over every ridge, so left alone it washes the colour back out.
        wash(21, .8f * vivid)
    }

    fun isNight(m: Float, sun: Sun) = dayAt(m, sun).let { it.a === it.b && it.a === KEYS["night"] }

    /** Open-Meteo's weather code as what the backdrop should show. */
    fun kindOf(code: Int): Int = when (code) {
        0, 1 -> CLEAR
        2, 3 -> CLOUDY
        45, 48 -> FOG
        in 71..77, 85, 86 -> SNOW
        in 51..67, in 80..82, in 95..99 -> RAIN
        else -> CLOUDY
    }

    /** How much each kind of weather greys the scene. */
    fun dimOf(kind: Int) = when (kind) { CLOUDY -> .16f; RAIN -> .26f; SNOW -> .1f; FOG -> .14f; else -> 0f }

    private class Shape(val seed: Int, val height: Float, val rough: Double)

    private val SHAPES = mapOf(
        "com.netflix.ninja" to Shape(101, 1.22f, .6),
        "com.apple.atve.amazon.appletv" to Shape(102, .62f, .36),
        "com.amazon.firebat" to Shape(103, 1.02f, .5),
        "org.xbmc.kodi" to Shape(104, .84f, .44),
        "com.hbo.hbonow" to Shape(105, 1.16f, .56),
        "com.hulu.plus" to Shape(106, .68f, .38),
        "com.amazon.firetv.youtube" to Shape(107, .96f, .52),
        "ps5" to Shape(109, 1.3f, .64),
        "settings" to Shape(110, .44f, .38),
        "all" to Shape(111, .5f, .42),
    )
    private val shapes = HashMap<String, Array<FloatArray>>()

    /** Each app's own land. Apps without one get a shape of their own from their name. */
    fun shape(id: String): Array<FloatArray> = shapes.getOrPut(id) {
        val s = SHAPES[id] ?: Shape(id.hashCode(), .7f + abs(id.hashCode() % 50) / 100f, .4 + abs(id.hashCode() % 20) / 100.0)
        range(s.seed, s.height, s.rough)
    }

    /** An app's seed, height and roughness, which every kind of place builds that app's own version from. */
    fun look(id: String): Triple<Int, Float, Double> {
        val s = SHAPES[id] ?: Shape(id.hashCode(), .7f + abs(id.hashCode() % 50) / 100f, .4 + abs(id.hashCode() % 20) / 100.0)
        return Triple(s.seed, s.height, s.rough)
    }

    /** Mountains that belong to no app, for the backdrop to drift through when left alone. */
    fun dream(n: Int): Array<FloatArray> = range(201 + n, .66f + (n * 37 % 60) / 100f, .38 + (n * 13 % 24) / 100.0)
}

/**
 * The scene to draw: today's light, one tile's land and colour, and, on the idle screen, mountains
 * slowly drifting from one range to the next.
 */
class Flow {
    val col = FloatArray(Sky.N)
    val ridge = Array(4) { FloatArray(POINTS) }

    var shape: Array<FloatArray>? = null
        private set
    var tint: FloatArray? = null
        private set
    /** The place under the sky when it isn't mountains, which the ridges above then only lend their shapes to. */
    var land: Places.Land? = null
        private set
    private var from: Array<FloatArray>? = null
    private var to: Array<FloatArray>? = null
    private var t = 0f
    private var next = 0
    val drifting get() = to != null

    fun target(shape: Array<FloatArray>?, tint: FloatArray?, land: Places.Land? = null) { this.shape = shape; this.tint = tint; this.land = land }

    /** Everything exactly as it should be at this minute. */
    /** How many of the city's windows are lit at this minute. */
    var lit = 0f
        private set

    fun snap(m: Float, sun: Sun) {
        colour(m, sun, tint)
        val d = Sky.dayAt(m, sun)
        val sh = shape
        for (i in 0 until 4) {
            val a = sh?.get(i) ?: d.a.ridges[i]; val b = sh?.get(i) ?: d.b.ridges[i]
            val k = if (sh != null) 0f else d.t
            for (j in 0 until POINTS) ridge[i][j] = a[j] + (b[j] - a[j]) * k
        }
    }

    /** The light of minute [m], washed with [with] in place of the tile's own colour, leaving the land's shape alone. */
    fun colour(m: Float, sun: Sun, with: FloatArray?) {
        lit = Places.lit(m, sun)
        Sky.pack(m, sun, col)
        if (land?.kind == Places.SPACE) Sky.space(col)
        with?.let { Sky.tint(col, it) }
    }

    /** Only mountains drift from one range to the next: a city or a coast reshaping itself would just look broken. */
    fun startDrift() {
        if (land != null) return
        from = Array(4) { ridge[it].copyOf() }; to = Sky.dream(next++); t = 0f
    }

    fun stopDrift() { from = null; to = null; t = 0f }

    /** Moves the land along, and rests a moment between ranges. */
    fun drift(dt: Float) {
        val a = from ?: return; val b = to ?: return
        t += dt / DRIFT_S
        val x = t.coerceIn(0f, 1f); val k = x * x * (3 - 2 * x)
        for (i in 0 until 4) for (j in 0 until POINTS) ridge[i][j] = a[i][j] + (b[i][j] - a[i][j]) * k
        if (t >= 1.35f) { from = b; to = Sky.dream(next++); t = 0f }
    }

    companion object {
        const val DRIFT_S = 8f
    }
}
