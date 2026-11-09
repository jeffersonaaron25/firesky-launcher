package com.firesky.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The painted wallpaper. A line-for-line port of the mock's canvas code, with the same seeded
 * random numbers, so each time of day paints the exact scene approved in the mock.
 * Drawn in 1280x720 design units and scaled up to the screen.
 */
object Landscape {
    private const val W = 1280f
    private const val H = 720f

    class Orb(val x: Float, val y: Float, val r: Float, val core: Int, val glow: Int)
    class Pal(
        val sky: IntArray, val layers: IntArray, val haze: Int, val hz: Float, val seed: Int,
        val sun: Orb? = null, val moon: Orb? = null,
        val stars: Boolean = false, val aurora: Boolean = false, val water: Boolean = false,
    )

    private fun c(hex: Long) = hex.toInt()
    private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color.argb((a * 255).roundToInt(), r, g, b)

    val PAL = mapOf(
        "dusk" to Pal(
            intArrayOf(c(0xFF1A1636), c(0xFF6A3A6C), c(0xFFF2A066)),
            intArrayOf(c(0xFF6B4470), c(0xFF47305A), c(0xFF2C1F40), c(0xFF170F25)),
            rgba(242, 160, 102, .16f), .72f, 7,
            sun = Orb(.7f, .5f, 30f, c(0xFFFFE3B8), c(0xFFFFBE82)), water = true,
        ),
        "alpine" to Pal(
            intArrayOf(c(0xFF2C5F9E), c(0xFF86B5DE), c(0xFFE3EEF5)),
            intArrayOf(c(0xFFA3BBD0), c(0xFF6D8CA8), c(0xFF46637B), c(0xFF223646)),
            rgba(225, 238, 246, .22f), .8f, 3,
            sun = Orb(.2f, .18f, 24f, c(0xFFFFFBE8), c(0xFFFFFAE1)),
        ),
        "night" to Pal(
            intArrayOf(c(0xFF03060D), c(0xFF0A1930), c(0xFF14404F)),
            intArrayOf(c(0xFF123044), c(0xFF0C2132), c(0xFF071520), c(0xFF030A10)),
            rgba(60, 180, 160, .07f), .74f, 11,
            moon = Orb(.8f, .18f, 16f, c(0xFFEEF2FF), c(0xFFC8D7FF)), stars = true, aurora = true, water = true,
        ),
        "amber" to Pal(
            intArrayOf(c(0xFF16112B), c(0xFF58385C), c(0xFFE39457)),
            intArrayOf(c(0xFF5A3A55), c(0xFF3C2742), c(0xFF24182E), c(0xFF120C19)),
            rgba(227, 148, 87, .14f), .74f, 17,
            sun = Orb(.3f, .6f, 26f, c(0xFFFFF0D0), c(0xFFFFBE78)), water = true,
        ),
    )

    /** Which scene belongs to this time of day. */
    fun keyFor(hour: Int, minute: Int): String = when {
        hour in 5..6 -> "amber"
        hour in 7..16 -> "alpine"
        hour in 17..20 || (hour == 21 && minute < 30) -> "dusk"
        else -> "night"
    }

    fun paint(key: String, scale: Float): Bitmap {
        val p = PAL.getValue(key)
        val bmp = Bitmap.createBitmap((W * scale).roundToInt(), (H * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(scale, scale)
        val r = Rng(p.seed)
        val hz = H * p.hz
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(0f, 0f, 0f, hz, p.sky, floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, W, H, paint)
        paint.shader = null

        if (p.stars) repeat(320) {
            val x = r.next() * W; val y = r.next() * hz * .85; val s = r.next() * 1.4 + .3
            paint.color = Color.argb(((r.next() * .7 + .15) * 255).roundToInt(), 255, 255, 255)
            cv.drawRect(x.toFloat(), y.toFloat(), (x + s).toFloat(), (y + s).toFloat(), paint)
        }

        if (p.aurora) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            for (b in 0 until 3) {
                val base = H * (.14f + b * .07f); val ph = r.next() * 6; val f = .004 + r.next() * .003
                var x = 0
                while (x < W) {
                    val y = (base + sin(x * f + ph) * 38 + sin(x * f * 2.3 + ph) * 14).toFloat()
                    val len = (90 + sin(x * .01 + ph) * 40).toFloat()
                    paint.shader = LinearGradient(
                        0f, y, 0f, y + len,
                        intArrayOf(rgba(90, 255, 190, 0f), rgba(90, 255, 190, .05f + b * .012f), rgba(60, 140, 255, 0f)),
                        floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP,
                    )
                    cv.drawRect(x.toFloat(), y, x + 3f, y + len, paint)
                    x += 3
                }
            }
            paint.shader = null; paint.xfermode = null
        }

        fun orb(o: Orb) {
            val x = o.x * W; val y = o.y * H
            val gr = Color.red(o.glow); val gg = Color.green(o.glow); val gb = Color.blue(o.glow)
            paint.shader = RadialGradient(
                x, y, o.r * 9,
                intArrayOf(rgba(gr, gg, gb, .55f), rgba(gr, gg, gb, 0f)),
                floatArrayOf(.5f / 9f, 1f), Shader.TileMode.CLAMP,
            )
            cv.drawRect(0f, 0f, W, H, paint)
            paint.shader = null
            paint.color = o.core
            cv.drawCircle(x, y, o.r, paint)
        }
        p.sun?.let(::orb)
        p.moon?.let(::orb)

        val L = p.layers.size
        for (i in 0 until L) {
            val base = hz - (L - 1 - i) * 30
            val amp = 200f - i * 38
            val pts = ridge(r, amp, .52)
            val path = Path().apply {
                moveTo(0f, H)
                for (j in pts.indices) lineTo(j / (pts.size - 1f) * W, base - pts[j])
                lineTo(W, H); close()
            }
            paint.color = p.layers[i]
            cv.drawPath(path, paint)
            if (i < L - 1) {
                paint.shader = LinearGradient(0f, base - amp, 0f, H, intArrayOf(Color.TRANSPARENT, p.haze), null, Shader.TileMode.CLAMP)
                cv.drawRect(0f, base - amp, W, base - amp + H, paint)
                paint.shader = null
            }
        }

        if (p.water) {
            val tmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
            val d = H - hz
            cv.save()
            cv.translate(0f, 2 * hz)
            cv.scale(1f, -1f)
            cv.drawBitmap(
                tmp,
                Rect(0, ((hz - d) * scale).roundToInt(), (W * scale).roundToInt(), (hz * scale).roundToInt()),
                RectF(0f, hz - d, W, hz),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            cv.restore()
            tmp.recycle()
            paint.color = rgba(4, 8, 16, .4f)
            cv.drawRect(0f, hz, W, H, paint)
            repeat(170) {
                paint.color = Color.argb(((r.next() * .06 + .02) * 255).roundToInt(), 255, 255, 255)
                val x = r.next() * W; val y = hz + r.next() * d; val w = 40 + r.next() * 180
                cv.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + 1).toFloat(), paint)
            }
        }

        paint.shader = RadialGradient(
            W / 2, H / 2, H * .95f,
            intArrayOf(Color.TRANSPARENT, rgba(0, 0, 0, .45f)),
            floatArrayOf(.35f / .95f, 1f), Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, W, H, paint)
        return bmp
    }
}
