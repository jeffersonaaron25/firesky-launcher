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
import java.util.Date
import java.util.Locale
import java.util.Calendar
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
    private var lastGo = 0L
    @Volatile private var pressed = 0L
    @Volatile private var task: ActivityManager.AppTask? = null

    private var log: LogTail? = null
    private var watching: LogTail? = null

    /** The glance over other apps, and the ≡ press that brings it up. */
    private var glance: Glance? = null
    private val hold = MenuHold()
    private var wasPlaying = false
    private var lastNudge = 0L
    private var lastPass = 0L
    private var lastRain = 0L
    @Volatile private var fetching = false
    private var fetchedAt = 0L

    /** The cover is a picture of the home screen, put up for a Home press, so a press now is meant for what it shows. */
    private var typing = false
    private val typed = TypeAhead()

    /**
     * The earliest sign of a Home press that Fire OS lets an app see: its own log line, about a
     * tenth of a second after the press and well before Amazon's home draws. The key itself and the
     * close-dialogs broadcast are both withheld from apps here. Needs the READ_LOGS grant.
     *
     * Waking shows up here too, a second before the screen lights. The screen-on broadcast only
     * arrives after that, by which time Amazon's home has been on screen for most of a second.
     */
    private fun watchLog() {
        log = LogTail("home-log", listOf("logcat", "-T", "1", "-v", "brief", "-b", "system", "-s", "ActivityManager:I", "PowerManagerService:I")) { l ->
            // Straight from this thread: a moment spent queueing can be what lets Amazon's home in.
            if ("START u0" in l && "category.HOME" in l && AMAZON in l) {
                // The Home button is the system's own start (uid 1000). Anything else is Amazon's home
                // opened on purpose, such as its Settings, which is a page of its home screen.
                if (" from uid 1000" in l) visiting = false else if (visiting) return@LogTail
                go("home button")
            } else if ("Waking up from sleep" in l) wake()
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
            glance?.hide()
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
        Taste.init(this)
        me = this
        Prefs.init(this)
        glance = Glance(this)
        Playing.changed = { playing(it) }
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 60_000)
        diary = getExternalFilesDir(null)?.let { File(it, "home.log") }
        task = findTask()
        // Both are slow the first time, and a Home press has no time to spare for that.
        getSystemService(PowerManager::class.java)?.isInteractive
        Guard.warm(this)
        watchLog()
        watching = Watching.watch(this)
        // The list of apps and the star catalogue take a moment to make, so they're made now, in the
        // background, and are ready however the home screen is next opened.
        thread(name = "warm", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            Backdrop.restore(this)
            prepare()
            runCatching { Apps.get(this); Heavens.catalog(this) }.onFailure { Log.w(TAG, "couldn't get ready ahead of time", it) }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        me = null
        log?.stop()
        watching?.stop()
        lift()
        Playing.changed = null
        main.removeCallbacks(ticker)
        main.removeCallbacks(pauseStrip)
        glance?.end()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_HOME) {
            Log.i(TAG, "home key seen ${if (e.action == KeyEvent.ACTION_DOWN) "down" else "up"}")
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) go("home key")
            return true
        }
        // Over another app, holding ≡ brings up the glance. While it's up, the remote is the glance's.
        if (e.keyCode == KeyEvent.KEYCODE_MENU) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) main.postDelayed(held, MenuHold.HOLD_MS) else if (e.action == KeyEvent.ACTION_UP) main.removeCallbacks(held)
            when (hold.on(e.action, e.repeatCount, overApp() && Prefs.glance)) {
                MenuHold.Act.OPEN -> { openGlance(); return true }
                MenuHold.Act.EAT -> return true
                MenuHold.Act.PASS -> {}
            }
        }
        if (glance?.full == true) return glance?.key(e) == true
        // Fire TV's Settings is a page of Amazon's home screen, so Back there would open the rest of
        // it. Having come from here, Back comes back here. Its own screens (Network and so on) are
        // separate windows, and Back in them returns to Settings as usual.
        if (e.keyCode == KeyEvent.KEYCODE_BACK && inSettings && amazonOnTop && !front) {
            if (e.action == KeyEvent.ACTION_UP) { visiting = false; go("back from settings") }
            return true
        }
        // While the screen is covered, keys must not reach Amazon's home underneath.
        if (mask == null) return false
        // Android takes the event back once this returns, so only what it was is kept.
        if (typing) typed.keep(e.keyCode, e.action, e.repeatCount)
        return true
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString()
        // What's in front: Amazon's home, or another app's window. Its own pop-ups change neither.
        if (pkg != AMAZON) amazonOnTop = false
        if (pkg != AMAZON || e.className?.toString() != AMAZON_HOME) return
        amazonOnTop = true
        // Amazon's home often reports itself late, after the home screen is already back. Acting on
        // that would cover the real home screen with nothing to lift it.
        if (!front && !visiting) go("amazon home")
    }

    /** Called from the log thread as well as the main one. */
    private fun go(why: String) {
        val now = SystemClock.uptimeMillis()
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
            glance?.hide()
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

    private var slowLift = false
    private var sceneKey = ""
    private var scene: Bitmap? = null

    /** Painting takes a moment, so a stand-in for the hour is kept ready, in case nothing better is. */
    private fun prepare() {
        if (Backdrop.last != null) return
        val c = Calendar.getInstance()
        val k = Landscape.keyFor(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        if (k == sceneKey) return
        sceneKey = k
        thread(name = "scene") { scene = runCatching { Landscape.paint(k, .75f) }.getOrNull() }
    }

    /** [dark] for waking: the rise underneath starts from black. */
    private fun cover(dark: Boolean = false) {
        if (mask != null) return
        // Just after FireSky starts there's no picture of the home screen yet, so its last backdrop, kept on
        // disk, stands in: the same place in the same colours. Only before there's ever been one, a painting.
        val pic = if (dark) null else Backdrop.home ?: Backdrop.last ?: scene
        typed.clear()
        typing = pic != null && pic === Backdrop.home
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
        // A stand-in without the tiles on it gives way slowly, so the home screen fades in over it.
        slowLift = pic != null && pic !== Backdrop.home
        runCatching { getSystemService(WindowManager::class.java).addView(v, lp); mask = v }
            .onFailure { Log.w(TAG, "couldn't cover the screen", it) }
    }

    private fun lift() {
        main.removeCallbacks(giveUp)
        typing = false; typed.clear()
        val v = mask ?: return
        mask = null
        v.animate().alpha(0f).setDuration(if (slowLift) 500 else 180).withEndAction {
            runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        }.start()
    }

    override fun onInterrupt() {}

    private fun interactive() = getSystemService(PowerManager::class.java)?.isInteractive == true

    /** Another app is what's on the TV: not FireSky, not Amazon's own screens, not covered, and the screen is on. */
    private fun overApp() = !front && mask == null && !amazonOnTop && interactive()

    private val held = Runnable { if (hold.timeUp() && overApp()) openGlance() }

    private fun openGlance() {
        note("glance over ${Playing.now?.pkg ?: "an app"}")
        glance?.full()
    }

    /** Pausing shows the time and when it would end, once the pause has lasted a moment. */
    private fun playing(n: Playing.Now?) {
        val was = wasPlaying
        wasPlaying = n?.playing == true
        // Players report a pause more than once, and only the first one starts the wait.
        if (n != null && !n.playing) { if (was) { main.removeCallbacks(pauseStrip); main.postDelayed(pauseStrip, PAUSE_WAIT_MS) } }
        else { main.removeCallbacks(pauseStrip); glance?.unpause() }
    }

    private val pauseStrip = Runnable {
        val n = Playing.now ?: return@Runnable
        if (n.playing || !overApp() || !Prefs.nudges) return@Runnable
        glance?.strip(Playing.ends(Playing.known(this, n)), StripWhy.PAUSE, Glance.PAUSE_MS)
    }

    /**
     * Once a minute: keeps the forecast and the station's passes fresh while FireSky isn't open to
     * do it, and says if the station is about to pass or rain is about to start.
     */
    private val ticker = object : Runnable {
        override fun run() {
            runCatching { tick() }.onFailure { Log.w(TAG, "tick", it) }
            main.postDelayed(this, 60_000)
        }
    }

    private fun tick() {
        if (!interactive()) return
        val now = System.currentTimeMillis()
        if (!front && !fetching && now - fetchedAt > 10 * 60_000 && Weather.stale(this)) {
            fetching = true; fetchedAt = now
            thread(name = "fresh", isDaemon = true) {
                runCatching { Weather.fetch(this); Heavens.refresh(this) }
                fetching = false
            }
        }
        val g = glance ?: return
        if (!Prefs.nudges || !overApp() || g.showing || now - lastNudge < Nudges.GAP_MS) return
        val wx = Weather.cached(this)
        val n = Nudges.station(Heavens.passes, now, wx?.cloud ?: 0, lastPass) ?: Nudges.rain(wx, now, lastRain) ?: return
        lastNudge = now
        if (n.pass != 0L) lastPass = n.pass
        if (n.rain) lastRain = now
        note("nudge: ${n.text}")
        g.strip(n.text, StripWhy.NUDGE, Glance.NUDGE_MS)
    }

    companion object {
        private const val TAG = "FireSkyHome"
        private const val AMAZON = "com.amazon.tv.launcher"
        private const val AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
        private var me: HomeKey? = null

        /**
         * You've gone to one of Amazon's own screens on purpose (its Settings, or its home), so it can
         * stay until you press Home or come back here some other way.
         */
        @Volatile var visiting = false
            set(v) { field = v; if (!v) inSettings = false }

        /** The visit is to Fire TV's Settings, where Back is ours. */
        @Volatile private var inSettings = false

        /** Amazon's home screen, which Settings is a page of, is the window in front. */
        @Volatile private var amazonOnTop = false

        /** Whether the home screen is what's on the TV right now. Coming home takes the glance down. */
        @Volatile var front = false
            set(v) { field = v; if (v) me?.let { s -> s.main.post { s.glance?.hide() } } }

        /** A pause has lasted this long before the time is shown over it. */
        private const val PAUSE_WAIT_MS = 1200L

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

        /** The home screen is up, so the cover can go: [atOnce] for right now, from the main thread. */
        fun arrived(c: Context, atOnce: Boolean = false) {
            Guard.arrived(c)
            me?.let { s ->
                if (s.mask != null) note("home screen up ${s.since()}")
                s.task = s.findTask()
                if (atOnce) s.lift() else s.main.post { s.lift() }
            }
        }

        /** Something has been pressed on the picture of the home screen. Main thread only, like the one below. */
        val typedAhead get() = me?.typed?.any == true

        /** Those presses, handed over once, for the home screen to act on now that it's there. */
        fun takeTyped(): List<Pair<Int, Int>> = me?.let { s -> s.typing = false; s.typed.take() } ?: emptyList()

        fun openAmazonHome(c: Context) =
            visit(c, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(AMAZON))

        /** Fire TV's full settings, a page of Amazon's home screen, reached through its own shortcut. */
        fun openSettings(c: Context) =
            visit(c, Intent().setClassName(AMAZON, "$AMAZON.ui.SettingsActivity"), Intent(android.provider.Settings.ACTION_SETTINGS))
                .also { inSettings = it }

        private fun visit(c: Context, vararg tries: Intent): Boolean {
            visiting = true
            val ok = tries.any { i -> runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
            if (!ok) visiting = false
            return ok
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
        private set
    private const val FILE = "backdrop.jpg"

    /** The backdrop as it was on leaving, kept on disk as well, for the first Home press after FireSky starts again. */
    fun keep(c: Context, b: Bitmap) {
        last = b
        val dir = c.applicationContext.filesDir
        thread(name = "keep", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            runCatching {
                val tmp = java.io.File(dir, "$FILE.tmp")
                tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                tmp.renameTo(java.io.File(dir, FILE))
            }.onFailure { Log.w("FireSkyHome", "couldn't keep the backdrop", it) }
        }
    }

    /** Just after starting: the backdrop kept from last time, if there's no newer one. */
    fun restore(c: Context) {
        if (last != null) return
        val f = java.io.File(c.filesDir, FILE)
        if (f.exists()) last = runCatching { android.graphics.BitmapFactory.decodeFile(f.path) }.getOrNull()
    }
    /** Which tiles [home] shows and which is selected (HomeState.shape), so presses made on it are only played on the same layout. */
    @Volatile var shape: String? = null
}

/**
 * Presses made on the picture of the home screen that covers the moment of coming home, kept as key
 * and action, in order, until the real home screen is there to take them. Only the keys that mean
 * something on the home screen are kept.
 */
class TypeAhead {
    private val events = ArrayList<Pair<Int, Int>>()
    private val down = HashSet<Int>()

    val any get() = events.isNotEmpty()

    fun clear() { events.clear(); down.clear() }

    fun keep(key: Int, action: Int, repeat: Int) {
        if (key !in ARROWS && key !in PRESSES) return
        if (action == KeyEvent.ACTION_DOWN) {
            // A key held down repeats, and counts once. A release only counts if its press was seen.
            if (repeat == 0 && events.size < MAX) { events += key to KeyEvent.ACTION_DOWN; down += key }
        } else if (action == KeyEvent.ACTION_UP && down.remove(key)) events += key to KeyEvent.ACTION_UP
    }

    /**
     * Everything kept, handed over once. OK or Menu still held down is left out: its release would
     * arrive on its own, or not at all, and a press without one reads as a long hold.
     */
    fun take(): List<Pair<Int, Int>> {
        val out = ArrayList(events)
        for (k in down) if (k in PRESSES) out.removeAt(out.indexOfLast { it.first == k })
        clear()
        return out
    }

    private companion object {
        const val MAX = 16
        val ARROWS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        val PRESSES = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU)
    }
}

/**
 * Follows the TV's log a line at a time for as long as it's wanted. If logcat ever stops, or a line
 * trips something up, it's started again: without the log, Home falls back to slower signs of a
 * press and the Continue cards stop updating, and nothing would say so.
 */
class LogTail(private val name: String, private val command: List<String>, private val line: (String) -> Unit) {
    @Volatile private var on = true
    @Volatile private var running: Process? = null

    init {
        thread(name = name, isDaemon = true) {
            var wait = 1000L
            while (on) {
                val began = SystemClock.uptimeMillis()
                runCatching {
                    val p = ProcessBuilder(command).redirectErrorStream(true).start()
                    running = p
                    if (!on) p.destroy()
                    p.inputStream.bufferedReader().forEachLine(line)
                }.onFailure { Log.w("FireSkyLog", "$name stopped", it) }
                running?.destroy()
                if (!on) break
                // One that keeps stopping straight away is tried less and less often.
                wait = if (SystemClock.uptimeMillis() - began > 60_000) 1000L else (wait * 2).coerceAtMost(60_000L)
                HomeKey.note("$name: the log reader stopped, starting it again in ${wait / 1000} s")
                runCatching { Thread.sleep(wait) }
            }
        }
    }

    fun stop() { on = false; running?.destroy() }
}
