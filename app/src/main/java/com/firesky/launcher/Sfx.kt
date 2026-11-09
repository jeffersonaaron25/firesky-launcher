package com.firesky.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.math.pow

/**
 * Navigation sounds. Moving along a row walks a C-major pentatonic scale, so sliding across the
 * dock plays a gentle run up or down; any order of steps sounds in tune.
 */
object Sfx {
    private var pool: SoundPool? = null
    private var move = 0; private var select = 0; private var back = 0; private var edge = 0

    /** Semitones from the C6 sample: C5 D5 E5 G5 A5 C6 D6 E6 G6 A6 C7. */
    private val SCALE = intArrayOf(-12, -10, -8, -5, -3, 0, 2, 4, 7, 9, 12)

    /** True only while a remote press is moving focus, so programmatic focus stays silent. */
    @Volatile var armed = false

    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        move = p.load(ctx, R.raw.sfx_move, 1)
        select = p.load(ctx, R.raw.sfx_select, 1)
        back = p.load(ctx, R.raw.sfx_back, 1)
        edge = p.load(ctx, R.raw.sfx_edge, 1)
        pool = p
    }

    fun move(step: Int) {
        val st = SCALE[step.coerceIn(0, SCALE.lastIndex)]
        pool?.play(move, .32f, .32f, 1, 0, 2f.pow(st / 12f))
    }

    fun select() { pool?.play(select, .45f, .45f, 2, 0, 1f) }
    fun back() { pool?.play(back, .38f, .38f, 2, 0, 1f) }
    fun edge() { pool?.play(edge, .40f, .40f, 1, 0, 1f) }
}
