package com.firesky.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), Actions {
    private val s = HomeState()
    /** You left through a tile, so coming back should land on that tile. */
    private var opened = false
    private var away = false
    private var titlesJob: Job? = null
    private var watchArtJob: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private var landing = false
    private val landAnyway = Runnable { land() }
    /** The backdrop has drawn since coming back. */
    private var drawn = false
    /** The cards are up to date since coming back. */
    private var fresh = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.system24 = android.text.format.DateFormat.is24HourFormat(this)
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
        s.load(this)
        s.drawn = { drawn = true; HomeKey.mark("backdrop drawn"); if (fresh) land() }
        s.risen = { HomeKey.risen() }
        s.wx = Weather.cached(this)
        setContent { Home(s, this) }
        // Netflix tells Fire TV what you're watching when it likes, often just after you leave it.
        Watching.changed = { runOnUiThread { lifecycleScope.launch { loadWatching() } } }

        lifecycleScope.launch {
            s.apps = withContext(Dispatchers.Default) { Apps.load(this@MainActivity) }
            refresh()
            ready()
            loadTitles()
        }
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
        lifecycleScope.launch {
            refresh()
            ready()
        }
    }

    private fun ready() {
        fresh = true
        HomeKey.mark("cards ready")
        if (drawn) land()
    }

    override fun onResume() {
        super.onResume()
        HomeKey.front = true
        HomeKey.mark("resumed")
        // Brought back by the TV waking up: the rise plays, from the first card.
        if (Wake.take()) s.woke()
        s.back()
        drawn = !s.liveOk
        s.live?.resume()
        if (away) {
            away = false
            // Home brings this task back without a new intent, so this is where that's noticed:
            // back from a tile stays on the tile, and Home on the home screen goes to the first card.
            if (!opened) s.home()
            opened = false
        }
        landing = true
        main.removeCallbacks(landAnyway)
        main.postDelayed(landAnyway, if (s.liveOk) 1500 else 300)
        if (drawn && fresh) land()
    }

    /** The screen counts as arrived once the backdrop has drawn and the cards are current. */
    private fun land() {
        if (!landing) return
        landing = false
        main.removeCallbacks(landAnyway)
        HomeKey.arrived(this)
        loadTitles()
    }

    override fun onStop() {
        super.onStop()
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
        if (!opened) snap()
        s.live?.pause()
    }

    /**
     * Keeps a picture of the home screen to put up the moment Home is pressed, so it's there at once
     * even though the real one takes a second or so to come back. The clock and weather are painted
     * out with the backdrop behind them, because by then they could be hours old.
     */
    private fun snap() {
        val bg = s.live?.frame() ?: return
        Backdrop.last = bg
        if (s.idle || s.panel || s.allApps || s.menu != null) return
        val d = window.decorView
        val status = s.rects[STATUS]
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
            }, main)
        }.onFailure { HomeKey.note("no picture of the home screen: $it") }
    }

    override fun onDestroy() {
        super.onDestroy()
        Watching.changed = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when {
            intent.getBooleanExtra("wake", false) -> s.woke()
            opened -> {} // Back from an app: stay on the tile you left.
            else -> s.home()
        }
    }

    private suspend fun refresh() {
        val candidates = (Apps.DOCK + s.apps.map { it.pkg }).toSet()
        val history = withContext(Dispatchers.Default) { Usage.history(this@MainActivity) }
        val lastUsed = withContext(Dispatchers.Default) { Usage.lastUsed(this@MainActivity, history) }
        val resume = withContext(Dispatchers.Default) { Resume.get(this@MainActivity) }
        val usual = withContext(Dispatchers.Default) { Usage.usual(history, candidates) }
        val kodi = withContext(Dispatchers.IO) { Kodi.latest(this@MainActivity) }
        s.kodi = kodi
        loadWatching()
        s.lastUsed = lastUsed
        s.resume = resume
        s.usual = usual
        s.useMinutes = Usage.minutes(history)
    }

    /** What you're partway through, by name, and each one's picture. */
    private suspend fun loadWatching() {
        s.watching = withContext(Dispatchers.IO) { Watching.latest(this@MainActivity) }
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
    private fun loadTitles() {
        if (!Tmdb.on || s.apps.isEmpty() || titlesJob?.isActive == true) return
        titlesJob = lifecycleScope.launch(Dispatchers.IO) {
            val ctx = this@MainActivity
            var items = Tmdb.cached(ctx)
            if (items.isEmpty() || Tmdb.stale(ctx)) items = Tmdb.refresh(ctx) ?: items
            withContext(Dispatchers.Main) { s.titles = items }
            val mine = items.filter { s.app(it.pkg) != null }
            for (t in Tmdb.pick(mine, s.useMinutes, s.hiddenCards, HomeState.MAX_CARDS + 2)) {
                val key = "t-${t.key}"
                if (key in s.thumbs) continue
                val art = Tmdb.art(ctx, t.backdrop)?.let(Tmdb::card) ?: continue
                withContext(Dispatchers.Main) { s.hues[key] = Color(art.accent); s.thumbs[key] = art.thumb }
            }
            val title = s.kodi?.title ?: return@launch
            val art = Tmdb.find(ctx, title)?.let { Tmdb.art(ctx, it) }?.let(Tmdb::card) ?: return@launch
            withContext(Dispatchers.Main) { s.hues["t-kodi"] = Color(art.accent); s.thumbs["t-kodi"] = art.thumb }
        }
    }

    /** Straight into the app: its own screen appears over this one as it starts. */
    private fun leave(c: Card, go: () -> Boolean) {
        snap()
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
            Kind.TITLE -> leave(c) { view(c.link, c.pkg) || launch(c.pkg) }
            // A named card goes to the show itself; a bare one just opens the app.
            Kind.RESUME -> leave(c) { view(s.linkFor(c), c.pkg) || launch(c.pkg) }
            Kind.APP, Kind.USUAL -> leave(c) { launch(c.pkg) }
            Kind.PS5 -> leave(c) { Input.switchTo(this, c.port) }
            Kind.SETTINGS -> openSettings()
            Kind.ALL -> s.allApps = true
            Kind.WEATHER -> s.panel = true
        }
    }

    override fun amazonHome() {
        if (!HomeKey.openAmazonHome(this)) s.toast = "Couldn't open Amazon home"
    }

    /** Fire TV's settings panel opens over the home screen; the full menu lives in Amazon's launcher. */
    private fun openSettings() {
        val tries = listOf(
            Intent().setClassName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.hud.HudActivity"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        s.toast = "Couldn't open settings"
    }

    private companion object {
        /** Room around the clock for its glow and the weather chip growing when selected. */
        const val PAD = 24
    }
}
