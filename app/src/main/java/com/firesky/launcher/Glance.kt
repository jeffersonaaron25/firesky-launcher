package com.firesky.launcher

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil

/** What another app is playing, from its media session: enough to say when it ends. */
object Playing {
    class Now(
        val pkg: String, val title: String?, val playing: Boolean,
        val positionMs: Long, val durationMs: Long,
        /** When [positionMs] was true, on the elapsed-time clock, and how fast it's going. */
        val at: Long, val speed: Float,
        /**
         * Where this stretch of watching began, and when by the wall clock: what tells the episode on
         * screen from the others in the app's continue-watching list.
         */
        val startMs: Long = positionMs, val since: Long = 0L,
    ) {
        /** Where it is right now. */
        fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long =
            if (playing && at > 0) positionMs + ((now - at) * speed).toLong() else positionMs

        /** How much is left right now, if the app says how long it is. */
        fun leftMs(now: Long = SystemClock.elapsedRealtime()): Long? {
            if (durationMs <= 0 || positionMs < 0) return null
            return (durationMs - positionAt(now)).coerceAtLeast(0)
        }

        val progress: Float? get() = leftMs()?.let { 1f - it.toFloat() / durationMs }
    }

    /** How far a continue-watching entry's saved place can be from where watching began and still be it. */
    private const val SLACK_MS = 2 * 60_000L
    /** Starting again this near the beginning, after being this much further on, is the next episode rolling on. */
    private const val RESTART_MS = 3 * 60_000L
    private const val JUMP_MS = 5 * 60_000L

    @Volatile var now: Now? = null
    /** Called on the main thread when what's playing starts, pauses, stops or changes. */
    @Volatile var changed: ((Now?) -> Unit)? = null

    /** A player reported a change. Stopping clears it only if it was the one playing. */
    fun report(pkg: String, st: PlaybackState?, meta: MediaMetadata?) {
        if (pkg == BuildConfig.APPLICATION_ID) return
        val n = when (st?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED -> {
                val was = now?.takeIf { it.pkg == pkg }
                val fresh = was == null || (st.position < RESTART_MS && was.positionAt(st.lastPositionUpdateTime) - st.position > JUMP_MS)
                Now(
                    pkg,
                    meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.ifBlank { null } ?: meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.ifBlank { null },
                    st.state == PlaybackState.STATE_PLAYING, st.position, meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                    st.lastPositionUpdateTime, st.playbackSpeed,
                    if (fresh) st.position else was!!.startMs, if (fresh) System.currentTimeMillis() else was!!.since,
                )
            }
            else -> if (now?.pkg == pkg) null else return
        }
        now = n
        changed?.invoke(n)
    }

    /**
     * What's known about it, filling in what the app leaves out. Netflix's player gives no title or
     * length, but its continue-watching list, which FireSky already reads, has both.
     */
    fun known(ctx: Context, n: Now?): Now? {
        if (n == null || (n.title != null && n.durationMs > 0)) return n
        val w = match(runCatching { Watching.of(ctx, n.pkg) }.getOrDefault(emptyList()), n) ?: return n
        val name = Shows.cached(ctx, w.title)?.name?.ifBlank { null } ?: w.title
        val ep = if (w.season != null && w.episode != null) " · S${w.season} E${w.episode}" else ""
        return Now(n.pkg, n.title ?: (name + ep), n.playing, n.positionMs, if (n.durationMs > 0) n.durationMs else w.durationMs, n.at, n.speed, n.startMs, n.since)
    }

    /**
     * The continue-watching entry that is what's on screen, or null when none can be told to be.
     * The newest entry isn't enough: Netflix had already moved a show on to its next episode while
     * an earlier one played. So an entry saved before this stretch began has to be where it began,
     * and one saved since has to be at a place already passed.
     */
    fun match(items: List<Watching.Item>, n: Now, now: Long = SystemClock.elapsedRealtime()): Watching.Item? {
        val pos = n.positionAt(now)
        return items.filter { w ->
            if (w.watchedAt < n.since) abs(w.progressMs - n.startMs) <= SLACK_MS
            else w.progressMs in (n.startMs - SLACK_MS)..(pos + SLACK_MS)
        }.maxByOrNull { it.watchedAt }
    }

    /** "ends 9:47 PM if you carry on", when the app says how long it is. */
    fun ends(n: Now?, wall: Long = System.currentTimeMillis()): String? =
        n?.leftMs()?.takeIf { it > 60_000 }?.let { Shows.endsAt(wall, it) + " if you carry on" }
}

