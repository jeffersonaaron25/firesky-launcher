package com.firesky.launcher

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The land under the sky. Mountains are FireSky's ridges as they always were; the others are built
 * the same way, as up to four layers of heights across the screen, far to near, so the shader draws
 * them all alike. Every app gets its own version of a place from its own seed, as it gets its own
 * mountains, and the sky above stays the real one.
 */
object Places {
    /** Heights per layer: one for every pixel of the 1280-wide backdrop, and the edge. */
    const val DETAIL = 1281

    const val MOUNTAINS = "mountains"
    const val WATER = "water"
    const val CITY = "city"
    const val PLAINS = "plains"
    const val ISLANDS = "islands"
    /** Each app has a place of its own. */
    const val SHUFFLE = "shuffle"
    /** One place for every app, a new one each morning. */
    const val DAILY = "daily"

    val KINDS = listOf(MOUNTAINS, WATER, CITY, PLAINS, ISLANDS)

    /**
     * One app's version of a place. Heights are in the backdrop's pixels above each layer's ground,
     * which sits [base] below the horizon (above it when negative).
     */
    class Land(
        val kind: String,
        val height: Array<FloatArray>,
        /** A building's own number in (0, 1], where windows go; 0 where there are none. */
        val seed: Array<FloatArray>,
        /** How high each building's walls go, below any spire or mast: where its windows stop. */
        val wall: Array<FloatArray>,
        val base: FloatArray,
        /** How far above its ground each layer's haze begins; 0 for none. */
        val haze: FloatArray,
        /** Windows on each layer's buildings: 0 none, 1 small and far, 2 large and near. */
        val windows: FloatArray,
        /** The waterline below the horizon, or null for dry land. */
        val water: Float? = null,
        /** Layers from this one on stand in front of the water. */
        val front: Int = 4,
    ) {
        /** How far above the mountains' horizon this place's own is: its farthest ground, where the sun and stars set. */
        val horizon: Float get() = minOf(base.min(), water ?: 0f)
    }

    /** Which place [id] shows: the one chosen, its own when shuffled by app, or today's. */
    fun kind(choice: String, id: String?, day: Long): String = when (choice) {
        in KINDS -> choice
        SHUFFLE -> KINDS[(Rng((id ?: "all").hashCode() xor 0x5EED).next() * KINDS.size).toInt().coerceIn(0, KINDS.size - 1)]
        DAILY -> KINDS[Math.floorMod(day, KINDS.size.toLong()).toInt()]
        else -> MOUNTAINS
    }

    /** Which day it is for the daily place, which changes at sunrise rather than at midnight. */
    fun day(epochMinutesLocal: Long, sun: Sun): Long = Math.floorDiv(epochMinutesLocal - sun.rise, 1440L)

    /** The place [id] shows right now, by the setting: null for mountains. */
    fun current(id: String?, sun: Sun, now: Long = System.currentTimeMillis()): Land? {
        val local = (now + java.util.TimeZone.getDefault().getOffset(now)) / 60_000
        return land(kind(Prefs.chosen(Prefs.PLACE), id, day(local, sun)), id)
    }

    private val made = HashMap<String, Land?>()

    /** [id]'s version of a place; null for mountains, which the shader draws as it always has. */
    fun land(kind: String, id: String?): Land? = synchronized(made) {
        if (kind == MOUNTAINS) return null
        val key = "$kind/${id ?: "all"}"
        made.getOrPut(key) {
            val (seed, h, rough) = Sky.look(id ?: "all")
            when (kind) {
                WATER -> water(seed, h, rough)
                CITY -> city(seed, h)
                PLAINS -> plains(seed, h, rough)
                ISLANDS -> islands(seed, h, rough)
                else -> null
            }
        }
    }

    /**
     * How many windows are lit: none by day, more and more from just before sunset, most an hour
     * after, then about half of those once it's late, until morning.
     */
    fun lit(m: Float, sun: Sun): Float {
        val r = sun.rise.toFloat(); val s = sun.set.toFloat()
        val evening = .58f
        fun late(minute: Float) = evening * (1f - .5f * smooth((minute - 22 * 60f) / 180f))
        return when {
            m >= s + 50 -> late(m)
            m > s - 40 -> evening * smooth((m - (s - 40)) / 90f)
            m >= r + 30 -> 0f
            m > r - 20 -> late(m + 1440) * (1f - smooth((m - (r - 20)) / 50f))
            else -> late(m + 1440)
        }
    }

    /* ---------- the places ---------- */

    private fun empty() = Array(4) { FloatArray(DETAIL) }

