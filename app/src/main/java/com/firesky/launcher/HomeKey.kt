package com.firesky.launcher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Keeps Amazon's home off the screen. Fire OS can hold back an app opened just after Home is
 * pressed, so the moment Home is seen this asks for FireSky and covers the screen with a picture of
 * the home screen. The real one takes over underneath when it arrives.
 */
class HomeKey : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var mask: View? = null
    private var sceneKey = ""
    private var scene: Bitmap? = null
    private var lastGo = 0L
    @Volatile private var pressed = 0L
    @Volatile private var task: ActivityManager.AppTask? = null

    private var log: Process? = null
    private var watching: Process? = null

    /**
     * The earliest sign of a Home press that Fire OS lets an app see: its own log line, about a
     * tenth of a second after the press and well before Amazon's home draws. The key itself and the
     * close-dialogs broadcast are both withheld from apps here. Needs the READ_LOGS grant.
     *
     * Waking shows up here too, a second before the screen lights. The screen-on broadcast only
     * arrives after that, by which time Amazon's home has been on screen for most of a second.
     */
    private fun watchLog() {
        thread(name = "home-log", isDaemon = true) {
            runCatching {
                val p = ProcessBuilder("logcat", "-T", "1", "-v", "brief", "-b", "system", "-s", "ActivityManager:I", "PowerManagerService:I")
                    .redirectErrorStream(true).start()
                log = p
                p.inputStream.bufferedReader().forEachLine { l ->
                    // Straight from this thread: a moment spent queueing can be what lets Amazon's home in.
                    if ("START u0" in l && "category.HOME" in l && AMAZON in l) go("home button")
                    else if ("Waking up from sleep" in l) wake()
                }
            }.onFailure { Log.w(TAG, "can't watch the log", it) }
        }
    }

    /**
     * The TV is waking up. Fire OS puts Amazon's home up before the screen lights, so the home
     * screen comes back now, under a black cover that the wake-up rise starts from.
     */
    private fun wake() {
        if (!Wake.claim()) return
        pressed = SystemClock.uptimeMillis()
        note("waking up")
        Wake.ask()
        main.post {
            cover(dark = true)
            main.removeCallbacks(giveUp)
            main.postDelayed(giveUp, 7000)
        }
        val moved = toFront(task) || toFront(findTask().also { task = it })
        note("task to front: $moved ${since()}")
        if (!moved) {
            Wake.cancel()
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("wake", true))
            }
        }
    }

    override fun onServiceConnected() {
        me = this
        diary = getExternalFilesDir(null)?.let { File(it, "home.log") }
        task = findTask()
        // Both are slow the first time, and a Home press has no time to spare for that.
        getSystemService(PowerManager::class.java)?.isInteractive
        Guard.warm(this)
        watchLog()
        watching = Watching.watch(this)
        prepare()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        me = null
        log?.destroy()
        watching?.destroy()
        lift()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_HOME) {
            Log.i(TAG, "home key seen ${if (e.action == KeyEvent.ACTION_DOWN) "down" else "up"}")
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) go("home key")
            return true
        }
        // While the screen is covered, keys must not reach Amazon's home underneath.
        return mask != null
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (e.packageName?.toString() != AMAZON || e.className?.toString() != AMAZON_HOME) return
        // Amazon's home often reports itself late, after the home screen is already back. Acting on
        // that would cover the real home screen with nothing to lift it.
        if (!front) go("amazon home")
    }

    /** Called from the log thread as well as the main one. */
    private fun go(why: String) {
        val now = SystemClock.uptimeMillis()
        if (now < amazonUntil) return
        // Fire OS shuffles apps while the TV sleeps. Waking is handled by wake().
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return
        synchronized(this) {
            if (now - lastGo < 800) return
            lastGo = now
        }
        if (!Guard.mayTry(this)) {
            note("FireSky keeps failing to open, so leaving Amazon's home on screen")
            return
        }
        pressed = now
        note("going home: $why")
        main.post {
            cover()
            main.removeCallbacks(giveUp)
            main.postDelayed(giveUp, 7000)
            prepare()
        }
        bringBack()
    }

    private fun findTask() = runCatching { getSystemService(ActivityManager::class.java).appTasks.firstOrNull() }.getOrNull()

    private fun toFront(t: ActivityManager.AppTask?) = t != null && runCatching { t.moveToFront() }.isSuccess

    /**
     * Fire OS holds back anything opened for 5 seconds after Home, but bringing our own task back to
     * the front isn't subject to that. Asking before the app you're leaving has finished pausing
     * means Amazon's home never opens at all, which saves about a second, so this comes first.
     * Starting fresh is only for when there's no task.
     */
    private fun bringBack() {
        val moved = toFront(task) || toFront(findTask().also { task = it })
        note("task to front: $moved ${since()}")
        if (!moved) runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    private fun since() = "+${SystemClock.uptimeMillis() - pressed}ms"

    private val giveUp = Runnable { note("gave up waiting ${since()}"); lift() }

    /** [dark] for waking: the rise underneath starts from black. */
    private fun cover(dark: Boolean = false) {
        if (mask != null) return
        val pic = if (dark) null else Backdrop.home ?: Backdrop.last ?: scene
        note("cover: ${when (pic) { null -> "black"; Backdrop.home -> "home screen"; Backdrop.last -> "backdrop only"; else -> "painting" }}")
        val v = ImageView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_CROP
            pic?.let(::setImageBitmap)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { getSystemService(WindowManager::class.java).addView(v, lp); mask = v }
            .onFailure { Log.w(TAG, "couldn't cover the screen", it) }
    }

    private fun lift() {
        main.removeCallbacks(giveUp)
        val v = mask ?: return
        mask = null
        v.animate().alpha(0f).setDuration(180).withEndAction {
            runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        }.start()
    }

    /** Painting takes a moment, so the current scene is kept ready before it's needed. */
    private fun prepare() {
        val c = Calendar.getInstance()
        val k = Landscape.keyFor(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        if (k == sceneKey) return
        sceneKey = k
        thread(name = "scene") { scene = runCatching { Landscape.paint(k, .75f) }.getOrNull() }
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "FireSkyHome"
        private const val AMAZON = "com.amazon.tv.launcher"
        private const val AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
        private var me: HomeKey? = null

        /** Until this time Amazon's home is allowed to stay, because you asked for it. */
        @Volatile private var amazonUntil = 0L

        /** Whether the home screen is what's on the TV right now. */
        @Volatile var front = false

        /** The screen is covered, so whatever happens underneath won't be seen. */
        val covering get() = me?.mask != null

        /** A step of coming home, timed from the press. Only while the cover is up, so only Home presses. */
        fun mark(what: String) { me?.let { s -> if (s.mask != null) note("$what ${s.since()}") } }

        private val writer = Executors.newSingleThreadExecutor()
        private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        @Volatile private var diary: File? = null

        /**
         * Logs it, and keeps it in a small file the Mac can read later with
         * `adb shell cat /sdcard/Android/data/com.firesky.launcher/files/home.log`, because the TV's own
         * log forgets within minutes.
         */
        fun note(msg: String) {
            Log.i(TAG, msg)
            val f = diary ?: return
            val at = System.currentTimeMillis()
            writer.execute {
                runCatching {
                    if (f.length() > 200_000) f.renameTo(File(f.path + ".old"))
                    f.appendText("${stamp.format(Date(at))} $msg\n")
                }
            }
        }

        /** The wake-up rise has begun, from black, so the black cover can go and the rise be seen. */
        fun risen() {
            me?.let { s ->
                if (s.mask != null) note("rising ${s.since()}")
                s.main.post { s.lift() }
            }
        }

        /** The home screen is up, so the cover can go. */
        fun arrived(c: Context) {
            Guard.arrived(c)
            me?.let { s ->
                if (s.mask != null) note("home screen up ${s.since()}")
                s.task = s.findTask()
                s.main.post { s.lift() }
            }
        }

        fun openAmazonHome(c: Context): Boolean {
            amazonUntil = SystemClock.uptimeMillis() + 3000
            return runCatching {
                c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(AMAZON).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }
}

/**
 * If FireSky ever crashed on opening, Amazon's home would appear, and sending you back would crash
 * it again, forever. After three tries that never arrive, it steps aside for a while. Kept on disk
 * because a crash takes this process with it.
 */
object Guard {
    private const val MAX_TRIES = 3
    private const val PAUSE_MS = 5 * 60_000L

    private fun prefs(c: Context) = c.getSharedPreferences("guard", Context.MODE_PRIVATE)

    /** The first read waits on the disk, so it's done before anyone presses Home. */
    fun warm(c: Context) { prefs(c).getInt("tries", 0) }

    fun mayTry(c: Context): Boolean {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val tries = p.getInt("tries", 0)
        if (tries >= MAX_TRIES && now - p.getLong("at", 0) < PAUSE_MS) return false
        p.edit().putInt("tries", if (tries >= MAX_TRIES) 1 else tries + 1).putLong("at", now).apply()
        return true
    }

    fun arrived(c: Context) { prefs(c).edit().putInt("tries", 0).apply() }
}

/** One wake-up, whichever part of the app notices it first. */
object Wake {
    private const val WINDOW_MS = 5000L
    private var at = -WINDOW_MS

    /** When the home screen was asked back for a wake, so it plays the rise when it arrives. */
    @Volatile private var askedAt = 0L

    fun ask() { askedAt = SystemClock.elapsedRealtime() }
    fun cancel() { askedAt = 0L }

    /** True only for the first to report this wake. The other report comes within a second or two. */
    @Synchronized
    fun claim(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - at < WINDOW_MS) return false
        at = now
        return true
    }

    /** Only soon after asking: a home screen that returns much later is just coming back. */
    fun take(): Boolean = (askedAt != 0L && SystemClock.elapsedRealtime() - askedAt < 10_000).also { askedAt = 0L }
}

/** What covers the screen when coming home: the whole home screen, or failing that its backdrop. */
object Backdrop {
    @Volatile var home: Bitmap? = null
    @Volatile var last: Bitmap? = null
}