/**
 * Holding ≡ over another app. A short press is left for the app; once it has been held for
 * [HOLD_MS], the rest of that press is FireSky's. Fire OS doesn't pass a held key's repeats to an
 * accessibility service, so the caller calls [timeUp] when the time has passed.
 */
class MenuHold {
    enum class Act { PASS, OPEN, EAT }

    private var down = false
    private var held = false

    fun on(action: Int, repeat: Int, allowed: Boolean): Act = when {
        action == KeyEvent.ACTION_DOWN && repeat == 0 -> { down = allowed; held = false; Act.PASS }
        action == KeyEvent.ACTION_DOWN -> when {
            held -> Act.EAT
            allowed -> { held = true; Act.OPEN }
            else -> Act.PASS
        }
        else -> { down = false; if (held) { held = false; Act.EAT } else Act.PASS }
    }

    /** Still held once the time is up: the glance opens, unless a repeat has already opened it. */
    fun timeUp(): Boolean {
        if (!down || held) return false
        held = true
        return true
    }

    companion object {
        const val HOLD_MS = 550L
    }
}

/** A quiet word over whatever is on: the space station about to pass, or rain about to start. */
object Nudges {
    /** At most one every half hour, and rain at most once in three hours. */
    const val GAP_MS = 30 * 60_000L
    const val RAIN_GAP_MS = 3 * 3_600_000L

    class Nudge(val text: String, val pass: Long = 0L, val rain: Boolean = false)

    /** Two to four minutes before a pass you could see, under a sky that isn't mostly cloud. Each pass once. */
    fun station(passes: List<Orbit.Pass>, ms: Long, cloud: Int, told: Long): Nudge? {
        if (cloud > 60) return null
        val p = passes.firstOrNull { it.end >= ms } ?: return null
        if (p.start == told) return null
        val mins = ceil((p.start - ms) / 60_000.0).toInt()
        if (mins !in 2..4) return null
        return Nudge("Space station overhead in $mins min · look ${Heavens.compass(p.fromAz)}", pass = p.start)
    }

    /** Rain or snow starting within half an hour while it's dry, in the same words as the home screen. */
    fun rain(w: Weather.Wx?, ms: Long, told: Long): Nudge? {
        val at = w?.rainAt ?: return null
        if (ms - told < RAIN_GAP_MS) return null
        val m = Regex("^(Rain|Snow) in (\\d+) min$").find(at) ?: return null
        if (m.groupValues[2].toInt() > 30) return null
        return Nudge(at, rain = true)
    }
}

enum class GlanceMode { NONE, STRIP, FULL }

/** Why a strip is up, so a pause strip can go when playing carries on and a nudge can't. */
enum class StripWhy { PAUSE, NUDGE }

/** What the glance shows. */
class GlanceState {
    var mode by mutableStateOf(GlanceMode.NONE)
    var why by mutableStateOf(StripWhy.PAUSE)
    var line by mutableStateOf<String?>(null)
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
    var index by mutableIntStateOf(0)
    var wx by mutableStateOf<Weather.Wx?>(null)
    var playing by mutableStateOf<Playing.Now?>(null)
    var appLabel by mutableStateOf("")
    var now by mutableLongStateOf(System.currentTimeMillis())
}

private val WARM = Color(0xFFFFC27A)

@Composable
private fun Clock(g: GlanceState, size: Float, weight: Int) {
    val c = Calendar.getInstance().apply { timeInMillis = g.now }
    T(Prefs.time(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)), size, weight)
}

@Composable
private fun WeatherChip(w: Weather.Wx, icon: Float, text: Float) {
    val z = LocalDz.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(z.d(8))) {
        WeatherIcon(w.code, w.isDay, icon)
        T("${w.temp}°", text, 400)
    }
}

/**
 * The small one, in the corner where the home screen keeps its clock: the time and the weather,
 * and a line under them when there's something to say.
 */
@Composable
fun GlanceStrip(g: GlanceState) {
    val z = LocalDz.current
    val shape = RoundedCornerShape(z.d(22))
    Box(Modifier.padding(top = z.d(34), end = z.d(44), start = z.d(12), bottom = z.d(12))) {
        Column(
            Modifier.background(Color(0xC20C0F17), shape).border(1.dp, Color.White.copy(alpha = .12f), shape)
                .padding(horizontal = z.d(22), vertical = z.d(12)),
            horizontalAlignment = Alignment.End,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(z.d(18))) {
                g.wx?.let { WeatherChip(it, 24f, 22f) }
                Clock(g, 34f, 300)
            }
            g.line?.let {
                Spacer(Modifier.height(z.d(4)))
                T(it, 18f, 500, if (g.why == StripWhy.NUDGE) 1f else .82f, color = if (g.why == StripWhy.NUDGE) WARM else Color.White)
            }
        }
    }
}

