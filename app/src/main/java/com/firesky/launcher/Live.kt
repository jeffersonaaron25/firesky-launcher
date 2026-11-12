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
            // Wrapped round at the sides too, as the clouds are, since a place's water slides it along.
            for (dx in listOf(0, -W)) cv.drawRect((x + dx).toFloat(), y.toFloat(), (x + dx + w).toFloat(), (y + 1).toFloat(), p)
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
    private val tex = IntArray(10)
    private val fbo = IntArray(4)
    private val loc = HashMap<String, Int>()
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val halves = ByteBuffer.allocateDirect(POINTS * 4 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    /** Another place's heights, buildings and walls, and which one the texture holds now. */
    private val landBuf = ByteBuffer.allocateDirect(Places.DETAIL * 4 * 4 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    private var landShown: Places.Land? = null
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
    private val sparkBuf = ByteBuffer.allocateDirect(Fireworks.MAX * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val curtBuf = ByteBuffer.allocateDirect(Heavens.MAX_CURTAIN * Heavens.CURTAIN_STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    /** The real sky to put behind the next scene; null keeps the painted one. */
    var heaven: Heavens.Frame? = null
    /** Which way the clouds drift: 1 leftwards, -1 rightwards, from where the wind at cloud height comes from. */
    var wind = 1f
    private val drift = CloudDrift()
    /** The water's waves, which like the clouds move only while the picture does: stills redrawn later don't jump. */
    private val waves = CloudDrift(CloudDrift.WAVES)
    /** A thunderstorm: darker cloud than rain alone, and on the idle screen, lightning. */
    var storm = false
    /** How bright the lightning is in the next moving frame, where it struck, its shape, and whether the bolt shows. */
    var flash = 0f; var boltX = 640f; var boltShape = 0f; var bolt = false
    /** How hard the wind blows, from [Wind.factor]: 1 is a light breeze. */
    var speed = 1f
    /** A balloon as x, y, radius and how much it shows. */
    val balloon = FloatArray(4)

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
        GLES30.glGenTextures(10, tex, 0)
        GLES30.glGenFramebuffers(4, fbo, 0)

        // Ridge rows are 514 bytes. At the default of 4 each row would start two bytes late.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        texture(0, null, false, false)
        // Mirrored, so the land can slide sideways as the camera turns without a seam.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_MIRRORED_REPEAT)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, POINTS, 4, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null)
        // The other places: a column for every pixel, mirrored at the ends like the ridges.
        texture(9, null, false, false)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_MIRRORED_REPEAT)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, Places.DETAIL, 4, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        if (cat != null) {
            // The real sky is drawn into a picture of its own and brings its own aurora, so the painted
            // stars and aurora would be made and never shown.
            target(3, 1, w, h)
            texture(2, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), false, false)
        } else {
            val (stars, aurora) = Bake.night()
            texture(1, stars, false, false)
            texture(2, aurora, false, false)
        }
        texture(3, Bake.shimmer(), true, false)
        texture(4, Bake.clouds(), true, false)
        texture(5, null, true, true)
        target(0, 6, w, h)
        target(1, 7, w, h)
        target(2, 8, DRIFT_W, DRIFT_H)
        listOf("uRidge", "uStars", "uAurora", "uShim", "uCloud", "uFx").forEachIndexed { i, n ->
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, n), i)
        }
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uLand"), 9)
        for (n in listOf(
            "uTop", "uBot", "uSky", "uLay", "uHaze", "uCore", "uGlow", "uCloudC", "uSunP", "uHz", "uWater", "uStarA",
            "uAuroraA", "uMoon", "uSunA", "uSunR", "uAa", "uT", "uDim", "uLate", "uKind", "uPan", "uLift", "uDrift", "uWave", "uFlash", "uBolt",
            "uSlant", "uBalloon", "uPlace", "uBase", "uHazeH", "uWin", "uLit", "uWl", "uFront", "uRipple", "uLight", "uLightC", "uGrass", "uSway",
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
        // Another place's land goes up only when it changes: it stays put while the sky moves.
        val land = f.land
        if (land != null && land !== landShown) {
            landBuf.position(0)
            for (i in 0 until 4) for (j in 0 until Places.DETAIL) {
                landBuf.put(Half.toHalf(land.height[i][j])); landBuf.put(Half.toHalf(land.seed[i][j]))
                landBuf.put(Half.toHalf(land.wall[i][j])); landBuf.put(0.toShort())
            }
            landBuf.position(0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE9)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[9])
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, Places.DETAIL, 4, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, landBuf)
            landShown = land
        }

        val v = f.col
        for (i in 0 until 9) sky[i] = v[i] / 255f
        for (i in 0 until 12) lay[i] = v[9 + i] / 255f
        // Where each ridge begins and ends on screen, with a pixel or two to spare for its soft edge.
        val hv = heaven
        val hz = 720f * v[Sky.HZ] + (hv?.lift ?: 0f)
        for (i in 0 until 4) {
            val r = land?.height?.get(i) ?: f.ridge[i]
            val base = if (land != null) hz + land.base[i] else hz - (3 - i) * 30f
            var lo = r[0]; var hi = r[0]
            for (j in 1 until r.size) { if (r[j] < lo) lo = r[j]; if (r[j] > hi) hi = r[j] }
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
        // The painted sun keeps its height above the place's own horizon, as the real sky does.
        val sunY = hv?.sunY ?: (v[Sky.SUN_Y] + (land?.horizon ?: 0f) / 720f)
        GLES30.glUniform2f(loc.getValue("uSunP"), hv?.sunX ?: v[Sky.SUN_X], sunY)
        GLES30.glUniform1f(loc.getValue("uHz"), v[Sky.HZ])
        // Mountains have water below them only in some lights; other places have it or don't.
        GLES30.glUniform1f(loc.getValue("uWater"), if (land != null) (if (land.water != null) 1f else 0f) else v[Sky.WATER])
        GLES30.glUniform1i(loc.getValue("uPlace"), if (land != null) 1 else 0)
        if (land != null) {
            GLES30.glUniform1fv(loc.getValue("uBase"), 4, land.base, 0)
            GLES30.glUniform1fv(loc.getValue("uHazeH"), 4, land.haze, 0)
            GLES30.glUniform1fv(loc.getValue("uWin"), 4, land.windows, 0)
        }
        GLES30.glUniform1f(loc.getValue("uLit"), f.lit)
        GLES30.glUniform1f(loc.getValue("uWl"), land?.water ?: 0f)
        GLES30.glUniform1i(loc.getValue("uFront"), land?.front ?: 4)
        GLES30.glUniform1f(loc.getValue("uRipple"), if (land?.water != null) 1f else 0f)
        // Plains have the wind in the grass and the trees, leaning the way the clouds drift, further the harder it blows.
        val plains = land?.kind == Places.PLAINS
        GLES30.glUniform1f(loc.getValue("uGrass"), if (plains) 1f else 0f)
        GLES30.glUniform1f(loc.getValue("uSway"), if (plains) 1.7f * speed.coerceAtMost(2f) * wind else 0f)
        // The light a place's water catches: the sun when it's up, laying a path the lower it is, else the moon in its phase.
        var lx = 0f; var ly = 0f; var la = 0f; var lr = .86f; var lg = .9f; var lb = 1f
        if (land?.water != null) {
            val sunA = if (hv != null && !hv.sunIn) 0f else v[Sky.SUN_A]
            if (sunA > .05f) {
                lx = (hv?.sunX ?: v[Sky.SUN_X]) * 1280f; ly = sunY * 720f
                la = sunA * (.25f + .75f * (ly / 720f / .6f).coerceIn(0f, 1f))
                lr = v[Sky.CORE] / 255f; lg = v[Sky.CORE + 1] / 255f; lb = v[Sky.CORE + 2] / 255f
            } else if (hv != null) {
                for (k in 0 until hv.dotCount) if (hv.dots[k * 8 + 7] == 1f) {
                    lx = hv.dots[k * 8]; ly = hv.dots[k * 8 + 1]
                    la = hv.dots[k * 8 + 6] * (.3f + .7f * (hv.moonLight[2] + 1f) / 2f)
                }
            } else if (v[Sky.MOON] > .05f) { lx = .8f * 1280f; ly = .18f * 720f; la = v[Sky.MOON] * .8f }
        }
        GLES30.glUniform3f(loc.getValue("uLight"), lx, ly, la)
        GLES30.glUniform3f(loc.getValue("uLightC"), lr, lg, lb)
        // The real sky carries its own brightness for dusk, moonlight and cloud; the painted one fades by time of day.
        GLES30.glUniform1f(loc.getValue("uStarA"), if (hv != null) 1f else v[Sky.STARS])
        GLES30.glUniform1f(loc.getValue("uAuroraA"), if (hv != null) 0f else v[Sky.AURORA])
        GLES30.glUniform1f(loc.getValue("uMoon"), if (hv != null) 0f else v[Sky.MOON])
        GLES30.glUniform1f(loc.getValue("uSunA"), if (hv != null && !hv.sunIn) 0f else v[Sky.SUN_A])
        GLES30.glUniform1f(loc.getValue("uPan"), hv?.pan ?: 0f)
        GLES30.glUniform1f(loc.getValue("uLift"), hv?.lift ?: 0f)
        GLES30.glUniform1f(loc.getValue("uDrift"), drift.step(SystemClock.uptimeMillis(), wind * speed))
        GLES30.glUniform1f(loc.getValue("uWave"), waves.step(SystemClock.uptimeMillis(), 1f))
        GLES30.glUniform1f(loc.getValue("uSlant"), Wind.slant(wind, speed))
        // The balloon only drifts across the moving picture: caught in a still, it would hang there.
        GLES30.glUniform4f(loc.getValue("uBalloon"), balloon[0], balloon[1], balloon[2], if (falling) balloon[3] else 0f)
        GLES30.glUniform1f(loc.getValue("uSunR"), v[Sky.SUN_R])
        GLES30.glUniform1f(loc.getValue("uAa"), 1280f / tw)
        GLES30.glUniform1f(loc.getValue("uT"), t)
        GLES30.glUniform1f(loc.getValue("uDim"), if (storm && k == Sky.RAIN) STORM_DIM else Sky.dimOf(k))
        // Lightning is only in the moving picture: caught in a still, it would stay on screen.
        val lit = if (falling && storm) flash else 0f
        GLES30.glUniform1f(loc.getValue("uFlash"), lit)
        GLES30.glUniform3f(loc.getValue("uBolt"), boltX, boltShape, if (bolt) lit else 0f)
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

    /** One of the still pictures, black: what the very first picture fades in from. */
    fun black(slot: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[slot])
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Puts a still on the screen, or a fade between the two. */
    fun show(from: Int, to: Int, mix: Float) = present(6 + from, 6 + to, mix)

    /** The idle picture again, as last drawn, for a frame in which only the fireworks move. */
    fun again() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        present(8, 8, 0f)
    }

    /** Fireworks straight over the picture on screen, as light added to it. */
    fun sparks(d: FloatArray, n: Int) {
        if (n <= 0 || dotProg == 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_ZERO, GLES30.GL_ONE)
        GLES30.glUseProgram(dotProg)
        GLES30.glUniform1f(dl.getValue("uScale"), w / 1280f)
        sparkBuf.position(0); sparkBuf.put(d, 0, n * 8); sparkBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[3])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 32, sparkBuf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindVertexArray(vao[3]); GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n)
        GLES30.glBindVertexArray(0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /**
     * A frame of the home screen's gentle motion, with rain and snow left as cloud as in a still:
     * straight onto the screen, or at [half] size and stretched, as the idle screen draws.
     */
    fun ambient(f: Flow, t: Float, late: Boolean, half: Boolean) {
        if (half) {
            scene(f, DRIFT_W, t, false, late)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[2])
            GLES30.glViewport(0, 0, DRIFT_W, DRIFT_H)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            present(8, 8, 0f)
        } else {
            scene(f, w, t, false, late)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

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
        /** A storm greys the scene more than rain does. */
        const val STORM_DIM = .36f
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
    /** The tile settled on, whose place is shown. */
    private var focusId: String? = null
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
    /** How far the real sky's camera has moved the land down the screen, in the backdrop's 720 units. */
    @Volatile var lift = 0f
        private set
    private var windKmh = 10f
    /** Wind asked for over ADB, in place of the forecast's. */
    private var windTest: Float? = null
    /** How long the idle screen has been up, and whether a balloon is out this morning. */
    private var idleS = 0f
    private var balloonOut = false
    /** New Year's fireworks are due, looked at once a second; and they're being drawn over the home screen. */
    private var fwOn = false
    private var sparkling = false
    private val sparkDots = FloatArray(Fireworks.MAX * 8)
    private var sparkN = 0
    private var fwFrom = 0L; private var fwDrawn = 0; private var fwWorkNs = 0L; private var fwWorstNs = 0L
    private val fireworks get() = fwOn || Fireworks.busy

    /** Moves the fireworks on and gathers their sparks, dimmed by cloud and by the shade the backdrop paints in. */
    private fun stepFireworks(dt: Float) {
        sparkN = 0
        val clear = 1f - .75f * (Heavens.cloudTest ?: cloud) / 100f
        Fireworks.step(dt, fwOn) { x, y, size, r, g, b, a ->
            if (sparkN < Fireworks.MAX) {
                val o = sparkN * 8
                sparkDots[o] = x; sparkDots[o + 1] = y; sparkDots[o + 2] = size
                sparkDots[o + 3] = r; sparkDots[o + 4] = g; sparkDots[o + 5] = b
                sparkDots[o + 6] = a * clear * Fireworks.shade(x, y); sparkDots[o + 7] = 0f
                sparkN++
            }
        }
    }

    private var storm = false
    /** A storm asked for over ADB, to see one without waiting for the weather. */
    private var stormTest = false
    private val lightning = Storm()
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

    /*
     * The home screen's gentle motion. Left alone for a few seconds with nothing open over it, the
     * picture moves at a low frame rate where something in it naturally would: water, a city's lights,
     * real clouds and the stars. Mountains and plains on a clear day stay still, and draw nothing.
     * Any key stills it at once, so moving between tiles has the chip to itself, and the frame on
     * screen stays put.
     */
    /** Nothing is open over the home screen. */
    private var calm = false
    private var lastPoke = 0L
    /** Frames of motion are being drawn. */
    private var ambient = false
    /** What's on screen is a frame of motion, which the still behind it no longer matches. */
    private var shownAmbient = false
    private var ambientLast = 0L
    private var ambientFrom = 0L; private var ambientDrawn = 0; private var ambientWorkNs = 0L; private var ambientWorstNs = 0L
    /** From adb, for measuring: frames a second, half size or full, and waiting for the chip to finish each frame. */
    @Volatile private var ambientFps = AMBIENT_FPS
    @Volatile private var ambientHalf = true
    @Volatile private var ambientFinish = false
    @Volatile private var ambientAfter = AMBIENT_AFTER_MS
    private var ambientRunHalf = true
    private val settle = Runnable { startAmbient() }

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
        // The sun, moon and stars rise and set on the place's own horizon: over water that's well above the mountains'.
        val hz = 720.0 * flow.col[Sky.HZ] + (flow.land?.horizon ?: 0f)
        Heavens.frame(skyF, Heavens.now(), here.lat, here.lon, hz, Heavens.cloudTest ?: cloud, view, dt)
        // Clouds drift away from where the wind at cloud height comes from, as seen through this window.
        painter.wind = windFrom?.let { from ->
            val s = sin((from - skyF.viewAz) * PI / 180).toFloat()
            if (abs(s) < .3f) (if (s < 0) -.3f else .3f) else s
        } ?: 1f
        painter.heaven = skyF
        lift = skyF.lift
        if (draw) { painter.heavens(skyF, clock()); lastSkyDraw = SystemClock.uptimeMillis() }
    }

    /** A key was pressed: the motion stops where it is, and comes back after a few quiet seconds. */
    fun poke() = h.post {
        lastPoke = SystemClock.uptimeMillis()
        stopAmbient()
        maybeAmbient()
    }

    /** Whether the home screen is showing with nothing open over it. */
    fun calm(on: Boolean) = h.post {
        calm = on
        if (on) maybeAmbient() else stopAmbient()
    }

    /** Starts the motion once the home screen has been left alone long enough, or plans to. */
    private fun maybeAmbient() {
        h.removeCallbacks(settle)
        if (!calm || ambient || !Prefs.motion) return
        val wait = lastPoke + ambientAfter - SystemClock.uptimeMillis()
        if (wait > 0) h.postDelayed(settle, wait) else startAmbient()
    }

    /** Whether anything in the picture would move now: water, a city's lit windows, real clouds or fog, or stars. */
    private fun moves(): Boolean {
        val land = flow.land
        return land?.water != null || (land != null && land.windows.any { it > 0f } && flow.lit > .001f) ||
            shownKind() != Sky.CLEAR || (cat != null && skyF.dark > .05f)
    }

    private fun startAmbient() {
        // A fade, the station or fireworks have the screen; the motion starts again when they're done.
        if (!ready || !active || idle || !calm || ambient || !Prefs.motion || fade >= 0f || animating || sparkling || !moves()) return
        ambient = true
        ambientLast = 0L
        ambientFrom = SystemClock.uptimeMillis(); ambientDrawn = 0; ambientWorkNs = 0; ambientWorstNs = 0
        ambientRunHalf = ambientHalf
        h.post(ambientTick)
    }

    private fun stopAmbient() {
        h.removeCallbacks(settle)
        if (!ambient) return
        ambient = false
        h.removeCallbacks(ambientTick)
        if (ambientDrawn > 0) Log.i(TAG, "motion %.0f s: %d frames, %.1f ms each, worst %.1f%s".format(
            (SystemClock.uptimeMillis() - ambientFrom) / 1000.0, ambientDrawn, ambientWorkNs / 1e6 / ambientDrawn, ambientWorstNs / 1e6,
            if (ambientRunHalf) " (half size)" else ""))
    }

    /** Before a still is shown again, it's made to match the frame of motion on screen, so nothing jumps. */
    private fun matchStill() {
        if (!shownAmbient) return
        painter.still(flow, cur, clock(), lateNow())
        shownAmbient = false
    }

    private val ambientTick = object : Runnable {
        override fun run() {
            if (!ambient) return
            if (!ready || !active || idle || fade >= 0f || animating || sparkling || !moves()) { stopAmbient(); return }
            val t0 = System.nanoTime()
            val now = SystemClock.uptimeMillis()
            val dt = if (ambientLast == 0L) 0f else ((now - ambientLast) / 1000f).coerceAtMost(.25f)
            ambientLast = now
            sky(dt, true)
            painter.ambient(flow, clock(), lateNow(), ambientRunHalf)
            if (ambientFinish) GLES30.glFinish()
            swap()
            shownAmbient = true
            val work = System.nanoTime() - t0
            ambientDrawn++; ambientWorkNs += work; if (work > ambientWorstNs) ambientWorstNs = work
            h.postDelayed(this, (1000L / ambientFps - work / 1_000_000).coerceAtLeast(1))
        }
    }

    /**
     * Once a second, a cheap look at the sky with nothing drawn. The stars turn, so at night the
     * picture is redrawn every half minute as a single frame. When the station rises or a meteor
     * falls, frames run until it's over.
     */
    private val skyClock = object : Runnable {
        override fun run() {
            if (ready) fwOn = Fireworks.on(Calendar.getInstance().apply { timeInMillis = Heavens.now() })
            if (ready && active && !idle && fireworks && !sparkling) {
                sparkling = true; fwFrom = SystemClock.uptimeMillis(); fwDrawn = 0; fwWorkNs = 0; fwWorstNs = 0
                wakeLoop()
            }
            if (ready && active && calm && !ambient && SystemClock.uptimeMillis() - lastPoke > ambientAfter) maybeAmbient()
            if (ready && active && !idle && cat != null && fade < 0f && !animating && !ambient) {
                sky(1f, false)
                val every = if (skyF.dark > 0f || skyF.dotCount > 0) 30_000L else 300_000L
                if (skyF.moving) { matchStill(); animating = true; skyAcc = 0f; animFrom = SystemClock.uptimeMillis(); animDrawn = 0; animWorkNs = 0; animWorstNs = 0; wakeLoop() }
                else if (SystemClock.uptimeMillis() - lastSkyDraw > every) quick()
            }
            h.postDelayed(this, 1000)
        }
    }

    /** The scene as it is now, swapped in without a fade: the stars have moved a pixel. */
    private fun quick() {
        apply()
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
                apply()
                painter.weather(shownKind())
                glUp = true
                // The selected tile, and so the colour, is usually known within a moment of starting.
                if (focusId != null) firstLight() else h.postDelayed(firstLightLate, FIRST_WAIT_MS)
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
            glUp = false
            h.removeCallbacks(firstLightLate)
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

    /** The graphics are set up, though the first picture may still be waiting for the selected tile. */
    private var glUp = false
    private val firstLightLate = Runnable { firstLight() }

    /**
     * The first picture after starting, faded in from black in the selected tile's own colours,
     * rather than appearing at once in others that change a moment later.
     */
    private fun firstLight() {
        if (ready || !glUp) return
        h.removeCallbacks(firstLightLate)
        flow.target(flow.shape, flow.tint, landFor(focusId))
        flow.snap(minuteNow(), sun)
        sky(0f, true)
        painter.still(flow, 1 - cur, clock(), lateNow())
        painter.black(cur)
        ready = true
        fade = 0f; fadeS = FIRST_FADE_S
        wakeLoop()
        h.postDelayed(refresh, REFRESH_MS)
        h.postDelayed(skyClock, 1000)
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
        stopAmbient()
        matchStill()
        apply()
        // Looked at again each time, so a daily place changes with the morning and a new setting shows.
        flow.target(flow.shape, flow.tint, landFor(focusId))
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
                idleS += dt
                if (balloonOut) Balloon.at(idleS, painter.wind, painter.balloon) else painter.balloon[3] = 0f
                if (painter.storm) {
                    painter.flash = lightning.step(dt)
                    painter.boltX = lightning.x; painter.boltShape = lightning.shape; painter.bolt = lightning.bolt
                }
                if (fireworks) stepFireworks(dt) else sparkN = 0
                // The land moves every other frame; fireworks, which need every frame, go over the last one in between.
                if (odd) { sky(skyAcc, true); skyAcc = 0f; painter.moving(flow, clock(), lateNow()); painter.sparks(sparkDots, sparkN); swap() }
                else if (sparkN > 0) { painter.again(); painter.sparks(sparkDots, sparkN); swap() }
                choreo.postFrameCallback(tick)
            }
            fade >= 0f -> {
                fade += dt / fadeS
                val done = fade >= 1f
                if (sparkling) stepFireworks(dt)
                painter.show(cur, 1 - cur, if (done) 1f else smooth(fade))
                if (sparkling) painter.sparks(sparkDots, sparkN)
                swap()
                if (done) {
                    cur = 1 - cur
                    fade = -1f
                    if (queued >= 0f) { val q = queued; queued = -1f; scene(q) } else maybeAmbient()
                }
                if (fade >= 0f || animating || sparkling) choreo.postFrameCallback(tick) else { parked = true; lastNs = 0L }
            }
            animating || sparkling -> {
                frameN++
                val t0 = System.nanoTime()
                if (sparkling) stepFireworks(dt)
                // Meteors need smooth frames; the station moves slowly enough for one in four.
                var drew = false
                if (animating) {
                    skyAcc += dt
                    if (Heavens.meteors.isNotEmpty() || frameN % 4 == 0) {
                        flow.snap(minuteNow(), sun)
                        sky(skyAcc, true); skyAcc = 0f
                        painter.still(flow, cur, clock(), lateNow())
                        drew = true
                    }
                }
                if (drew || sparkling) {
                    painter.show(cur, cur, 0f)
                    if (sparkling) painter.sparks(sparkDots, sparkN)
                    swap()
                    val w = System.nanoTime() - t0
                    if (drew) { animDrawn++; animWorkNs += w; if (w > animWorstNs) animWorstNs = w }
                    if (sparkling) { fwDrawn++; fwWorkNs += w; if (w > fwWorstNs) fwWorstNs = w }
                }
                if (animating && !skyF.moving) {
                    animating = false
                    maybeAmbient()
                    if (animDrawn > 0) Log.i(TAG, "sky moved %.0f s: %d frames, %.1f ms each, worst %.1f".format(
                        (SystemClock.uptimeMillis() - animFrom) / 1000.0, animDrawn, animWorkNs / 1e6 / animDrawn, animWorstNs / 1e6))
                }
                if (sparkling && !fireworks) {
                    sparkling = false
                    maybeAmbient()
                    painter.show(cur, cur, 0f); swap()
                    if (fwDrawn > 0) Log.i(TAG, "fireworks %.0f s: %d frames, %.1f ms each, worst %.1f".format(
                        (SystemClock.uptimeMillis() - fwFrom) / 1000.0, fwDrawn, fwWorkNs / 1e6 / fwDrawn, fwWorstNs / 1e6))
                }
                if (animating || sparkling) choreo.postFrameCallback(tick) else { parked = true; lastNs = 0L }
            }
            else -> {
                if (dirty) { matchStill(); painter.show(cur, cur, 0f); swap(); dirty = false }
                parked = true
                lastNs = 0L
            }
        }
    }

    /** The tile you've settled on: its land, and the colour to wash it with. */
    fun focus(id: String?, hue: FloatArray?) = h.post {
        // The same tile with a new colour, its picture's own just worked out: no reason to hurry.
        val same = id == focusId
        focusId = id
        flow.target(id?.let(Sky::shape), hue, landFor(id))
        if (!ready) { firstLight(); return@post }
        if (!idle) scene(if (same) SLOW_FADE_S else FADE_S)
    }

    /** The place chosen in the settings, for [id]: its own when shuffled, today's when it changes daily. */
    private fun landFor(id: String?): Places.Land? = Places.current(id, sun)

    /** The place setting changed: the land under the sky again. */
    fun place() = h.post { if (!idle) scene(FADE_S) }

    fun weather(kind: Int, sun: Sun, cloud: Int = 0, windFrom: Int? = null, storm: Boolean = false, windKmh: Float = 10f) = h.post {
        this.kind = kind; this.sun = sun; this.cloud = cloud; this.windFrom = windFrom; this.storm = storm
        this.windKmh = windKmh
        if (ready) { apply(); painter.weather(shownKind()); if (!idle) scene(SLOW_FADE_S) }
    }

    /** What the weather means for the painter, with anything asked for over ADB in place of the real thing. */
    private fun apply() {
        painter.storm = storm || stormTest
        painter.speed = Wind.factor(windTest ?: windKmh)
    }

    private fun shownKind() = if (stormTest) Sky.RAIN else if (Heavens.cloudTest == 0) Sky.CLEAR else kind

    /**
     * From adb only (the sender needs the DUMP permission), to look at the sky as it isn't right now:
     *   am broadcast -a com.firesky.launcher.SKY --ei cloud 0 --el ahead 90   a clear sky, 90 minutes on
     *   am broadcast -a com.firesky.launcher.SKY --ei cloud 0 --ez pass true  a minute before the station's next pass
     *   am broadcast -a com.firesky.launcher.SKY --ef kp 8                    a geomagnetic storm
     *   am broadcast -a com.firesky.launcher.SKY --ez storm true              a thunderstorm
     *   am broadcast -a com.firesky.launcher.SKY --ef wind 45                 a 45 km/h wind
     *   am broadcast -a com.firesky.launcher.SKY --ez balloon true            a balloon on the idle screen
     *   am broadcast -a com.firesky.launcher.SKY --ez fireworks true          New Year's fireworks
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
            stormTest = i.getBooleanExtra("storm", false)
            windTest = i.getFloatExtra("wind", -1f).takeIf { it >= 0 }
            Balloon.test = i.getBooleanExtra("balloon", false)
            Fireworks.test = i.getBooleanExtra("fireworks", false)
            if (ready) { apply(); painter.weather(shownKind()); animating = false; if (!idle) scene(SLOW_FADE_S) }
        }
    }

    /**
     * From adb only, to measure the home screen's motion:
     *   am broadcast -a com.firesky.launcher.MOTION --ei fps 12 --ez half false --ez finish true --el after 0
     * frames a second, drawn at half size, waiting for each frame to finish so its time is all of it,
     * and how long after a key it starts again. With no extras, back to normal.
     */
    private val motionTest = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            ambientFps = i.getIntExtra("fps", AMBIENT_FPS).coerceIn(1, 60)
            ambientHalf = i.getBooleanExtra("half", true)
            ambientFinish = i.getBooleanExtra("finish", false)
            ambientAfter = i.getLongExtra("after", AMBIENT_AFTER_MS).coerceAtLeast(0)
            Log.i(TAG, "motion test: $ambientFps fps, half $ambientHalf, finish $ambientFinish, after $ambientAfter ms")
            stopAmbient(); maybeAmbient()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(test, IntentFilter("com.firesky.launcher.SKY"), android.Manifest.permission.DUMP, h)
        context.registerReceiver(motionTest, IntentFilter("com.firesky.launcher.MOTION"), android.Manifest.permission.DUMP, h)
    }

    /** Only the idle screen has the chip to itself, so only it moves. */
    fun idle(on: Boolean) = h.post {
        if (!ready || on == idle) return@post
        idle = on
        if (on) {
            stopAmbient()
            idleS = 0f
            balloonOut = Balloon.morning(Calendar.getInstance(), minuteNow(), sun, shownKind(), cloud, windTest ?: windKmh)
            flow.startDrift()
            wakeLoop()
        } else {
            painter.balloon[3] = 0f
            // Leave from the frame on screen, so nothing jumps.
            painter.still(flow, cur, clock(), lateNow())
            shownAmbient = false
            lastPoke = SystemClock.uptimeMillis()
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
        stopAmbient(); shownAmbient = false
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

    fun pause() = h.post { active = false; animating = false; sparkling = false; stopAmbient() }

    fun resume() {
        waiting = true
        h.post { active = true; dirty = true; animating = false; lastPoke = SystemClock.uptimeMillis(); wakeLoop(); maybeAmbient() }
    }

    /** The backdrop as it is now, on its own. */
    fun frame(): Bitmap? = runCatching { getBitmap(960, 540) }.getOrNull()

    /** A tiny copy of the scene. Stretched back up, it's the frosted background behind the panel. */
    fun glass(): Bitmap? = runCatching { getBitmap(160, 90) }.getOrNull()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(test) }
        runCatching { context.unregisterReceiver(motionTest) }
        h.post { thread.quitSafely() }
    }

    private companion object {
        const val FADE_S = .45f
        /** The very first picture: how long it waits for the selected tile, and how slowly it fades in. */
        const val FIRST_WAIT_MS = 700L
        const val FIRST_FADE_S = 1.2f
        /**
         * The home screen's motion: how long after a key it starts, and how smoothly. Measured on a Fire TV
         * (Mali-G52): a full-size frame of water takes the chip about 108 ms, a half-size one 31 ms. Moving
         * while you browse doubled the slow frames of the tiles, so it waits for a pause.
         */
        const val AMBIENT_AFTER_MS = 3_000L
        const val AMBIENT_FPS = 12
        const val SLOW_FADE_S = 1.5f
        const val DAWN_FADE_S = 1.6f
        const val REFRESH_MS = 5 * 60_000L
    }
}
