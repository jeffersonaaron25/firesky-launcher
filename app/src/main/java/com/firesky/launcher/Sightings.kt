package com.firesky.launcher

import java.util.Calendar
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun smooth(v: Float): Float { val t = v.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

/** How the wind's real speed sets the clouds and the rain: 1 for a light breeze, as they always moved. */
object Wind {
    fun factor(kmh: Float) = (.45f + kmh / 18f).coerceIn(.45f, 3f)

    /** How far rain leans from straight down, the way the clouds drift. */
    fun slant(direction: Float, factor: Float) = (if (direction < 0) -1f else 1f) * (.06f + .15f * factor).coerceAtMost(.5f)
}

/** A hot-air balloon crossing the idle screen on some calm mornings. */
object Balloon {
    const val CHANCE = .35
    /** Seconds to cross the screen, and how often another one comes while the idle screen stays up. */
    const val CROSS_S = 240f
    const val EVERY_S = 720f
    const val RADIUS = 13f

    @Volatile var test = false

    /** Whether one might go up now: about one morning in three, early on, in calm weather that isn't wet. */
    fun morning(day: Calendar, m: Float, sun: Sun, kind: Int, cloud: Int, windKmh: Float): Boolean {
        if (test) return true
        if (m < sun.rise - 10 || m > sun.rise + 180) return false
        if (kind != Sky.CLEAR && kind != Sky.CLOUDY) return false
        if (cloud > 75 || windKmh > 18f) return false
        return Rng(day.get(Calendar.YEAR) * 400 + day.get(Calendar.DAY_OF_YEAR)).next() < CHANCE
    }

    /**
     * Where it is [t] seconds into the idle screen, as x, y, radius and how much it shows, into [out].
     * It drifts the way the clouds do ([direction] 1 is leftwards), slowly rising, with a gentle bob.
     */
    fun at(t: Float, direction: Float, out: FloatArray) {
        val k = (t % EVERY_S) / CROSS_S
        if (k >= 1f) { out[3] = 0f; return }
        out[0] = if (direction >= 0) 1330f - 1380f * k else -50f + 1380f * k
        out[1] = 300f - 120f * k + 5f * sin(t * .45f)
        out[2] = RADIUS
        out[3] = smooth(k / .06f) * smooth((1 - k) / .06f)
    }
}

/**
 * Fireworks over the valley around midnight on New Year's Eve. They're drawn over the finished
 * picture each frame, which costs next to nothing, rather than with the sky behind the hills, which
 * meant drawing the whole landscape again for every move of a spark.
 */
object Fireworks {
    @Volatile var test = false

    /** The most points one frame of them can hand over: a head and a short tail for each spark. */
    const val MAX = 900

    /** How much the backdrop darkens a point to keep the words on it readable, as its own shader does. */
    fun shade(x: Float, y: Float): Float {
        val d = kotlin.math.hypot(x - 640f, y - 360f)
        return (1 - .45f * ((d - 252f) / 432f).coerceIn(0f, 1f)) *
            (1 - .78f * ((y / 720f - .48f) / .52f).coerceIn(0f, 1f)) *
            (1 - .5f * (1 - x / 1280f / .6f).coerceIn(0f, 1f))
    }

    private class Spark(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val r: Float, val g: Float, val b: Float, val life: Float,
        /** A rocket still climbing; it bursts at [burst] height. */
        val rocket: Boolean = false, val burst: Float = 0f, var age: Float = 0f,
    )

    private val sparks = ArrayList<Spark>()
    private val rnd = Random()
    private var wait = 0f

    private val COLOURS = listOf(
        floatArrayOf(1f, .8f, .45f), floatArrayOf(1f, .38f, .32f), floatArrayOf(.5f, 1f, .6f),
        floatArrayOf(.55f, .7f, 1f), floatArrayOf(.88f, .55f, 1f), floatArrayOf(1f, 1f, 1f),
    )

    /** From two minutes to midnight on the 31st until twenty past on the 1st, local time. */
    fun on(c: Calendar): Boolean {
        if (test) return true
        val m = c.get(Calendar.MONTH); val d = c.get(Calendar.DAY_OF_MONTH); val h = c.get(Calendar.HOUR_OF_DAY); val min = c.get(Calendar.MINUTE)
        return (m == Calendar.DECEMBER && d == 31 && h == 23 && min >= 58) || (m == Calendar.JANUARY && d == 1 && h == 0 && min < 20)
    }

    /** Something is in the sky, so the picture has to keep moving. */
    val busy get() = sparks.isNotEmpty()

    /**
     * Moves the show on by [dt] seconds, launching more while [now], and hands every spark to [dot]
     * as x, y, size, red, green, blue and brightness. Only the drawing thread calls this.
     */
    fun step(dt: Float, now: Boolean, dot: (Float, Float, Float, Float, Float, Float, Float) -> Unit) {
        if (now && dt > 0f) {
            wait -= dt
            if (wait <= 0f) {
                val c = COLOURS[rnd.nextInt(COLOURS.size)]
                sparks += Spark((180 + rnd.nextFloat() * 920), 500f, (rnd.nextFloat() - .5f) * 30f, -(220f + rnd.nextFloat() * 50f),
                    c[0], c[1], c[2], 3f, rocket = true, burst = 120f + rnd.nextFloat() * 170f)
                wait = .7f + rnd.nextFloat() * 2.2f
            }
        }
        val born = ArrayList<Spark>()
        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.age += dt
            s.x += s.vx * dt; s.y += s.vy * dt
            if (s.rocket) {
                s.vy += 40f * dt
                if (s.y <= s.burst || s.vy >= 0f) {
                    val n = 48 + rnd.nextInt(20)
                    val speed = 100f + rnd.nextFloat() * 50f
                    for (i in 0 until n) {
                        val a = i * 2 * PI.toFloat() / n + rnd.nextFloat() * .1f
                        val v = speed * (.75f + rnd.nextFloat() * .35f)
                        born += Spark(s.x, s.y, cos(a) * v, sin(a) * v, s.r, s.g, s.b, 2.2f + rnd.nextFloat() * .9f)
                    }
                    it.remove(); continue
                }
                // Rising out of the dark of the valley.
                val rise = (s.age / .5f).coerceIn(0f, 1f)
                dot(s.x, s.y, 9f, 1f, .9f, .75f, rise)
                dot(s.x - s.vx * .03f, s.y - s.vy * .03f, 7f, 1f, .8f, .6f, .5f * rise)
                continue
            }
            // Slowed by the air, pulled down, fading out with a sparkle at the end.
            s.vx *= 1 - .7f * dt; s.vy = s.vy * (1 - .7f * dt) + 30f * dt
            val k = s.age / s.life
            if (k >= 1f) { it.remove(); continue }
            val fade = (1 - k) * (1 - k)
            val twinkle = if (k > .6f && rnd.nextFloat() < .3f) .3f else 1f
            // A bright head with a short tail behind it, so each spark streaks rather than dots.
            dot(s.x, s.y, 11f, s.r, s.g, s.b, fade * twinkle)
            dot(s.x - s.vx * .05f, s.y - s.vy * .05f, 8f, s.r, s.g, s.b, fade * .55f)
            dot(s.x - s.vx * .1f, s.y - s.vy * .1f, 6f, s.r, s.g, s.b, fade * .25f)
        }
        sparks += born
        if (!now && sparks.isEmpty()) wait = 0f
    }
}