    /** A FireSky ridge, drawn out to a height for every pixel. */
    private fun fine(r: FloatArray): FloatArray = FloatArray(DETAIL) { j ->
        val x = j / (DETAIL - 1f) * (POINTS - 1); val i = min(floor(x).toInt(), POINTS - 2); val t = x - i
        r[i] + (r[i + 1] - r[i]) * t
    }

    private fun smooth(v: Float): Float { val t = v.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** How far above the horizon open water starts: high enough that it shows above the row of cards. */
    const val WATERLINE = 190f

    /** How tall the city can be on the left, under the app's name and the line below it. */
    const val LEFT_CITY = 95f

    /** Off screen and never drawn. */
    private const val UNUSED = 900f

    /** A lake with hills along the far shore, or a coast where one side opens to the sea; sometimes rocks in front. */
    private fun water(seed: Int, h: Float, rough: Double): Land {
        val r = Rng(seed * 7 + 3)
        val open = r.next() < .5; val side = r.next() < .5; val rocks = r.next() < .5
        val sr = Rng(seed + 40)
        val far = fine(ridge(sr, 92f * (.55f + h * .5f), rough)); val near = fine(ridge(sr, 52f * (.55f + h * .5f), rough))
        val height = empty()
        for (j in 0 until DETAIL) {
            val u = j / (DETAIL - 1f)
            val m = if (open) ((if (side) u else 1 - u) - .4f).div(.38f).coerceIn(0f, 1f).pow(1.4f) else .5f + .5f * sin(PI * (u * .92 + .04)).toFloat()
            height[0][j] = far[j] * m; height[1][j] = near[j] * m
        }
        val base = floatArrayOf(-WATERLINE - 10f, -WATERLINE, UNUSED, UNUSED)
        if (rocks) {
            // Rocks along the near shore, on the side the shore is open to.
            val stones = fine(ridge(Rng(seed + 90), 46f, .62))
            val from = if (side) .6f else 0f
            for (j in 0 until DETAIL) {
                val u = (j / (DETAIL - 1f) - from) / .4f
                if (u in 0f..1f) height[3][j] = 26f + stones[(u * (DETAIL - 1)).toInt()] * sin(PI * u).toFloat()
            }
            base[3] = 150f
        }
        return Land(WATER, height, empty(), empty(), base, floatArrayOf(height[0].max() + 10f, 0f, 0f, 0f), FloatArray(4), -WATERLINE, if (rocks) 3 else 4)
    }

    /** Far blocks and nearer buildings, kept lower on the left where the text sits, towers rising to the right; sometimes a river in front. */
    private fun city(seed: Int, h: Float): Land {
        val r = Rng(seed * 13 + 5)
        val river = r.next() < .5
        val height = empty(); val seeds = empty(); val wall = empty()
        fun building(layer: Int, x0: Float, w: Float, tall: Float) {
            val id = .05f + r.next().toFloat() * .95f
            val a = max(0, x0.toInt()); val b = min(DETAIL - 1, (x0 + w).toInt())
            for (j in a..b) { height[layer][j] = tall; seeds[layer][j] = id; wall[layer][j] = tall }
        }
        var x = -10f
        while (x < DETAIL) {
            val w = 18f + r.next().toFloat() * 44f
            building(1, x, w, 52f + r.next().toFloat() * 110f * (.6f + h * .5f))
            x += w + if (r.next() < .2) r.next().toFloat() * 14f else 0f
        }
        // A faint skyline further off still, for depth: too far for windows.
        val dr = Rng(seed * 29 + 1)
        x = -6f
        while (x < DETAIL) {
            val w = 12f + dr.next().toFloat() * 30f
            // Lower under the text on the left.
            val tall = (70f + dr.next().toFloat() * 120f * (.6f + h * .5f)) * (if (x < 560f) .55f else 1f)
            for (j in max(0, x.toInt())..min(DETAIL - 1, (x + w).toInt())) height[0][j] = tall
            x += w + if (dr.next() < .3) dr.next().toFloat() * 20f else 0f
        }
        x = -20f
        while (x < DETAIL) {
            val w = 28f + r.next().toFloat() * 62f
            val right = x / 1280f
            var tall = 50f + r.next().toFloat() * 110f
            if (r.next() < .14 + right * .3) tall += 80f + r.next().toFloat() * 170f * h
            tall = min(tall, LEFT_CITY + (330f - LEFT_CITY) * ((right - .35f) / .3f).coerceIn(0f, 1f))
            building(2, x, w, tall)
            val kind = r.next()
            val a = x.toInt(); val mid = (x + w / 2).toInt()
            fun raise(j: Int, to: Float) { if (j in 0 until DETAIL) height[2][j] = max(height[2][j], to) }
            when {
                // A narrower storey on top.
                kind < .2 -> for (j in (x + w * .2f).toInt()..(x + w * .8f).toInt()) raise(j, tall * 1.12f)
                // A spire with a mast, and below a mast alone: only right of the text.
                kind < .32 && right >= .35f -> {
                    for (j in (x + w * .15f).toInt()..(x + w * .85f).toInt()) raise(j, tall + w * .9f * (1f - kotlin.math.abs(j - (x + w / 2)) / (w * .35f)).coerceAtLeast(0f))
                    for (j in mid - 1..mid) raise(j, tall + w * .9f + 26f)
                }
                kind < .42 && right >= .35f -> for (j in a + (w * .45f).toInt()..a + (w * .45f).toInt() + 1) raise(j, tall + 30f)
            }
            x += w + if (r.next() < .25) 4f + r.next().toFloat() * 18f else 0f
        }
        val base = floatArrayOf(-94f, -82f, -70f, UNUSED)
        val haze = floatArrayOf(height[0].max() + 20f, height[1].max() + 60f, 0f, 0f)
        return Land(CITY, height, seeds, wall, base, haze, floatArrayOf(0f, 1f, 2f, 0f), if (river) -70f else null)
    }

    /** Low rolling land under a big sky, with trees along the middle rise. */
    private fun plains(seed: Int, h: Float, rough: Double): Land {
        val r = Rng(seed * 17 + 9)
        val rr = Rng(seed + 70)
        val amps = floatArrayOf(80f, 60f, 42f).map { it * (.6f + h * .4f) }
        val rises = amps.map { fine(ridge(rr, it, min(rough, .45))) }
        val height = empty()
        for (k in 0 until 3) rises[k].copyInto(height[k + 1])
        // Trees: round ones and a few tall poplars, as bumps on the middle rise.
        val n = 6 + (r.next() * 8).toInt()
        repeat(n) {
            val cx = r.next().toFloat() * 1280f; val s = 9f + r.next().toFloat() * 11f; val poplar = r.next() < .3
            val half = if (poplar) s * .45f else s
            val top = if (poplar) s * 4f else s * 2.4f
            for (j in (cx - half).toInt()..(cx + half).toInt()) {
                if (j !in 0 until DETAIL) continue
                val d = (j - cx) / half
                val crown = top - (if (poplar) s * 2f else s) * (1f - sqrt(max(0f, 1f - d * d)))
                height[2][j] = max(height[2][j], rises[1][j] + crown)
            }
        }
        // High enough that the hills and trees show above the row of cards, low enough to leave a big sky.
        val base = floatArrayOf(UNUSED, -128f, -98f, -68f)
        return Land(PLAINS, height, empty(), empty(), base, floatArrayOf(0f, amps[0], amps[1], 0f), FloatArray(4))
    }

    /** One to three islands on an open sea, the nearest the biggest, all mirrored in the water. */
    private fun islands(seed: Int, h: Float, rough: Double): Land {
        val r = Rng(seed * 19 + 2)
        val n = 1 + (r.next() * 3).toInt()
        val height = empty()
        for (k in 0 until n) {
            val cx = (.3f + r.next().toFloat() * .65f) * 1280f
            val far = k < n - 1 || r.next() < .3
            val w = (140f + r.next().toFloat() * 300f) * (if (far) .7f else 1f)
            val tall = (30f + r.next().toFloat() * 85f) * (.6f + h * .4f) * (if (far) .65f else 1f)
            val shape = fine(ridge(Rng(seed + cx.toInt()), tall, rough))
            val layer = if (far) 1 else 2
            for (j in (cx - w / 2).toInt()..(cx + w / 2).toInt()) {
                if (j !in 0 until DETAIL) continue
                // Rounding can put the first and last columns a hair outside the island, where the power would be NaN.
                val u = ((j - (cx - w / 2)) / w).coerceIn(0f, 1f)
                height[layer][j] = max(height[layer][j], shape[(u * (DETAIL - 1)).toInt()] * max(0f, sin(PI * u).toFloat()).pow(.7f))
            }
        }
        val base = floatArrayOf(UNUSED, -WATERLINE, -WATERLINE, UNUSED)
        return Land(ISLANDS, height, empty(), empty(), base, floatArrayOf(0f, height[1].max() + 20f, 0f, 0f), FloatArray(4), -WATERLINE)
    }
}
