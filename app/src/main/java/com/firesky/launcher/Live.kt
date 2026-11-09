package com.firesky.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader as GShader
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Half
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TAG = "FireSkyLive"

/** The baked pictures the landscape shader samples. Same seeds as the paintings. */
private object Bake {
    private const val W = 1280
    private const val H = 720

    private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color.argb((a * 255).roundToInt(), r, g, b)

    /** Stars and aurora share one stream of random numbers in the painting, so they're baked together. */
    fun night(): Pair<Bitmap, Bitmap> {
        val r = Rng(11)
        val hz = H * .74f
        val stars = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val p = Paint()
        Canvas(stars).let { cv ->
            repeat(320) {
                val x = r.next() * W; val y = r.next() * hz * .85; val s = r.next() * 1.4 + .3
                p.color = Color.argb(((r.next() * .7 + .15) * 255).roundToInt(), 255, 255, 255)
                cv.drawRect(x.toFloat(), y.toFloat(), (x + s).toFloat(), (y + s).toFloat(), p)
            }
        }
        val aurora = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        Canvas(aurora).let { cv ->
            cv.drawColor(Color.BLACK)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            for (b in 0 until 3) {
                val base = H * (.14f + b * .07f); val ph = r.next() * 6; val f = .004 + r.next() * .003
                var x = 0
                while (x < W) {
                    val y = (base + sin(x * f + ph) * 38 + sin(x * f * 2.3 + ph) * 14).toFloat()
                    val len = (90 + sin(x * .01 + ph) * 40).toFloat()
                    p.shader = LinearGradient(
                        0f, y, 0f, y + len,
                        intArrayOf(rgba(90, 255, 190, 0f), rgba(90, 255, 190, .05f + b * .012f), rgba(60, 140, 255, 0f)),
                        floatArrayOf(0f, .3f, 1f), GShader.TileMode.CLAMP,
                    )
                    cv.drawRect(x.toFloat(), y, x + 3f, y + len, p)
                    x += 3
                }
            }
        }
        return stars to aurora
    }

    fun shimmer(): Bitmap {
        val b = Bitmap.createBitmap(W, 200, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); val p = Paint(); val r = Rng(23)
        repeat(170) {
            p.color = Color.argb(((r.next() * .06 + .02) * 255).roundToInt(), 255, 255, 255)
            val x = r.next() * W; val y = r.next() * 200; val w = 40 + r.next() * 180
            cv.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + 1).toFloat(), p)
        }
        return b
    }

    /** Soft puffs that wrap at the sides, so clouds can scroll forever. */
    fun clouds(): Bitmap {
        val w = 1024; val h = 256
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG); val r = Rng(5)
        repeat(46) {
            val x = (r.next() * w).toFloat(); val y = (60 + r.next() * 130).toFloat(); val rad = (34 + r.next() * 56).toFloat()
            for (dx in listOf(-w, 0, w)) {
                p.shader = RadialGradient(x + dx, y, rad, rgba(255, 255, 255, .13f), rgba(255, 255, 255, 0f), GShader.TileMode.CLAMP)
                cv.drawCircle(x + dx, y, rad, p)
            }
        }
        return b
    }

    fun rain(): Bitmap {
        val s = 512
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); val r = Rng(9)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1.4f; strokeCap = Paint.Cap.ROUND }
        repeat(110) {
            val x = (r.next() * s).toFloat(); val y = (r.next() * s).toFloat(); val l = (14 + r.next() * 12).toFloat()
            for (dy in listOf(-s, 0, s)) cv.drawLine(x, y + dy, x, y + dy + l, p)
        }
        return b
    }

    fun snow(): Bitmap {
        val s = 512
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); val r = Rng(9)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        repeat(70) {
            val x = (r.next() * s).toFloat(); val y = (r.next() * s).toFloat(); val rad = (1 + r.next() * 1.8).toFloat()
            cv.drawCircle(x, y, rad, p)
        }
        return b
    }
}

