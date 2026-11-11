package com.firesky.launcher

import kotlin.math.exp

/**
 * Lightning for the idle screen during a thunderstorm. A strike is two to four flickers inside
 * half a second, as the real thing is, then quiet for a while. Some strikes are only a glow in the
 * cloud; about half show the bolt itself.
 */
class Storm(seed: Int = 23) {
    private val r = Rng(seed)
    private var wait = FIRST_S
    private var age = -1f
    private var at = FloatArray(0)
    private var strength = FloatArray(0)

    /** Where across the screen it struck, in the backdrop's 1280 units, and what shapes its kinks. */
    var x = 640f
        private set
    var shape = 0f
        private set
    /** Whether this strike shows a bolt as well as lighting the cloud. */
    var bolt = false
        private set
    val striking get() = age >= 0f

    private fun strike() {
        val n = 2 + (r.next() * 3).toInt()
        at = FloatArray(n); strength = FloatArray(n)
        var t = 0f
        for (i in 0 until n) {
            at[i] = t; strength[i] = (.45 + r.next() * .55).toFloat()
            t += (.06 + r.next() * .16).toFloat()
        }
        // The first or second flicker is the bright one.
        strength[(r.next() * 2).toInt().coerceAtMost(n - 1)] = 1f
        x = (160 + r.next() * 960).toFloat()
        shape = r.next().toFloat()
        bolt = r.next() < .5
        age = 0f
    }

    /** Moves on by [dt] seconds. Returns how bright the lightning is now, 0 to 1. */
    fun step(dt: Float): Float {
        if (age < 0f) {
            wait -= dt
            if (wait > 0f) return 0f
            strike()
        } else age += dt
        var f = 0f
        // The brightest it was since the last step, so a flicker that began between two frames isn't caught late and dim.
        for (i in at.indices) if (age >= at[i]) f = maxOf(f, strength[i] * exp(-(maxOf(at[i], age - dt) - at[i]) / FADE_S))
        if (age > at.last() + 6 * FADE_S) { age = -1f; wait = (GAP_S + r.next() * SPREAD_S).toFloat(); return 0f }
        return f
    }

    companion object {
        /** Each flicker dies away over about this long. */
        const val FADE_S = .09f
        const val FIRST_S = 2.5f
        /** Between strikes: at least this long, and up to [SPREAD_S] more. */
        const val GAP_S = 3.5
        const val SPREAD_S = 9.0
    }
}

/**
 * How far the clouds have drifted. They move with the wind while the picture is moving, frame after
 * frame, and stay where they are between one still and the next. Tied to the clock instead, they
 * leapt whenever a still was redrawn, whenever the forecast changed the wind, and once an hour.
 * The water's waves keep time the same way, coming round every [loop] seconds.
 */
class CloudDrift(private val loop: Float = LOOP) {
    private var last = 0L
    // Kept finer than it's handed out, so thousands of small steps don't add up to a drift of their own.
    private var exact = 0.0
    val at get() = exact.toFloat()

    /** A frame is being drawn at [nowMs] with the wind at [wind]. Returns where the clouds are for it. */
    fun step(nowMs: Long, wind: Float): Float {
        val gap = nowMs - last
        last = nowMs
        if (gap in 1 until RUN_MS) {
            exact += gap / 1000.0 * wind
            if (exact >= loop) exact -= loop else if (exact < 0) exact += loop
        }
        return at
    }

    companion object {
        /** Frames closer together than this are one stretch of movement. */
        const val RUN_MS = 250L
        /** Every cloud layer's speed fits a whole number of times into this, so coming round is seamless. */
        const val LOOP = 10_000f
        /** The water's waves come round every hour: their periods, 3, 4 and 6 seconds and 2 minutes, all fit into it. */
        const val WAVES = 3600f
    }
}