/**
 * The full glance over a show: the home screen's clock and weather where they always are, what's
 * playing and when it ends where the home screen names a title, and the dock where the dock is.
 */
@Composable
fun GlanceFull(g: GlanceState) {
    val z = LocalDz.current
    Box(Modifier.fillMaxSize()) {
        // The show dims behind it, most where the words and the dock are.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .45f), .2f to Color.Transparent, .42f to Color.Transparent, .8f to Color.Black.copy(alpha = .7f), 1f to Color.Black.copy(alpha = .8f))))
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = z.d(42), end = z.d(60)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(z.d(22)),
        ) {
            g.wx?.let { WeatherChip(it, 28f, 26f) }
            Clock(g, 54f, 200)
        }
        val p = g.playing
        if (p != null && (p.title != null || Playing.ends(p, g.now) != null)) {
            // Where the home screen names a title, clear of the player's own controls along the bottom.
            Column(Modifier.offset(z.d(72), z.d(176)).widthIn(max = z.d(1000))) {
                T((if (p.playing) "Now playing" else "Paused").uppercase() + (if (g.appLabel.isNotEmpty()) " · ${g.appLabel.uppercase()}" else ""), 14f, 600, .7f, .16f)
                p.title?.let { Box(Modifier.height(z.d(64)), contentAlignment = Alignment.CenterStart) { T(it, 44f, 600, spacing = -.02f) } }
                Playing.ends(p, g.now)?.let { T(it, 24f, 400, .85f) }
                p.progress?.let { k ->
                    Spacer(Modifier.height(z.d(12)))
                    Canvas(Modifier.width(z.d(420)).height(z.d(4))) {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(Color.White.copy(alpha = .22f), cornerRadius = r)
                        drawRoundRect(Color.White, size = Size(size.width * k, size.height), cornerRadius = r)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = z.d(26)), horizontalAlignment = Alignment.CenterHorizontally) {
            g.apps.getOrNull(g.index)?.let { T(it.label, 20f, 500, .9f) }
            Spacer(Modifier.height(z.d(10)))
            val bar = RoundedCornerShape(z.d(28))
            Row(
                Modifier.background(Color.White.copy(alpha = .10f), bar).border(1.dp, Color.White.copy(alpha = .15f), bar).padding(z.d(16)),
                horizontalArrangement = Arrangement.spacedBy(z.d(12)),
            ) {
                g.apps.forEachIndexed { i, a -> AppTile(a, i == g.index) }
            }
        }
    }
}

@Composable
private fun AppTile(a: AppEntry, picked: Boolean) {
    val z = LocalDz.current
    val shape = RoundedCornerShape(z.d(14))
    val grow = if (picked) 1.1f else 1f
    Box(
        Modifier.graphicsLayer { scaleX = grow; scaleY = grow; alpha = if (picked) 1f else .78f }
            .size(z.d(100), z.d(56)).clip(shape).background(a.hue)
            .then(if (picked) Modifier.border(z.d(2.5f), Color.White, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            a.banner != null -> Image(a.banner, a.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            a.icon != null -> Image(a.icon, a.label, Modifier.size(z.d(40)))
            else -> T(a.label, 16f, 600)
        }
    }
}

/** Either glance, fading in and out, with the clock kept to the minute while it's up. */
@Composable
fun GlanceView(g: GlanceState, widthPx: Int) {
    val d = LocalDensity.current
    val z = remember(widthPx, d) { Dz(widthPx / d.density / 1280f, d, widthPx) }
    LaunchedEffect(g.mode) {
        while (g.mode != GlanceMode.NONE) { g.now = System.currentTimeMillis(); delay(15_000) }
    }
    CompositionLocalProvider(LocalDz provides z) {
        AnimatedVisibility(g.mode == GlanceMode.FULL, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) { GlanceFull(g) }
        AnimatedVisibility(g.mode == GlanceMode.STRIP, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) { GlanceStrip(g) }
    }
}

/** Gives a view outside any activity the lifecycle and saved state that Compose looks for. */
private class Owner : LifecycleOwner, SavedStateRegistryOwner {
    private val reg = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performAttach()
        saved.performRestore(null)
        reg.currentState = Lifecycle.State.RESUMED
    }

    fun end() { reg.currentState = Lifecycle.State.DESTROYED }
}

/**
 * The glance over other apps, put up by the Home key's accessibility service: a window that takes
 * no focus, so the app keeps playing underneath, with the remote's keys handed to it by the service
 * while the full one is up. Main thread only.
 */
class Glance(private val ctx: Context) {
    val state = GlanceState()
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = ctx.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: Owner? = null
    private var shownAs = GlanceMode.NONE

    private val autoHide = Runnable { hide() }
    private val remove = Runnable { detach() }

    val full get() = state.mode == GlanceMode.FULL
    val showing get() = state.mode != GlanceMode.NONE

    /** The dock, as the home screen has it, and what's playing. */
    fun full() {
        val home = HomeState().apply { load(ctx); apps = Apps.ready() ?: Apps.get(ctx) }
        state.apps = home.dock().filter { it.kind == Kind.APP }.mapNotNull { home.app(it.pkg) }
        state.playing = Playing.known(ctx, Playing.now)
        state.appLabel = Playing.now?.let { n -> home.app(Resume.alias(n.pkg))?.label }.orEmpty()
        state.index = state.apps.indexOfFirst { Resume.alias(it.pkg) == Resume.alias(Playing.now?.pkg ?: "") }.coerceAtLeast(0)
        show(GlanceMode.FULL, FULL_MS)
    }

    fun strip(line: String?, why: StripWhy, ms: Long) {
        if (full) return
        state.line = line
        state.why = why
        show(GlanceMode.STRIP, ms)
    }

    /** Takes down a strip put up for a pause, now that playing has carried on. */
    fun unpause() { if (state.mode == GlanceMode.STRIP && state.why == StripWhy.PAUSE) hide() }

    fun hide() {
        main.removeCallbacks(autoHide)
        if (state.mode == GlanceMode.NONE) return
        state.mode = GlanceMode.NONE
        main.postDelayed(remove, 400)
    }

    private fun show(mode: GlanceMode, ms: Long) {
        main.removeCallbacks(remove)
        main.removeCallbacks(autoHide)
        state.wx = Weather.cached(ctx)
        state.now = System.currentTimeMillis()
        attach(mode)
        state.mode = mode
        main.postDelayed(autoHide, ms)
    }

    private fun params(mode: GlanceMode) = WindowManager.LayoutParams(
        if (mode == GlanceMode.FULL) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        if (mode == GlanceMode.FULL) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.END }

    /** The strip's window is only as big as the strip, so the video underneath isn't covered by an empty one. */
    private fun attach(mode: GlanceMode) {
        val v = view
        if (v != null) {
            if (shownAs != mode) runCatching { wm.updateViewLayout(v, params(mode)) }
            shownAs = mode
            return
        }
        val o = Owner()
        val c = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            val width = ctx.resources.displayMetrics.widthPixels
            setContent { GlanceView(state, width) }
        }
        runCatching { wm.addView(c, params(mode)); view = c; owner = o; shownAs = mode }
            .onFailure { HomeKey.note("couldn't put the glance up: ${it.message}"); o.end() }
    }

    private fun detach() {
        val v = view ?: return
        view = null
        runCatching { wm.removeView(v) }
        owner?.end(); owner = null
        shownAs = GlanceMode.NONE
    }

    /** The remote while the full glance is up. Media keys and the rest still reach the app. */
    fun key(e: KeyEvent): Boolean {
        if (!full) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        val up = e.action == KeyEvent.ACTION_UP
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, FULL_MS)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                val i = state.index + if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (i in state.apps.indices) state.index = i
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (up) {
                val a = state.apps.getOrNull(state.index)
                hide()
                if (a != null && Resume.alias(a.pkg) != Resume.alias(state.playing?.pkg ?: "")) {
                    runCatching { ctx.startActivity(Intent(a.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> if (up) hide()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {}
            else -> return false
        }
        return true
    }

    /** Takes it all down at once, for when the service goes. */
    fun end() { main.removeCallbacks(autoHide); state.mode = GlanceMode.NONE; detach() }

    companion object {
        /** The full glance goes by itself after this long without a press. */
        const val FULL_MS = 10_000L
        const val PAUSE_MS = 15_000L
        const val NUDGE_MS = 10_000L
    }
}