/** Compiles the shaders and holds the pictures. Works on any GL thread. */
class Painter {
    private var prog = 0
    private var blend = 0
    private val tex = IntArray(9)
    private val fbo = IntArray(4)
    private val loc = HashMap<String, Int>()
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val halves = ByteBuffer.allocateDirect(POINTS * 4 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    private var kind = -1
    private var fxKind = -1
    private var w = 0; private var h = 0
    private var uA = 0; private var uB = 0; private var uMix = 0
    private val sky = FloatArray(9); private val lay = FloatArray(12)
    private val top = FloatArray(4); private val bot = FloatArray(4)

    private var skyProg = 0; private var dotProg = 0; private var curtProg = 0; private var curtT = -1
    private val vao = IntArray(5); private val vbo = IntArray(5)
    private var nStars = 0; private var nMilky = 0; private var nLines = 0
    private val sl = HashMap<String, Int>(); private val dl = HashMap<String, Int>()
    private val dotBuf = ByteBuffer.allocateDirect(Heavens.MAX_DOTS * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val curtBuf = ByteBuffer.allocateDirect(Heavens.MAX_CURTAIN * Heavens.CURTAIN_STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    /** The real sky to put behind the next scene; null keeps the painted one. */
    var heaven: Heavens.Frame? = null
    /** Which way the clouds drift: 1 leftwards, -1 rightwards, from where the wind at cloud height comes from. */
    var wind = 1f

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { GLES30.glGetShaderInfoLog(s) }
        return s
    }

    private fun program(frag: String, vert: String = Shader.VERT): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vert))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, frag))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private fun texture(unit: Int, b: Bitmap?, repeatX: Boolean, repeatY: Boolean) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[unit])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, if (repeatX) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, if (repeatY) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE)
        if (b != null) { GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0); b.recycle() }
    }

    /** A picture the scene can be drawn into. */
    private fun target(i: Int, unit: Int, tw: Int, th: Int) {
        texture(unit, null, false, false)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, tw, th, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[unit], 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "no frame buffer" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun init(width: Int, height: Int, cat: Heavens.Catalog?) {
        w = width; h = height
        blend = program(Shader.BLEND)
        GLES30.glUseProgram(blend)
        uA = GLES30.glGetUniformLocation(blend, "uA")
        uB = GLES30.glGetUniformLocation(blend, "uB")
        uMix = GLES30.glGetUniformLocation(blend, "uMix")
        prog = program(Shader.FRAG)
        GLES30.glUseProgram(prog)
        GLES30.glGenTextures(9, tex, 0)
        GLES30.glGenFramebuffers(4, fbo, 0)

        // Ridge rows are 514 bytes. At the default of 4 each row would start two bytes late.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        texture(0, null, false, false)
        // Mirrored, so the land can slide sideways as the camera turns without a seam.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_MIRRORED_REPEAT)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, POINTS, 4, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null)
        val (stars, aurora) = Bake.night()
        if (cat != null) { stars.recycle(); target(3, 1, w, h) } else texture(1, stars, false, false)
        texture(2, aurora, false, false)
        texture(3, Bake.shimmer(), true, false)
        texture(4, Bake.clouds(), true, false)
        texture(5, null, true, true)
        target(0, 6, w, h)
        target(1, 7, w, h)
        target(2, 8, DRIFT_W, DRIFT_H)
        listOf("uRidge", "uStars", "uAurora", "uShim", "uCloud", "uFx").forEachIndexed { i, n ->
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, n), i)
        }
        for (n in listOf(
            "uTop", "uBot", "uSky", "uLay", "uHaze", "uCore", "uGlow", "uCloudC", "uSunP", "uHz", "uWater", "uStarA",
            "uAuroraA", "uMoon", "uSunA", "uSunR", "uAa", "uT", "uDim", "uLate", "uKind", "uPan", "uLift", "uWind",
        )) loc[n] = GLES30.glGetUniformLocation(prog, n)
        if (cat != null) sky(cat)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
    }

    /** The sky's own programs, and the catalogue on the chip, where it stays. */
    private fun sky(cat: Heavens.Catalog) {
        skyProg = program(Shader.SKY_FRAG, Shader.SKY_VERT)
        dotProg = program(Shader.DOT_FRAG, Shader.DOT_VERT)
        curtProg = program(Shader.CURTAIN_FRAG, Shader.CURTAIN_VERT)
        curtT = GLES30.glGetUniformLocation(curtProg, "uT")
        for (n in listOf("uMode", "uLst", "uSinLat", "uCosLat", "uViewAz", "uSinP", "uCosP", "uR", "uCx", "uCy", "uLim", "uScale", "uClear", "uMilky", "uLines", "uT")) sl[n] = GLES30.glGetUniformLocation(skyProg, n)
        for (n in listOf("uScale", "uMoonL")) dl[n] = GLES30.glGetUniformLocation(dotProg, n)
        GLES30.glGenVertexArrays(5, vao, 0); GLES30.glGenBuffers(5, vbo, 0)
        fun upload(i: Int, data: FloatArray, vararg attrs: Pair<Int, Int>) {
            GLES30.glBindVertexArray(vao[i]); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[i])
            if (data.isNotEmpty()) {
                val b = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data); b.position(0)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, b, GLES30.GL_STATIC_DRAW)
            }
            val stride = attrs.sumOf { it.second } * 4; var off = 0
            for ((loc, n) in attrs) { GLES30.glEnableVertexAttribArray(loc); GLES30.glVertexAttribPointer(loc, n, GLES30.GL_FLOAT, false, stride, off); off += n * 4 }
        }
        upload(0, cat.stars, 0 to 2, 1 to 1, 2 to 3); nStars = cat.stars.size / 6
        upload(1, cat.milky, 0 to 2, 1 to 1); nMilky = cat.milky.size / 3
        upload(2, cat.lines, 0 to 2); nLines = cat.lines.size / 2
        upload(3, FloatArray(0), 0 to 2, 1 to 1, 2 to 4, 3 to 1)
        upload(4, FloatArray(0), 0 to 2, 1 to 1, 2 to 1, 3 to 1)
        GLES30.glBindVertexArray(0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Draws the real sky into its own picture, which the scene then shows behind the land. */
    fun heavens(f: Heavens.Frame, t: Float) {
        if (skyProg == 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[3])
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(skyProg)
        fun u(n: String, v: Float) = GLES30.glUniform1f(sl.getValue(n), v)
        u("uLst", f.lst); u("uSinLat", f.sinLat); u("uCosLat", f.cosLat); u("uViewAz", (f.viewAz * Math.PI / 180).toFloat())
        u("uSinP", f.sinP); u("uCosP", f.cosP); u("uR", f.r); u("uCx", f.cx); u("uCy", f.cy); u("uLim", f.lim)
        u("uScale", w / 1280f); u("uClear", f.clear); u("uMilky", f.milky); u("uLines", f.dark); u("uT", t)
        val mode = sl.getValue("uMode")
        if (f.milky > .02f) { GLES30.glUniform1i(mode, 1); GLES30.glBindVertexArray(vao[1]); GLES30.glDrawArrays(GLES30.GL_POINTS, 0, nMilky) }
        if (f.dark > .2f) { GLES30.glUniform1i(mode, 2); GLES30.glBindVertexArray(vao[2]); GLES30.glDrawArrays(GLES30.GL_LINES, 0, nLines) }
        GLES30.glUniform1i(mode, 0); GLES30.glBindVertexArray(vao[0]); GLES30.glDrawArrays(GLES30.GL_POINTS, 0, nStars)
        if (f.curtainCount > 0) {
            GLES30.glUseProgram(curtProg)
            GLES30.glUniform1f(curtT, t)
            curtBuf.position(0); curtBuf.put(f.curtain, 0, f.curtainCount * Heavens.CURTAIN_STRIDE); curtBuf.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[4])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, f.curtainCount * Heavens.CURTAIN_STRIDE * 4, curtBuf, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindVertexArray(vao[4]); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, f.curtainCount)
        }
        if (f.dotCount > 0) {
            GLES30.glUseProgram(dotProg)
            GLES30.glUniform1f(dl.getValue("uScale"), w / 1280f)
            GLES30.glUniform3f(dl.getValue("uMoonL"), f.moonLight[0], f.moonLight[1], f.moonLight[2])
            dotBuf.position(0); dotBuf.put(f.dots, 0, f.dotCount * 8); dotBuf.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[3])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, f.dotCount * 32, dotBuf, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindVertexArray(vao[3]); GLES30.glDrawArrays(GLES30.GL_POINTS, 0, f.dotCount)
        }
        GLES30.glBindVertexArray(0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun weather(k: Int) {
        if (k == kind) return
        kind = k
        val b = when (k) { Sky.RAIN -> Bake.rain(); Sky.SNOW -> Bake.snow(); else -> null } ?: return
        if (k == fxKind) { b.recycle(); return }
        fxKind = k
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[5])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
        b.recycle()
    }

    private fun scene(f: Flow, tw: Int, t: Float, falling: Boolean, late: Boolean) {
        GLES30.glUseProgram(prog)
        halves.position(0)
        for (i in 0 until 4) { val r = f.ridge[i]; for (j in 0 until POINTS) halves.put(Half.toHalf(r[j])) }
        halves.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, POINTS, 4, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, halves)

        val v = f.col
        for (i in 0 until 9) sky[i] = v[i] / 255f
        for (i in 0 until 12) lay[i] = v[9 + i] / 255f
        // Where each ridge begins and ends on screen, with a pixel or two to spare for its soft edge.
        val hv = heaven
        val hz = 720f * v[Sky.HZ] + (hv?.lift ?: 0f)
        for (i in 0 until 4) {
            val r = f.ridge[i]; val base = hz - (3 - i) * 30f
            var lo = r[0]; var hi = r[0]
            for (j in 1 until POINTS) { if (r[j] < lo) lo = r[j]; if (r[j] > hi) hi = r[j] }
            top[i] = base - hi - 3f; bot[i] = base - lo + 3f
        }
        // Rain and snow frozen in a still would look like scratches, so a still shows just their clouds.
        val k = kind.coerceAtLeast(0)
        val shown = if (!falling && (k == Sky.RAIN || k == Sky.SNOW)) Sky.CLOUDY else k
        GLES30.glUniform1fv(loc.getValue("uTop"), 4, top, 0)
        GLES30.glUniform1fv(loc.getValue("uBot"), 4, bot, 0)
        GLES30.glUniform3fv(loc.getValue("uSky"), 3, sky, 0)
        GLES30.glUniform3fv(loc.getValue("uLay"), 4, lay, 0)
        GLES30.glUniform4f(loc.getValue("uHaze"), v[21] / 255f, v[22] / 255f, v[23] / 255f, v[24])
        GLES30.glUniform3f(loc.getValue("uCore"), v[Sky.CORE] / 255f, v[Sky.CORE + 1] / 255f, v[Sky.CORE + 2] / 255f)
        GLES30.glUniform3f(loc.getValue("uGlow"), v[Sky.GLOW] / 255f, v[Sky.GLOW + 1] / 255f, v[Sky.GLOW + 2] / 255f)
        GLES30.glUniform3f(loc.getValue("uCloudC"), v[Sky.CLOUD] / 255f, v[Sky.CLOUD + 1] / 255f, v[Sky.CLOUD + 2] / 255f)
        // The sun where it really is, when the real sky is drawn.
        if (hv != null) GLES30.glUniform2f(loc.getValue("uSunP"), hv.sunX, hv.sunY) else GLES30.glUniform2f(loc.getValue("uSunP"), v[Sky.SUN_X], v[Sky.SUN_Y])
        GLES30.glUniform1f(loc.getValue("uHz"), v[Sky.HZ])
        GLES30.glUniform1f(loc.getValue("uWater"), v[Sky.WATER])
        // The real sky carries its own brightness for dusk, moonlight and cloud; the painted one fades by time of day.
        GLES30.glUniform1f(loc.getValue("uStarA"), if (hv != null) 1f else v[Sky.STARS])
        GLES30.glUniform1f(loc.getValue("uAuroraA"), if (hv != null) 0f else v[Sky.AURORA])
        GLES30.glUniform1f(loc.getValue("uMoon"), if (hv != null) 0f else v[Sky.MOON])
        GLES30.glUniform1f(loc.getValue("uSunA"), if (hv != null && !hv.sunIn) 0f else v[Sky.SUN_A])
        GLES30.glUniform1f(loc.getValue("uPan"), hv?.pan ?: 0f)
        GLES30.glUniform1f(loc.getValue("uLift"), hv?.lift ?: 0f)
        GLES30.glUniform1f(loc.getValue("uWind"), wind)
        GLES30.glUniform1f(loc.getValue("uSunR"), v[Sky.SUN_R])
        GLES30.glUniform1f(loc.getValue("uAa"), 1280f / tw)
        GLES30.glUniform1f(loc.getValue("uT"), t)
        GLES30.glUniform1f(loc.getValue("uDim"), Sky.dimOf(k))
        GLES30.glUniform1f(loc.getValue("uLate"), if (late) .8f else 1f)
        GLES30.glUniform1i(loc.getValue("uKind"), shown)
    }

    /** Draws the scene once, into one of the two still pictures. */
    fun still(f: Flow, slot: Int, t: Float, late: Boolean) {
        scene(f, w, t, false, late)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[slot])
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun present(a: Int, b: Int, mix: Float) {
        GLES30.glUseProgram(blend)
        GLES30.glUniform1i(uA, a)
        GLES30.glUniform1i(uB, b)
        GLES30.glUniform1f(uMix, mix)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Puts a still on the screen, or a fade between the two. */
    fun show(from: Int, to: Int, mix: Float) = present(6 + from, 6 + to, mix)

    /** The moving scene for the idle screen: a smaller picture, stretched. */
    fun moving(f: Flow, t: Float, late: Boolean) {
        scene(f, DRIFT_W, t, true, late)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[2])
        GLES30.glViewport(0, 0, DRIFT_W, DRIFT_H)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        present(8, 8, 0f)
    }

    private companion object {
        const val DRIFT_W = 960
        const val DRIFT_H = 540
    }
}

