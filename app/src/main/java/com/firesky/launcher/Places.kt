package com.firesky.launcher

import kotlin.math.PI
import kotlin.math.abs
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
    const val DESERT = "desert"
    const val FOREST = "forest"
    /** No land at all: the real sky all round, as if seen from out among the stars. */
    const val SPACE = "space"
    /** Each app has a place of its own, a new one every morning. */
    const val SHUFFLE = "shuffle"
    /** One place for every app, a new one each morning. */
    const val DAILY = "daily"

    /** The landscapes, which shuffling chooses among. Space isn't one: among them it looked out of place. */
    val KINDS = listOf(MOUNTAINS, WATER, CITY, PLAINS, ISLANDS, DESERT, FOREST)

    /** Every place that can be chosen in the settings: the landscapes, and space on its own. */
    val CHOICES = KINDS + SPACE

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
        /** Which way the window faces, in degrees from north. Only space looks anywhere but south. */
        val facing: Double = 180.0,
    ) {
        /** Where the sun and stars set: the same for every place. */
        val horizon: Float get() = HORIZON
    }

    /** Which place [id] shows: the one chosen, its own when shuffled by app, or today's. */
    fun kind(choice: String, id: String?, day: Long): String = when (choice) {
        in CHOICES -> choice
        SHUFFLE -> {
            // Each app starts from a place of its own and steps through all of them, a step a day of its
            // own size: never the same place two days running, and every place within a week and a day.
            // The steps are 1, 3 or 5, none of which divides seven, so every place comes round.
            val key = (id ?: "all").hashCode()
            val start = (Rng(key xor 0x5EED).next() * KINDS.size).toInt().coerceIn(0, KINDS.size - 1)
            val step = 1 + 2 * (Rng(key xor 0x1A4D).next() * (KINDS.size / 2)).toInt().coerceIn(0, KINDS.size / 2 - 1)
            KINDS[Math.floorMod(start + day * step, KINDS.size.toLong()).toInt()]
        }
        DAILY -> KINDS[Math.floorMod(day, KINDS.size.toLong()).toInt()]
        else -> MOUNTAINS
    }

    /**
     * Where a title's story is set, from its TMDB keywords, so its page can stand it in that place;
     * null when they don't say. When several places match, the one most named wins, the rarer
     * places counting for more: The Revenant's forest beats its mountains.
     */
    fun story(keywords: List<String>): String? {
        val score = HashMap<String, Float>()
        for (k in keywords) {
            val w = k.lowercase(java.util.Locale.ROOT).trim()
            val words = w.split(' ', ',', '-').filter { it.isNotEmpty() }
            for ((kind, names) in STORY) {
                val hit = w in names || when (kind) {
                    CITY -> "city" in words
                    WATER -> "ocean" in words || "sea" in words
                    // An island of any kind, but not a place merely named one, like Long Island.
                    ISLANDS -> w.endsWith(" island")
                    else -> false
                }
                if (hit) score[kind] = (score[kind] ?: 0f) + STORY_WEIGHT.getValue(kind)
            }
        }
        return score.maxByOrNull { it.value }?.key
    }

    private val STORY = mapOf(
        ISLANDS to setOf("island", "deserted island", "tropical island", "castaway", "stranded on an island", "island life"),
        DESERT to setOf("desert", "sand dune", "sandstorm", "sahara", "oasis", "desert planet", "camel", "bedouin"),
        FOREST to setOf("forest", "woods", "jungle", "wilderness", "rainforest", "cabin in the woods", "camping", "lumberjack",
            "enchanted forest", "redwood", "woodland", "forest fire", "logging"),
        WATER to setOf("ocean", "sea", "lake", "boat", "sailing", "ship", "sailboat", "submarine", "underwater", "fishing", "lighthouse",
            "beach", "coast", "surfing", "river", "pirate", "lifeboat", "shark", "shipwreck", "cruise ship", "cargo ship", "fisherman", "fishing boat"),
        CITY to setOf("manhattan, new york city", "los angeles, california", "london, england", "paris, france", "tokyo, japan",
            "chicago, illinois", "metropolis", "skyscraper", "urban setting", "downtown", "hong kong", "las vegas", "san francisco, california",
            "brooklyn, new york city", "gotham city", "urban life"),
        PLAINS to setOf("farm", "farmer", "ranch", "rancher", "prairie", "countryside", "rural area", "rural setting", "midwest",
            "great plains", "cornfield", "cowboy", "nomad", "road trip", "wheat field", "small farm", "farm life"),
        MOUNTAINS to setOf("mountain", "mountains", "mountain climbing", "mountaineering", "himalayas", "alps", "skiing", "avalanche",
            "rock climbing", "glacier", "mount everest", "climbing", "mountain village"),
    )

    private val STORY_WEIGHT = mapOf(ISLANDS to 1.6f, DESERT to 1.6f, FOREST to 1.4f, WATER to 1.2f, CITY to 1.1f, PLAINS to 1f, MOUNTAINS to .9f)

    /** Which day it is for the daily place, which changes at sunrise rather than at midnight. */
    fun day(epochMinutesLocal: Long, sun: Sun): Long = Math.floorDiv(epochMinutesLocal - sun.rise, 1440L)

    /** The place [id] shows right now, by the setting: null for mountains. */
    fun current(id: String?, sun: Sun, now: Long = System.currentTimeMillis()): Land? {
        val local = (now + java.util.TimeZone.getDefault().getOffset(now)) / 60_000
        return land(kind(test ?: Prefs.chosen(Prefs.PLACE), id, day(local, sun)), id)
    }

    /** For pictures and trying things out over adb: this place for every app instead of the setting, kept nowhere. */
    @Volatile var test: String? = null

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
                DESERT -> desert(seed, h)
                FOREST -> forest(seed, h)
                SPACE -> space(seed)
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

    /**
     * The sky's horizon, in pixels above the mountains' feet: the same for every place, so the real
     * sun, moon and stars stand in the same place whichever app is selected. Open water meets the sky
     * there, and every other place's farthest ground lies on it; mountains rise in front of it.
     */
    const val HORIZON = -WATERLINE

    /** How tall the city can be on the left, under the app's name and the line below it. */
    const val LEFT_CITY = 55f

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

    /** Far blocks and nearer buildings, kept lower on the left where the text sits, towers rising to the right, a river in front. */
    private fun city(seed: Int, h: Float): Land {
        val r = Rng(seed * 13 + 5)
        // Every city stands on its river: on the shared horizon, a city without one sat on a slab of bare ground.
        r.next()
        val height = empty(); val seeds = empty(); val wall = empty()
        fun building(layer: Int, x0: Float, w: Float, tall: Float) {
            val id = .05f + r.next().toFloat() * .95f
            val a = max(0, x0.toInt()); val b = min(DETAIL - 1, (x0 + w).toInt())
            for (j in a..b) { height[layer][j] = tall; seeds[layer][j] = id; wall[layer][j] = tall }
        }
        var x = -10f
        while (x < DETAIL) {
            val w = 18f + r.next().toFloat() * 44f
            building(1, x, w, 34f + r.next().toFloat() * 72f * (.6f + h * .5f))
            x += w + if (r.next() < .2) r.next().toFloat() * 14f else 0f
        }
        // A faint skyline further off still, for depth: too far for windows.
        val dr = Rng(seed * 29 + 1)
        x = -6f
        while (x < DETAIL) {
            val w = 12f + dr.next().toFloat() * 30f
            // Lower under the text on the left.
            val tall = (44f + dr.next().toFloat() * 76f * (.6f + h * .5f)) * (if (x < 560f) .55f else 1f)
            for (j in max(0, x.toInt())..min(DETAIL - 1, (x + w).toInt())) height[0][j] = tall
            x += w + if (dr.next() < .3) dr.next().toFloat() * 20f else 0f
        }
        x = -20f
        while (x < DETAIL) {
            val w = 28f + r.next().toFloat() * 62f
            val right = x / 1280f
            // No taller than the mountains stand, so the city sits with the other places under the same sky.
            var tall = 32f + r.next().toFloat() * 66f
            if (r.next() < .14 + right * .3) tall += 40f + r.next().toFloat() * 70f * h
            tall = min(tall, LEFT_CITY + (150f - LEFT_CITY) * ((right - .35f) / .3f).coerceIn(0f, 1f))
            building(2, x, w, tall)
            val kind = r.next()
            val a = x.toInt(); val mid = (x + w / 2).toInt()
            fun raise(j: Int, to: Float) { if (j in 0 until DETAIL) height[2][j] = max(height[2][j], to) }
            when {
                // A narrower storey on top.
                kind < .2 -> for (j in (x + w * .2f).toInt()..(x + w * .8f).toInt()) raise(j, tall * 1.12f)
                // A spire with a mast, and below a mast alone: only right of the text.
                kind < .32 && right >= .35f -> {
                    for (j in (x + w * .15f).toInt()..(x + w * .85f).toInt()) raise(j, tall + w * .5f * (1f - kotlin.math.abs(j - (x + w / 2)) / (w * .35f)).coerceAtLeast(0f))
                    for (j in mid - 1..mid) raise(j, tall + w * .5f + 18f)
                }
                kind < .42 && right >= .35f -> for (j in a + (w * .45f).toInt()..a + (w * .45f).toInt() + 1) raise(j, tall + 20f)
            }
            x += w + if (r.next() < .25) 4f + r.next().toFloat() * 18f else 0f
        }
        val base = floatArrayOf(HORIZON, HORIZON + 14f, HORIZON + 30f, UNUSED)
        val haze = floatArrayOf(height[0].max() + 20f, height[1].max() + 60f, 0f, 0f)
        // A river runs right along the foot of the nearest buildings, with no strip of ground between.
        return Land(CITY, height, seeds, wall, base, haze, floatArrayOf(0f, 1f, 2f, 0f), base[2])
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
        val base = floatArrayOf(UNUSED, HORIZON, HORIZON + 30f, HORIZON + 62f)
        // A lake at the foot of the nearest rise, so the land below isn't one flat field.
        return Land(PLAINS, height, empty(), empty(), base, floatArrayOf(0f, amps[0], amps[1], 0f), FloatArray(4), base[3])
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

    /**
     * A sea of sand: a low far rank of dunes, then three more, nearer and larger. Each dune rises in a
     * long slope from the side the wind comes from to a sharp crest, and falls in a steep face. [Land.seed]
     * holds which way the sand faces, from 0 (towards the left) to 1 (towards the right), smoothly, so the
     * shader can light one side of each dune and shade the other.
     */
    private fun desert(seed: Int, h: Float): Land {
        val r = Rng(seed * 23 + 4)
        val height = empty(); val face = Array(4) { FloatArray(DETAIL) { .5f } }
        fun dune(p: Double): Float {
            val c = .74; val q = ((p % 1) + 1) % 1
            return if (q < c) (.5 - .5 * kotlin.math.cos(PI * q / c)).toFloat() else (.5 + .5 * kotlin.math.cos(PI * (q - c) / (1 - c))).toFloat()
        }
        val grow = .7f + h * .3f
        val amps = floatArrayOf(24f, 44f, 66f, 92f); val waves = floatArrayOf(300f, 460f, 620f, 780f)
        for (layer in 0 until 4) {
            val ph = r.next(); val ph2 = r.next()
            val wob = fine(ridge(Rng(seed + 60 + layer), 1f, .5))
            for (j in 0 until DETAIL) height[layer][j] = amps[layer] * grow * (.72f * dune(j / waves[layer].toDouble() + ph + .12 * wob[j]) + .28f * dune(j / (waves[layer] * .57) + ph2))
            val raw = FloatArray(DETAIL) { j -> (.5f - (height[layer][min(j + 2, DETAIL - 1)] - height[layer][max(j - 2, 0)]) / 4f * 2.2f).coerceIn(0f, 1f) }
            // Softened over a few pixels, so the light turns across the crest rather than switching.
            for (j in 0 until DETAIL) { var t = 0f; for (d in -8..8) t += raw[(j + d).coerceIn(0, DETAIL - 1)]; face[layer][j] = t / 17f }
        }
        val base = floatArrayOf(HORIZON, HORIZON + 20f, HORIZON + 48f, HORIZON + 86f)
        val haze = floatArrayOf(height[0].max() + 12f, height[1].max() + 14f, height[2].max() + 16f, 0f)
        return Land(DESERT, height, face, empty(), base, haze, FloatArray(4))
    }

    /** Ranks of conifers on rolling ground, small and close-set far off, tall and sparse near. */
    private fun forest(seed: Int, h: Float): Land {
        val r = Rng(seed * 31 + 6)
        val height = empty()
        // Ground, tree spacing and tree height for each rank, far to near.
        val ranks = arrayOf(floatArrayOf(10f, 13f, 30f), floatArrayOf(14f, 19f, 46f), floatArrayOf(18f, 28f, 70f), floatArrayOf(20f, 46f, 130f))
        val grow = .7f + h * .3f
        for (i in 0 until 4) {
            val (roll, gap, tall) = ranks[i].let { Triple(it[0], it[1], it[2] * grow) }
            val ground = fine(ridge(Rng(seed + 80 + i), roll, .45))
            for (j in 0 until DETAIL) height[i][j] = ground[j]
            var x = -gap
            while (x < DETAIL + gap) {
                val th = tall * (.6f + .8f * r.next().toFloat())
                val half = th * (.34f + .1f * r.next().toFloat()) / 2
                // Three tiers, each narrower than the one below, as a spruce grows.
                val tiers = arrayOf(floatArrayOf(0f, .42f, 1f, .4f), floatArrayOf(.42f, .74f, .68f, .2f), floatArrayOf(.74f, 1f, .4f, 0f))
                for (j in (x - half).toInt()..(x + half).toInt()) {
                    if (j !in 0 until DETAIL) continue
                    val d = kotlin.math.abs(j - x) / half
                    var top = 0f
                    for (t in tiers) {
                        val (hb, ht, wb, wt) = t.let { arrayOf(it[0], it[1], it[2], it[3]) }
                        if (d > wb) continue
                        top = max(top, th * (if (d <= wt) ht else hb + (ht - hb) * (wb - d) / (wb - wt)))
                    }
                    if (top > 0f) height[i][j] = max(height[i][j], ground[j] + top)
                }
                x += gap * (.7f + .6f * r.next().toFloat())
            }
        }
        // As high as the plains' rises, so the tops of the trees show above the row of cards.
        val base = floatArrayOf(HORIZON, HORIZON + 22f, HORIZON + 52f, HORIZON + 94f)
        val haze = floatArrayOf(height[0].max() + 8f, height[1].max() + 12f, height[2].max() + 20f, 0f)
        // A lake at the foot of the nearest trees, which stand mirrored in it.
        return Land(FOREST, height, empty(), empty(), base, haze, FloatArray(4), base[3])
    }

    /**
     * No land: every layer sits off screen, and the sky and the Milky Way take the app's colour. Each app looks out in its own direction, so they show
     * different stars: one of the eight points of the compass, south the likeliest.
     */
    private fun space(seed: Int): Land {
        val facings = doubleArrayOf(180.0, 180.0, 135.0, 225.0, 90.0, 270.0, 45.0, 315.0, 0.0)
        val facing = facings[(Rng(seed * 37 + 8).next() * facings.size).toInt().coerceIn(0, facings.size - 1)]
        return Land(SPACE, empty(), empty(), empty(), FloatArray(4) { UNUSED }, FloatArray(4), FloatArray(4), facing = facing)
    }
}
