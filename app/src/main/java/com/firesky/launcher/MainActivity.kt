package com.firesky.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), Actions {
    private val s = HomeState()
    /** You left through a tile, so coming back should land on that tile. */
    private var opened = false
    private var away = false
    private var titlesJob: Job? = null
    private var relearnJob: Job? = null
    private var watchArtJob: Job? = null
    private var appsJob: Job? = null
    private var kodiJob: Job? = null
    private var phonePage: Phone? = null
    /** Until then, a position an app saves is shown at once: the app you just left may only now be saying where you stopped. */
    private var resumeUntil = 0L
    /** How the home screen was when you left it: the tile selected, and nothing open over it. */
    private var leftAt: String? = null
    private var leftPlain = false
    /** This return went to the first card, not back to the tile you left. */
    private var homed = false
    private val main = Handler(Looper.getMainLooper())
    private var landing = false
    private val landAnyway = Runnable { land() }
    /** The backdrop has drawn since coming back. */
    private var drawn = false
    /** The cards are up to date since coming back. */
    private var fresh = false
    /** The screen has had a frame or two to show what coming back changed. */
    private var settled = false
    /**
     * Android has finished showing this window since it was last out of view. Until then the window
     * may not be on screen at all, however far along it is, and the cover would fade to whatever is.
     */
    private var entered = true
    private var returns = 0
    /** Out of view since the backdrop was last drawn, which loses its picture until a new frame arrives. */
    private var stopped = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Prefs.system24 = android.text.format.DateFormat.is24HourFormat(this)
        // For trying an update before it's published: point the check at another release feed.
        // adb only, like the sky test: the sender needs DUMP.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                getSharedPreferences("update", MODE_PRIVATE).edit().putString("feed", i.getStringExtra("url")).apply()
                update()
            }
        }, android.content.IntentFilter("com.firesky.launcher.UPDATE_FEED"), android.Manifest.permission.DUMP, null)
        // For trying picks out: a pretend history, kept nowhere, and a pretend sky. adb only, as above.
        // "as" lists titles: m:Arrival seen to the end, m2: twice, m0: left early, t8:Severance 8 episodes.
        // Sent without "as", the picks go back to your own.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                s.skyTest = i.getStringExtra("sky")
                val spec = i.getStringExtra("as")
                if (spec == null) { Picks.drop(); loadTitles(); return }
                val titles = s.titles; val have = s.apps.mapTo(HashSet()) { it.pkg }; val use = s.useMinutes; val hidden = s.hiddenCards
                lifecycleScope.launch(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val seen = spec.split(';').mapNotNull { p ->
                        val (k, title) = p.trim().split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                        val n = k.drop(1).toIntOrNull() ?: 1
                        val at = now - (if (n == 0) 30 else 3) * 86_400_000L
                        when {
                            k.startsWith("t") -> Taste.Seen(title, true, "", 1f, 1f, 0, (1..n).mapTo(HashSet()) { "1x$it" }, at, at)
                            n == 0 -> Taste.Seen(title, false, "", .1f, .1f, 0, HashSet(), at, at)
                            else -> Taste.Seen(title, false, "", 1f, 1f, n, HashSet(), at, at)
                        }
                    }
                    runCatching { Picks.refresh(this@MainActivity, titles, have, use, hidden, seen) }
                        .onFailure { android.util.Log.w("FireSkyPicks", "couldn't try picks", it) }
                    withContext(Dispatchers.Main) { loadTitles() }
                }
            }
        }, android.content.IntentFilter("com.firesky.launcher.PICKS"), android.Manifest.permission.DUMP, null)
        // For trying out how passed-over cards step aside: "shift" moves the date on that many days,
        // counted apart from the real counts; "end" goes back to them. adb only, as above.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                Passed.trial(if (i.getBooleanExtra("end", false)) null else i.getIntExtra("shift", 0).toLong())
                loadTitles()
            }
        }, android.content.IntentFilter("com.firesky.launcher.PASSED"), android.Manifest.permission.DUMP, null)
        // The idle screen at once, for looking at it without waiting. adb only, as above.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) { s.goIdle() }
        }, android.content.IntentFilter("com.firesky.launcher.IDLE"), android.Manifest.permission.DUMP, null)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        // Back on the home screen goes nowhere, except out of All apps.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { s.allApps = false }
        })
        Sfx.init(this)
        // Our own sounds replace the system's focus sounds in this window.
        window.decorView.isSoundEffectsEnabled = false
        HomeKey.mark("starting from scratch")
        Taste.init(this)
        Passed.init(this)
        s.load(this)
        s.saidCards = Taste.said(this)
        music(Music.now)
        Music.changed = ::music
        Brief.wake = { woke() }
        s.relearn = ::relearnSoon
        // Usually made already, so the dock is there in the very first frame.
        Apps.ready()?.let { s.apps = it }
        s.drawn = { drawn = true; HomeKey.mark("backdrop drawn"); if (fresh && settled && entered) land() }
        // The picture of the home screen for coming back to it is taken as the idle screen comes up: an app
        // opened from there leaves with the idle screen, or the home screen half back, on show.
        s.beforeIdle = { snap() }
        s.risen = { HomeKey.risen() }
        s.wx = Weather.cached(this)
        setContent { Home(s, this) }
        // Netflix tells Fire TV what you're watching when it likes, often just after you leave it.
        Watching.changed = { runOnUiThread { lifecycleScope.launch { loadWatching() } } }
        Resume.changed = { runOnUiThread { if (SystemClock.uptimeMillis() < resumeUntil) s.resume = Resume.get(this) } }
        // An app installed while the home screen is showing appears on it. One installed while
        // you're elsewhere is picked up on the way back.
        Apps.changed = { runOnUiThread { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) reloadApps() } }

        lifecycleScope.launch {
            try {
                loadApps()
                refresh()
                ready()
                loadTitles()
            } finally { s.loaded() }
        }
        lifecycleScope.launch { delay(HomeState.LOADING_MAX_MS); s.loaded(force = true) }
        // While the home screen is showing, keep the weather fresh and notice the PS5 turning on.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    // Where the TV is decides its weather and its sky; looked up again weekly.
                    if (Place.stale(this@MainActivity)) withContext(Dispatchers.IO) { Place.refresh(this@MainActivity) }
                    withContext(Dispatchers.IO) { runCatching { Heavens.refresh(this@MainActivity) } }
                    if (Weather.stale(this@MainActivity)) {
                        withContext(Dispatchers.IO) { Weather.fetch(this@MainActivity) }?.let { s.wx = it }
                    }
                    delay(15 * 60_000L)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    s.hdmi = withContext(Dispatchers.IO) { Hdmi.devices(this@MainActivity) }
                    delay(20_000)
                }
            }
        }
    }

    /**
     * Back from an app. Android is still busy switching for a second or so, so the cards are brought
     * up to date now, while the cover is up, and don't shift under you once you can move.
     */
    override fun onRestart() {
        super.onRestart()
        HomeKey.mark("on screen")
        fresh = false
        resumeUntil = SystemClock.uptimeMillis() + RESUME_GRACE_MS
        lifecycleScope.launch {
            loadApps()
            refresh()
            ready()
        }
    }

    private fun ready() {
        fresh = true
        HomeKey.mark("cards ready")
        if (drawn && settled && entered) land()
    }

    /**
     * Back in view. That can be half a second before being resumed, while the app you left finishes
     * pausing, so the backdrop is asked for now and is already drawn when that moment comes.
     */
    override fun onStart() {
        super.onStart()
        if (stopped) showBackdrop()
    }

    private fun showBackdrop() {
        drawn = !s.liveOk
        s.live?.resume()
    }

    override fun onResume() {
        super.onResume()
        HomeKey.front = true
        HomeKey.visiting = false
        HomeKey.mark("resumed")
        s.homeReady = android.provider.Settings.Secure.getString(contentResolver, "enabled_accessibility_services")?.contains("$packageName/") == true
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Updater.check(this@MainActivity) }
            if (r != null) s.update = r
        }
        // Brought back by the TV waking up: the rise plays, from the first card.
        val woke = Wake.take()
        if (woke) woke()
        s.back()
        when {
            // The rise starts from a frame drawn after this moment, as it always has.
            woke -> showBackdrop()
            // Only paused, never out of view: the backdrop's picture is still on screen.
            !stopped -> { drawn = true; s.live?.resume() }
            // Otherwise it was asked for when this came back into view, and may be there already.
        }
        stopped = false
        homed = false
        if (away) {
            away = false
            // Home brings this task back without a new intent, so this is where that's noticed:
            // back from a tile stays on the tile, and Home on the home screen goes to the first card.
            homed = !opened
            if (!opened) s.home()
            opened = false
        }
        landing = true
        main.removeCallbacks(landAnyway)
        main.postDelayed(landAnyway, if (s.liveOk) 1500 else 300)
        // The cover lifts once the screen has drawn a frame with what coming back changed, such as
        // the first card selected again: two frames from now, the first has been shown.
        settled = false
        val frames = Choreographer.getInstance()
        val now = ++returns
        frames.postFrameCallback { frames.postFrameCallback { if (now == returns) { settled = true; if (drawn && fresh && entered) land() } } }
    }

    /** The screen counts as arrived once the backdrop has drawn and the cards are current. */
    private fun land() {
        if (!landing) return
        landing = false
        main.removeCallbacks(landAnyway)
        // Anything pressed while the picture of the home screen was up is played now, but only if
        // the real one is laid out as the picture shows, so a press can't land on a different tile.
        if (!HomeKey.typedAhead) return arrive()
        val why = when {
            !fresh -> "the cards weren't up to date in time"
            !leftPlain -> "something was open over the home screen when you left"
            s.shape(selected()) != Backdrop.shape -> "the screen has changed since the picture was taken"
            else -> return playTyped(0)
        }
        HomeKey.note("presses on the picture not played: $why")
        arrive()
    }

    private fun arrive(atOnce: Boolean = false) {
        HomeKey.arrived(this, atOnce)
        loadTitles()
    }

    /** The tile this return ends on. */
    private fun selected() = if (homed) s.first() else leftAt

    /**
     * Focus takes a frame or two to reach its tile after coming back, and a press played before
     * then would start from the wrong one. So this waits, a few frames at most, and if it never
     * settles the presses are dropped, as they always used to be.
     */
    private fun playTyped(frame: Int) {
        Choreographer.getInstance().postFrameCallback {
            val settled = HomeKey.front && s.plain && s.focusAt == selected() && s.shape(s.focusAt) == Backdrop.shape
            when {
                settled && frame >= 1 -> {
                    val keys = HomeKey.takeTyped()
                    arrive(atOnce = true)
                    val t = SystemClock.uptimeMillis()
                    for ((code, action) in keys) runCatching { dispatchKeyEvent(KeyEvent(t, t, action, code, 0)) }
                    HomeKey.note("played ${keys.size} key events pressed on the picture")
                }
                frame >= SETTLE_FRAMES || !HomeKey.front -> {
                    HomeKey.note("presses on the picture not played: the selection didn't settle on ${selected()}, it's on ${s.focusAt}")
                    arrive()
                }
                else -> playTyped(frame + 1)
            }
        }
    }

    /** The window is on screen in full: the last thing the cover waits for. */
    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (entered) return
        entered = true
        HomeKey.mark("window shown")
        if (drawn && fresh && settled) land()
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        entered = false
        // Opened from All apps: it stays up until the app covers it, then it's gone for next time.
        if (opened) s.allApps = false
    }

    override fun onPause() {
        super.onPause()
        HomeKey.front = false
        s.shown = false
        away = true
        landing = false
        main.removeCallbacks(landAnyway)
        leftAt = s.focusAt
        leftPlain = s.plain
        // The picture for next time is taken once Android has been told this screen has stepped
        // aside, so the app that's opening isn't kept waiting while it's made.
        main.post(snapNow)
        s.live?.pause()
    }

    private val snapNow = Runnable { snap() }

    /**
     * Keeps a picture of the home screen to put up the moment Home is pressed, so it's there at once
     * even though the real one takes a second or so to come back. The clock and weather are painted
     * out with the backdrop behind them, because by then they could be hours old.
     */
    private fun snap() {
        val bg = s.live?.frame() ?: return
        Backdrop.keep(this, bg)
        if (s.nearIdle() || s.brief != null || s.panel || s.detail != null || s.allApps || s.menu != null) return
        val d = window.decorView
        val status = s.rects[STATUS]
        val shape = s.shape(s.focusAt)
        runCatching {
            val shot = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, shot, { result ->
                if (result != PixelCopy.SUCCESS) { HomeKey.note("no picture of the home screen: $result"); return@request }
                status?.let { r ->
                    val k = bg.width.toFloat() / shot.width
                    val to = Rect(r.left.toInt() - PAD, 0, shot.width, r.bottom.toInt() + PAD)
                    val from = Rect((to.left * k).toInt(), 0, bg.width, (to.bottom * k).toInt())
                    Canvas(shot).drawBitmap(bg, from, to, null)
                }
                Backdrop.home = shot
                Backdrop.shape = shape
            }, main)
        }.onFailure { HomeKey.note("no picture of the home screen: $it") }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(snapNow)
        Watching.changed = null
        Resume.changed = null
        Music.changed = null
        Brief.wake = null
        Apps.changed = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when {
            intent.getBooleanExtra("wake", false) -> woke()
            opened -> {} // Back from an app: stay on the tile you left.
            else -> s.home()
        }
    }

    /** The list of apps, made only if it isn't already, or an app has come or gone since. */
    private suspend fun loadApps() {
        val apps = Apps.ready() ?: withContext(Dispatchers.Default) { Apps.get(this@MainActivity) }
        if (apps !== s.apps) s.apps = apps
    }

    /** Several reports come for one install, so this waits for them to finish. */
    private fun reloadApps() {
        appsJob?.cancel()
        appsJob = lifecycleScope.launch {
            delay(1500)
            loadApps()
            loadTitles()
        }
    }

    private class Used(val history: Usage.History?, val last: Map<String, Usage.LastUse>, val usual: Usage.Usual?, val minutes: Map<String, Double>)

    /**
     * Brings the cards up to date. None of the three lookups waits on another, so they run side by
     * side, and everything is put on screen in one go: the rows change once, not card by card.
     */
    private suspend fun refresh() {
        val ctx = this
        val candidates = (Apps.DOCK + s.apps.map { it.pkg }).toSet()
        coroutineScope {
            val used = async(Dispatchers.Default) {
                val h = Usage.history(ctx)
                Used(h, Usage.lastUsed(ctx, h), Usage.usual(h, candidates), Usage.minutes(h))
            }
            val kodi = async(Dispatchers.IO) { Kodi.latest(ctx) }
            val watching = async(Dispatchers.IO) { Watching.latest(ctx) }
            val u = used.await()
            val k = kodi.await()
            val w = watching.await()
            // Read last, to give the app you just left the longest to say where you stopped.
            val resume = withContext(Dispatchers.Default) { Resume.get(ctx) }
            s.kodi = k
            showWatching(w)
            s.lastUsed = u.last
            s.resume = resume
            s.usual = u.usual
            s.useMinutes = u.minutes
            kodiAgain(u.history)
        }
    }

    /**
     * Kodi writes down where you stopped a moment after you leave it, which can be after the look
     * above. So when Kodi is what you've just left, it's read once more.
     */
    private fun kodiAgain(h: Usage.History?) {
        if (s.app(Kodi.PKG) == null) return
        val justLeft = h == null || h.sessions.lastOrNull()?.let { it.pkg == Kodi.PKG && System.currentTimeMillis() - it.end < 20_000 } == true
        if (!justLeft) return
        kodiJob?.cancel()
        kodiJob = lifecycleScope.launch {
            delay(2000)
            val k = withContext(Dispatchers.IO) { Kodi.latest(this@MainActivity) }
            val was = s.kodi
            if (k?.path != was?.path || k?.positionS != was?.positionS || k?.playedAt != was?.playedAt) s.kodi = k
        }
    }

    /** What you're partway through, by name, and each one's picture. */
    private suspend fun loadWatching() = showWatching(withContext(Dispatchers.IO) { Watching.latest(this@MainActivity) })

    private fun showWatching(items: List<Watching.Item>) {
        s.watching = if (Watching.hidden) emptyList() else items
        // How much is left of each show's season, and what's coming, from TMDB.
        lifecycleScope.launch(Dispatchers.IO) {
            val found = s.watching.mapNotNull { w -> Shows.refresh(this@MainActivity, w.title, w.season)?.let { w.title to it } }.toMap()
            if (found.isNotEmpty()) withContext(Dispatchers.Main) { s.shows = s.shows + found }
        }
        if (watchArtJob?.isActive == true) return
        watchArtJob = lifecycleScope.launch(Dispatchers.IO) {
            for (w in s.watching) {
                val url = w.art ?: continue
                if (w.key in s.thumbs) continue
                val name = "w-" + Integer.toHexString(url.hashCode()) + ".jpg"
                val art = Tmdb.fetch(this@MainActivity, url, name)?.let(Tmdb::card) ?: continue
                withContext(Dispatchers.Main) { s.hues[w.key] = Color(art.accent); s.thumbs[w.key] = art.thumb }
            }
        }
    }

    /** Today's titles and their pictures. Everything is kept on the TV, so this usually does nothing. */
    /**
     * After you've said what you think of a title, the picks are learned again, once you've stopped
     * for a moment, so saying it about several in a row costs one go.
     */
    private fun relearnSoon() {
        relearnJob?.cancel()
        relearnJob = lifecycleScope.launch {
            delay(RELEARN_AFTER_MS)
            while (titlesJob?.isActive == true) delay(2_000)
            loadTitles(again = true)
        }
    }

    private fun loadTitles(again: Boolean = false) {
        if (!Tmdb.on || s.apps.isEmpty() || titlesJob?.isActive == true) return
        titlesJob = lifecycleScope.launch(Dispatchers.IO) {
            val ctx = this@MainActivity
            var items = Tmdb.cached(ctx)
            if (items.isEmpty() || Tmdb.stale(ctx)) items = Tmdb.refresh(ctx) ?: items
            // Art for the picks likely to be shown, the weather's included, then for the New and Trending after them.
            suspend fun art(pool: Picks.Pool?): List<Tmdb.Item> {
                val chosen = pool?.let { p ->
                    Picks.row(p, s.sky(), HomeState.MAX_CARDS + 2, s.skipCards(), { s.app(it.pkg) != null }, s.week()).map { it.cand } +
                        listOf("storm", "snow", "night").mapNotNull { sky -> p.cands.firstOrNull { sky in it.skies && s.app(it.pkg) != null } }
                }.orEmpty().distinctBy { it.key }.map { it.item() }
                val mine = items.filter { s.app(it.pkg) != null }
                val picks = chosen + Tmdb.pick(mine, s.useMinutes, s.skipCards(), HomeState.MAX_CARDS + 2)
                for (t in picks) {
                    val key = "t-${t.key}"
                    if (key in s.thumbs) continue
                    val art = Tmdb.art(ctx, t.backdrop)?.let(Tmdb::card) ?: continue
                    withContext(Dispatchers.Main) { s.hues[key] = Color(art.accent); s.thumbs[key] = art.thumb }
                }
                return picks
            }
            // What's been learned from watching: as kept at once, so the row is up straight away, then
            // learned again once a day, which can take a while.
            // New picks take the row only once their pictures are in, so it rearranges once rather than
            // filling with New and Trending first and changing again as each picture lands.
            suspend fun show(p: Picks.Pool?) {
                val shown = art(p)
                withContext(Dispatchers.Main) { s.pool = p }
                loadDetails(shown)
            }
            var pool = Picks.cached(ctx)
            withContext(Dispatchers.Main) { s.titles = items; if (s.pool == null) s.pool = pool }
            show(pool)
            if (again || pool == null || Picks.stale(ctx)) {
                val have = s.apps.mapTo(HashSet()) { it.pkg }
                pool = runCatching { Picks.refresh(ctx, items, have, s.useMinutes, s.hiddenCards) }
                    .onFailure { android.util.Log.w("FireSkyPicks", "couldn't make picks", it) }.getOrNull() ?: pool
                show(pool)
            }
            withContext(Dispatchers.Main) {
                // Pictures of titles that have left the lists would otherwise be kept for as long as the app runs.
                val listed = (items.map { it.key } + pool?.cands.orEmpty().map { it.key }).mapTo(HashSet()) { "t-$it" }
                s.thumbs.keys.filter { it.startsWith("t-title:") && it !in listed }.forEach { s.thumbs.remove(it); s.hues.remove(it) }
            }
            val title = s.kodi?.title ?: return@launch
            val art = Tmdb.find(ctx, title)?.let { Tmdb.art(ctx, it) }?.let(Tmdb::card) ?: return@launch
            withContext(Dispatchers.Main) { s.hues["t-kodi"] = Color(art.accent); s.thumbs["t-kodi"] = art.thumb }
        }
    }

    /**
     * What each title's page will say, for the cards on show and the shows you're partway through.
     * What's already kept goes up first; anything missing or a week old is then asked for.
     */
    private suspend fun loadDetails(picks: List<Tmdb.Item>) {
        val ctx = this
        val want = (picks.map { it.movie to it.id } + s.watching.filter { it.season != null }.mapNotNull { s.shows[it.title] }.map { false to it.id }).distinct()
        suspend fun show(read: (Boolean, Int) -> Details.Info?) {
            val got = want.mapNotNull { (movie, id) -> read(movie, id) }.associateBy { it.key }
            withContext(Dispatchers.Main) { if (got.keys != s.details.keys || got.values.any { s.details[it.key] !== it }) s.details = got }
        }
        show { movie, id -> Details.cached(ctx, Details.key(movie, id)) }
        show { movie, id -> Details.get(ctx, movie, id) }
        // Each title's lettering, fetched ahead so its page opens with it.
        withContext(Dispatchers.Main) { s.details.values.mapNotNull { it.logo } }.forEach { Tmdb.fetch(ctx, LOGO_URL + it, logoName(it)) }
    }

    /** Lettering read recently, so going back to a page doesn't read it again. */
    private val logos = object : LinkedHashMap<String, ImageBitmap?>(16, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap?>) = size > 12
    }

    private fun logoName(path: String) = "logo-" + path.trimStart('/')

    /**
     * A title's lettering, kept clear so it sits on the picture. Lettering that's mostly dark would
     * vanish against the dark side of the page, so that keeps the name in type instead.
     */
    override suspend fun logo(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(logos) { if (logos.containsKey(path)) return@withContext logos[path] }
        val got = Tmdb.fetch(this@MainActivity, LOGO_URL + path, logoName(path))?.let { f ->
            runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
        }?.takeIf { Tmdb.light(it) }?.asImageBitmap()
        synchronized(logos) { logos[path] = got }
        got
    }

    /** The picture a title's page shows across the screen: TMDB's, at its full size. */
    override suspend fun art(c: Card): ImageBitmap? = withContext(Dispatchers.IO) {
        val path = s.titles.firstOrNull { "t-${it.key}" == c.key }?.backdrop
            ?: s.pool?.cands?.firstOrNull { "t-${it.key}" == c.key }?.backdrop ?: Tmdb.find(this@MainActivity, c.title)
        path?.let { Tmdb.art(this@MainActivity, it) }?.let { f ->
            runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })?.asImageBitmap() }.getOrNull()
        }
    }

    /** Music started, changed or stopped: the idle screen's colours, and the row's card with its own picture. */
    private fun music(t: Music.Track?) {
        s.music = t; s.live?.music(t)
        if (t == null) { s.thumbs.remove(HomeState.MUSIC_CARD); return }
        lifecycleScope.launch {
            val font = runCatching { resources.getFont(R.font.outfit) }.getOrNull()
            val art = withContext(Dispatchers.Default) { runCatching { Music.card(t, font) }.getOrNull() }
            if (s.music === t && art != null) s.thumbs[HomeState.MUSIC_CARD] = art.asImageBitmap()
        }
    }

    /** Waking: the rise, and on the day's first wake or the first after sunset, the brief. */
    private fun woke() {
        s.woke()
        s.brief = runCatching { Brief.due(this, s) }.getOrNull()
    }

    override fun trailer(c: Card, link: String) = leave(c) { view(link, YOUTUBE) }

    /** Straight into the app: its own screen appears over this one as it starts. */
    private fun leave(c: Card, go: () -> Boolean) {
        opened = true
        s.opening()
        if (!go()) {
            s.openingUntil = 0
            opened = false
            s.toast = "Couldn't open ${c.title}"
            return
        }
        // Some things, like switching input, may never take this screen away.
        lifecycleScope.launch {
            delay(6000)
            if (!away) opened = false
        }
    }

    private fun launch(pkg: String?): Boolean {
        val a = s.app(pkg) ?: return false
        return runCatching {
            startActivity(Intent(a.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }.isSuccess
    }

    private fun view(link: String?, pkg: String?): Boolean {
        val i = link?.let { Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } ?: return false
        return runCatching { startActivity(i) }.isSuccess
    }

    override fun open(c: Card) {
        when (c.kind) {
            // Kodi plays a path handed to it, and offers to resume from its bookmark.
            Kind.KODI -> leave(c) { view(s.kodi?.path, Kodi.PKG) || launch(Kodi.PKG) }
            // Straight to the show where the service's own ID is known, otherwise the app.
            Kind.TITLE -> { Passed.opened(c.hideId); leave(c) { view(c.link, c.pkg) || launch(c.pkg) } }
            // A named card goes to the show itself; a bare one just opens the app.
            Kind.RESUME -> leave(c) { view(s.linkFor(c), c.pkg) || launch(c.pkg) }
            Kind.APP, Kind.USUAL -> leave(c) { launch(c.pkg) }
            Kind.PS5 -> leave(c) { Input.switchTo(this, c.port) }
            Kind.SETTINGS -> if (!HomeKey.openSettings(this)) s.toast = "Couldn't open settings"
            Kind.ALL -> s.allApps = true
            Kind.WEATHER -> s.panel = true
            Kind.SETUP -> s.guide = true
            Kind.UPDATE -> update()
        }
    }

    override fun changed(key: String) {
        when (key) {
            Prefs.PLACE -> s.live?.place()
            // Each set of units keeps its own forecast, so this one is fetched if it isn't there.
            Prefs.TEMPERATURE, Prefs.WIND -> {
                s.wx = Weather.cached(this)
                lifecycleScope.launch { withContext(Dispatchers.IO) { Weather.fetch(this@MainActivity) }?.let { s.wx = it } }
            }
            Prefs.REGION, Prefs.TMDB -> {
                titlesJob?.cancel()
                Tmdb.forget(this)
                Details.forget(this)
                s.titles = emptyList()
                s.details = emptyMap()
                loadTitles()
            }
        }
    }

    override fun phone(open: Boolean) {
        phonePage?.stop()
        phonePage = null
        if (!open) { s.phoneUrl = null; return }
        val p = Phone(hasKey = { Tmdb.on }) { key ->
            Prefs.set(Prefs.TMDB, key ?: "")
            runOnUiThread {
                phone(false)
                s.settingsTick++
                s.toast = if (key == null) "New and Trending are off" else "New and Trending are on"
                changed(Prefs.TMDB)
            }
        }
        phonePage = p
        s.phoneUrl = p.start() ?: ""
    }

    override fun update() {
        s.update?.let { r ->
            if (!Updater.install(this, r)) s.toast = "Couldn't start the update"
            return
        }
        if (!BuildConfig.RELEASE) { s.toast = "A build of your own doesn't update itself"; return }
        s.toast = "Looking for an update…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Updater.check(this@MainActivity, now = true) }
            s.update = r
            s.toast = if (r != null) "FireSky ${r.version} is ready" else "FireSky is up to date"
        }
    }

    override fun amazonHome() {
        if (!HomeKey.openAmazonHome(this)) s.toast = "Couldn't open Amazon home"
    }


    private companion object {
        /** Where a title's lettering is read from, at a size that stays sharp on the TV. */
        const val LOGO_URL = "https://image.tmdb.org/t/p/w500"
        /** Room around the clock for its glow and the weather chip growing when selected. */
        const val PAD = 24
        const val RESUME_GRACE_MS = 2000L
        const val SETTLE_FRAMES = 6
        /** How long after the last thing you said about a title the picks are learned again. */
        const val RELEARN_AFTER_MS = 20_000L
    }
}