/** Minutes since midnight. */
private fun minuteNow(): Float {
    val c = Calendar.getInstance().apply { timeInMillis = Heavens.now() }
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f
}

private fun lateNow(): Boolean {
    val h = Calendar.getInstance().apply { timeInMillis = Heavens.now() }.get(Calendar.HOUR_OF_DAY)
    return h >= 23 || h < 5
}

/**
 * The home screen's backdrop. It's a still picture, so the graphics chip is free for the tiles.
 * Changing tile draws the new scene once and fades to it. Only the idle screen, with no tiles to
 * draw, moves: the land drifts and rain and snow fall. Everything happens on its own thread.
 */
class LiveView(ctx: Context, private val onFail: () -> Unit, private val onDrawn: () -> Unit) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("live").apply { start() }
    private val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private val flow = Flow()
    private val painter = Painter()
    private var sun = Sky.DEFAULT_SUN
    private var kind = Sky.CLEAR

    private var dpy: EGLDisplay? = null
    private var ctxGl: EGLContext? = null
    private var surf: EGLSurface? = null
    private var ready = false
    private var active = true
    private var parked = true
    private var dirty = false
    private var lastNs = 0L
    private var odd = false
    /** Coming back, the picture is empty until a new frame has reached the screen. */
    private var waiting = true

    private var cur = 0
    private var fade = -1f
    private var fadeS = FADE_S
    private var queued = -1f
    private var idle = false

    /** The real sky: the catalogue, the camera, and this moment of it. */
    private var cat: Heavens.Catalog? = null
    private val view = Heavens.View()
    private val skyF = Heavens.Frame()
    private var cloud = 0
    private var windFrom: Int? = null
    /** Something in the sky is moving, so the backdrop is drawing frames until it stops. */
    private var animating = false
    private var skyAcc = 0f
    private var frameN = 0
    private var lastSkyDraw = 0L
    /** How the last stretch of movement went: frames drawn, and the time they took. */
    private var animFrom = 0L
    private var animDrawn = 0
    private var animWorkNs = 0L
    private var animWorstNs = 0L

    private lateinit var choreo: Choreographer
    private val tick = Choreographer.FrameCallback { frame(it) }

    /** The sun moves slowly, so the still only needs redrawing now and then. */
    private val refresh = object : Runnable {
        override fun run() {
            if (ready && active && !idle) scene(SLOW_FADE_S)
            h.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        surfaceTextureListener = this
        isOpaque = false
        h.post { choreo = Choreographer.getInstance() }
    }

    private fun clock() = (SystemClock.uptimeMillis() / 1000.0 % 3600).toFloat()

    /** Works out the sky for now, moving the camera by [dt] seconds, and draws it if asked. */
    private fun sky(dt: Float, draw: Boolean) {
        if (cat == null) return
        val here = Place.get(context)
        Heavens.frame(skyF, Heavens.now(), here.lat, here.lon, 720.0 * flow.col[Sky.HZ], Heavens.cloudTest ?: cloud, view, dt)
        // Clouds drift away from where the wind at cloud height comes from, as seen through this window.
        painter.wind = windFrom?.let { from ->
            val s = sin((from - skyF.viewAz) * PI / 180).toFloat()
            if (abs(s) < .3f) (if (s < 0) -.3f else .3f) else s
        } ?: 1f
        painter.heaven = skyF
        if (draw) { painter.heavens(skyF, clock()); lastSkyDraw = SystemClock.uptimeMillis() }
    }

    /**
     * Once a second, a cheap look at the sky with nothing drawn. The stars turn, so at night the
     * picture is redrawn every half minute as a single frame. When the station rises or a meteor
     * falls, frames run until it's over.
     */
    private val skyClock = object : Runnable {
        override fun run() {
            if (ready && active && !idle && cat != null && fade < 0f && !animating) {
                sky(1f, false)
                val every = if (skyF.dark > 0f || skyF.dotCount > 0) 30_000L else 300_000L
                if (skyF.moving) { animating = true; skyAcc = 0f; animFrom = SystemClock.uptimeMillis(); animDrawn = 0; animWorkNs = 0; animWorstNs = 0; wakeLoop() }
                else if (SystemClock.uptimeMillis() - lastSkyDraw > every) quick()
            }
            h.postDelayed(this, 1000)
        }
    }

    /** The scene as it is now, swapped in without a fade: the stars have moved a pixel. */
    private fun quick() {
        flow.snap(minuteNow(), sun)
        sky(0f, true)
        painter.still(flow, 1 - cur, clock(), lateNow())
        cur = 1 - cur
        dirty = true
        wakeLoop()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        h.post {
            runCatching {
                egl(st)
                cat = runCatching { Heavens.catalog(context) }.onFailure { Log.w(TAG, "no star catalogue", it) }.getOrNull()
                painter.init(width, height, cat)
                painter.weather(shownKind())
                flow.snap(minuteNow(), sun)
                sky(0f, true)
                painter.still(flow, cur, clock(), lateNow())
                ready = true
                dirty = true
                wakeLoop()
                h.postDelayed(refresh, REFRESH_MS)
                h.postDelayed(skyClock, 1000)
            }.onFailure {
                Log.e(TAG, "backdrop couldn't start", it)
                main.post(onFail)
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        h.post {
            ready = false
            h.removeCallbacks(refresh)
            h.removeCallbacks(skyClock)
            dpy?.let { d ->
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(d, surf)
                EGL14.eglDestroyContext(d, ctxGl)
                EGL14.eglTerminate(d)
            }
            dpy = null
            st.release()
        }
        return false
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        if (!isOpaque) isOpaque = true
        if (waiting) { waiting = false; main.post(onDrawn) }
    }

    private fun egl(st: SurfaceTexture) {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        dpy = d
        val v = IntArray(2); check(EGL14.eglInitialize(d, v, 0, v, 1)) { "no EGL" }
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(d, attrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "no GL config" }
        ctxGl = EGL14.eglCreateContext(d, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surf = EGL14.eglCreateWindowSurface(d, cfgs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(d, surf, surf, ctxGl)) { "couldn't start GL" }
    }

    private fun wakeLoop() {
        if (!parked || !ready || !active) return
        parked = false
        lastNs = 0L
        choreo.postFrameCallback(tick)
    }

    /** Draws the scene as it should be now, once, and fades to it. */
    private fun scene(seconds: Float) {
        if (!ready) return
        if (fade >= 0f) { queued = seconds; return }
        flow.snap(minuteNow(), sun)
        sky(0f, true)
        painter.still(flow, 1 - cur, clock(), lateNow())
        fade = 0f; fadeS = seconds
        wakeLoop()
    }

    private fun swap() { EGL14.eglSwapBuffers(dpy, surf) }

    private fun smooth(v: Float): Float { val t = v.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    private fun frame(ns: Long) {
        if (!ready || !active) { parked = true; return }
        val dt = if (lastNs == 0L) 1 / 60f else ((ns - lastNs) / 1e9f).coerceIn(.001f, .05f)
        lastNs = ns
        when {
            idle -> {
                flow.drift(dt)
                odd = !odd
                skyAcc += dt
                if (odd) { sky(skyAcc, true); skyAcc = 0f; painter.moving(flow, clock(), lateNow()); swap() }
                choreo.postFrameCallback(tick)
            }
            fade >= 0f -> {
                fade += dt / fadeS
                val done = fade >= 1f
                painter.show(cur, 1 - cur, if (done) 1f else smooth(fade))
                swap()
                if (done) {
                    cur = 1 - cur
                    fade = -1f
                    if (queued >= 0f) { val q = queued; queued = -1f; scene(q) }
                }
                if (fade >= 0f || animating) choreo.postFrameCallback(tick) else { parked = true; lastNs = 0L }
            }
            animating -> {
                // Meteors need smooth frames; the station moves slowly enough for one in four.
                frameN++; skyAcc += dt
                if (Heavens.meteors.isNotEmpty() || frameN % 4 == 0) {
                    val t0 = System.nanoTime()
                    flow.snap(minuteNow(), sun)
                    sky(skyAcc, true); skyAcc = 0f
                    painter.still(flow, cur, clock(), lateNow())
                    painter.show(cur, cur, 0f)
                    swap()
                    val w = System.nanoTime() - t0
                    animDrawn++; animWorkNs += w; if (w > animWorstNs) animWorstNs = w
                }
                if (skyF.moving) choreo.postFrameCallback(tick) else {
                    animating = false; parked = true; lastNs = 0L
                    if (animDrawn > 0) Log.i(TAG, "sky moved %.0f s: %d frames, %.1f ms each, worst %.1f".format(
                        (SystemClock.uptimeMillis() - animFrom) / 1000.0, animDrawn, animWorkNs / 1e6 / animDrawn, animWorstNs / 1e6))
                }
            }
            else -> {
                if (dirty) { painter.show(cur, cur, 0f); swap(); dirty = false }
                parked = true
                lastNs = 0L
            }
        }
    }

    /** The tile you've settled on: its land, and the colour to wash it with. */
    fun focus(id: String?, hue: FloatArray?) = h.post {
        flow.target(id?.let(Sky::shape), hue)
        if (!idle) scene(FADE_S)
    }

    fun weather(kind: Int, sun: Sun, cloud: Int = 0, windFrom: Int? = null) = h.post {
        this.kind = kind; this.sun = sun; this.cloud = cloud; this.windFrom = windFrom
        if (ready) { painter.weather(shownKind()); if (!idle) scene(SLOW_FADE_S) }
    }

    private fun shownKind() = if (Heavens.cloudTest == 0) Sky.CLEAR else kind

    /**
     * From adb only (the sender needs the DUMP permission), to look at the sky as it isn't right now:
     *   am broadcast -a com.firesky.launcher.SKY --ei cloud 0 --el ahead 90   a clear sky, 90 minutes on
     *   am broadcast -a com.firesky.launcher.SKY --ei cloud 0 --ez pass true  a minute before the station's next pass
     *   am broadcast -a com.firesky.launcher.SKY --ef kp 8                    a geomagnetic storm
     *   am broadcast -a com.firesky.launcher.SKY                              back to the real sky
     */
    private val test = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = System.currentTimeMillis()
            Heavens.cloudTest = i.getIntExtra("cloud", -1).takeIf { it >= 0 }
            Heavens.kpTest = i.getFloatExtra("kp", -1f).takeIf { it >= 0 }?.toDouble()
            val next = Heavens.passes.firstOrNull { it.start > now }
            Heavens.ahead = if (i.getBooleanExtra("pass", false) && next != null) next.start - 60_000 - now else i.getLongExtra("ahead", 0) * 60_000
            Log.i(TAG, "sky test: cloud ${Heavens.cloudTest} kp ${Heavens.kpTest} ahead ${Heavens.ahead / 60_000} min, next pass ${next?.let { java.util.Date(it.start) }}")
            if (ready) { painter.weather(shownKind()); animating = false; if (!idle) scene(SLOW_FADE_S) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(test, IntentFilter("com.firesky.launcher.SKY"), android.Manifest.permission.DUMP, h)
    }

    /** Only the idle screen has the chip to itself, so only it moves. */
    fun idle(on: Boolean) = h.post {
        if (!ready || on == idle) return@post
        idle = on
        if (on) {
            flow.startDrift()
            wakeLoop()
        } else {
            // Leave from the frame on screen, so nothing jumps.
            painter.still(flow, cur, clock(), lateNow())
            flow.stopDrift()
            fade = -1f
            scene(FADE_S)
        }
    }

    /** Waking: start from the scene you left, and fade to now. */
    fun dawn() = h.post {
        if (!ready) return@post
        val m = minuteNow()
        val old = if (Sky.isNight(m, sun)) sun.set.toFloat() else sun.rise - 120f
        val s = flow.shape; val t = flow.tint
        flow.target(null, null)
        flow.snap(old, sun)
        painter.still(flow, cur, clock(), lateNow())
        flow.target(s, t)
        fade = -1f
        idle = false
        animating = false
        flow.stopDrift()
        scene(DAWN_FADE_S)
    }

    fun pause() = h.post { active = false; animating = false }

    fun resume() {
        waiting = true
        h.post { active = true; dirty = true; animating = false; wakeLoop() }
    }

    /** The backdrop as it is now, on its own. */
    fun frame(): Bitmap? = runCatching { getBitmap(960, 540) }.getOrNull()

    /** A tiny copy of the scene. Stretched back up, it's the frosted background behind the panel. */
    fun glass(): Bitmap? = runCatching { getBitmap(160, 90) }.getOrNull()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(test) }
        h.post { thread.quitSafely() }
    }

    private companion object {
        const val FADE_S = .45f
        const val SLOW_FADE_S = 1.5f
        const val DAWN_FADE_S = 1.6f
        const val REFRESH_MS = 5 * 60_000L
    }
}
