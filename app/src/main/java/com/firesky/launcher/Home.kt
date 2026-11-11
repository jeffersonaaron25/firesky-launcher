package com.firesky.launcher

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

interface Actions {
    fun open(c: Card)
    fun amazonHome()
    /** A setting changed on the TV; [key] says which. */
    fun changed(key: String) {}
    /** Opens or closes the phone page for the TMDB key. */
    fun phone(open: Boolean) {}
    /** Installs the downloaded release, or looks for one. */
    fun update() {}
    /** Plays a title's trailer, for [c]'s page. */
    fun trailer(c: Card, link: String) {}
    /** The full-size picture for a title's page. Null leaves the card's own smaller one. */
    suspend fun art(c: Card): ImageBitmap? = null
}

/** Everything is laid out in the mock's 1280x720 design units and scaled to the screen. */
class Dz(private val u: Float, private val density: Density, val widthPx: Int) {
    fun d(v: Number): Dp = (v.toFloat() * u).dp
    fun sp(v: Number): TextUnit = with(density) { (v.toFloat() * u).dp.toSp() }
    fun px(v: Number): Float = with(density) { (v.toFloat() * u).dp.toPx() }
}

val LocalDz = staticCompositionLocalOf<Dz> { error("Dz not provided") }

@OptIn(ExperimentalTextApi::class)
val Outfit = FontFamily(
    listOf(200, 300, 400, 500, 600, 700, 800).map { w ->
        Font(R.font.outfit, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

@Composable
fun T(
    text: String, size: Float, weight: Int = 400, alpha: Float = 1f, spacing: Float = 0f,
    color: Color = Color.White, modifier: Modifier = Modifier,
) {
    val z = LocalDz.current
    BasicText(
        text, modifier,
        style = TextStyle(
            color = color.copy(alpha = color.alpha * alpha),
            fontSize = z.sp(size), fontFamily = Outfit, fontWeight = FontWeight(weight),
            letterSpacing = spacing.em,
        ),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Home(s: HomeState, act: Actions) {
    val fm = LocalFocusManager.current
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { handleKey(s, act, it, fm) },
    ) {
        val density = LocalDensity.current
        val w = constraints.maxWidth
        val z = remember(w, density) { Dz(maxWidth.value / 1280f, density, w) }
        CompositionLocalProvider(LocalDz provides z) { Screen(s, act) }
    }
}

private fun isOk(e: KeyEvent) = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter

private fun handleKey(s: HomeState, act: Actions, e: KeyEvent, fm: FocusManager): Boolean {
    s.lastInput = SystemClock.uptimeMillis()
    val up = e.type == KeyEventType.KeyUp
    if (s.idle) {
        if (up) s.idle = false
        return true
    }
    // Any press puts the brief away and then does what it always does.
    if (s.brief != null) s.brief = null
    // The app you picked is still opening, so another OK would open it twice.
    if (isOk(e) && SystemClock.uptimeMillis() < s.openingUntil) return true
    // The QR pages close with Back (or OK); under them, FireSky's settings have keys of their own.
    if (s.phoneUrl != null || s.guide) {
        if (up && (e.key == Key.Back || e.key == Key.Menu || isOk(e))) { if (s.phoneUrl != null) act.phone(false); s.guide = false; Sfx.back() }
        return true
    }
    if (s.settings) return settingsKey(s, act, e)
    s.detail?.let { return detailKey(s, act, it, e) }
    s.menu?.let { return menuKey(s, act, it, e) }
    s.moving?.let { return moveKey(s, it, e) }
    if (s.panel) {
        if (up && (e.key == Key.Back || e.key == Key.Menu || isOk(e))) { s.panel = false; Sfx.back() }
        return true
    }
    // Menu is the options button, as on Amazon's home: the same choices as holding OK on a tile.
    // (Fire TV's own quick settings only open for Amazon's apps.) The weather is the chip in the corner.
    if (e.key == Key.Menu) {
        if (up) {
            val c = if (s.allApps) s.current?.removePrefix("all-")?.let(s::app)?.let(s::allCard) else s.rows.flatten().firstOrNull { it.key == s.current }
            c?.let { s.openMenu(it); Sfx.select() }
        }
        return true
    }
    if (s.allApps && e.key == Key.Back) {
        if (up) { s.allApps = false; Sfx.back() }
        return true
    }
    if (playKey(s, act, e)) return true
    // Arrows move focus here so each step can play its note, and a dead end can say so.
    val dir = when (e.key) {
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> return false
    }
    // The dock and the row stop at their ends. Past the last tile, focus would jump to whatever lies
    // that way, such as a card waiting off screen above the dock.
    if ((dir == FocusDirection.Left || dir == FocusDirection.Right) && !s.allApps) {
        val row = s.rows.firstOrNull { r -> r.any { it.key == s.current } }
        val i = row?.indexOfFirst { it.key == s.current } ?: -1
        if (row != null && i == (if (dir == FocusDirection.Right) row.lastIndex else 0)) {
            if (e.type == KeyEventType.KeyDown) Sfx.edge()
            return true
        }
    }
    if (e.type == KeyEventType.KeyDown) {
        Sfx.armed = true
        val moved = fm.moveFocus(dir)
        Sfx.armed = false
        if (moved) s.moved = true else Sfx.edge()
    }
    return true
}

private fun menuKey(s: HomeState, act: Actions, m: TileMenu, e: KeyEvent): Boolean {
    val up = e.type == KeyEventType.KeyUp
    when {
        e.key == Key.DirectionUp || e.key == Key.DirectionDown -> if (e.type == KeyEventType.KeyDown) {
            val d = if (e.key == Key.DirectionUp) -1 else 1
            s.menuIndex = (s.menuIndex + d + m.items.size) % m.items.size
            Sfx.move(s.menuIndex + 3)
        }
        e.key == Key.Back || e.key == Key.Menu -> if (up) { s.menu = null; Sfx.back() }
        isOk(e) -> if (up) {
            // The first release belongs to the long press that opened this menu.
            if (s.menuArmed) pick(s, act, m) else s.menuArmed = true
        } else if (e.nativeKeyEvent.repeatCount == 0) s.menuArmed = true
    }
    return true
}

private fun pick(s: HomeState, act: Actions, m: TileMenu) {
    val c = m.card
    s.menu = null
    Sfx.select()
    when (m.items[s.menuIndex]) {
        MenuAct.MOVE -> { s.moving = c.dockId; s.toast = "◀ ▶ to move · OK to place" }
        MenuAct.HIDE, MenuAct.HIDE_CARD -> {
            val row = if (c.dockId != null) s.dock() else s.tonight()
            val i = row.indexOfFirst { it.key == c.key }
            val next = row.getOrNull(i + 1) ?: row.getOrNull(i - 1) ?: s.dock().firstOrNull()
            s.hide(c, next?.key)
        }
        MenuAct.RESET -> s.resetDock()
        MenuAct.AMAZON -> act.amazonHome()
        MenuAct.FIRESKY -> { s.settingsIndex = 0; s.settings = true }
        MenuAct.ADD_DOCK -> c.pkg?.let(s::addToDock)
        MenuAct.UNDOCK -> { s.hide(c, null); s.toast = "${c.title} is out of the dock" }
        MenuAct.HIDE_APP -> c.pkg?.let(s::hideApp)
        MenuAct.DETAILS -> s.detail = s.detailOf(c)
    }
}

private fun moveKey(s: HomeState, id: String, e: KeyEvent): Boolean {
    when {
        e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> if (e.type == KeyEventType.KeyDown) {
            if (s.shift(id, if (e.key == Key.DirectionLeft) -1 else 1)) Sfx.move(4) else Sfx.edge()
        }
        isOk(e) || e.key == Key.Back -> if (e.type == KeyEventType.KeyUp) { s.moving = null; Sfx.select() }
    }
    return true
}

private fun Color.rgb() = floatArrayOf(red * 255, green * 255, blue * 255)

/** How far along a block is in the wake-up rise, starting [delay] into it. */
private fun riseAt(t: Float, delay: Float): Float {
    val p = ((t * WAKE_MS - delay) / 800f).coerceIn(0f, 1f)
    return 1 - (1 - p) * (1 - p) * (1 - p)
}

private const val WAKE_MS = 1340f
private const val TICK_MS = 15_000L

// The Tonight row, in design units: where it starts, each card and the gap between them.
private const val ROW_X = 72f
private const val CARD_W = 224f
private const val CARD_GAP = 18f
/** How far along the row a card can start and still fit, with a sliver of screen to spare. */
private const val ROW_FIT = 1280f - 16f - ROW_X - CARD_W

@Composable
private fun Screen(s: HomeState, act: Actions) {
    val z = LocalDz.current
    // Time is looked at four times a minute, in step with the clock, and again the moment you come
    // back. The clock on screen changes on the minute; "5 min ago" and "ends 9:47 PM" keep up too.
    val tick = produceState(System.currentTimeMillis() / TICK_MS, s.backTick) {
        while (true) {
            value = System.currentTimeMillis() / TICK_MS
            delay(TICK_MS - System.currentTimeMillis() % TICK_MS + 20)
        }
    }
    val minute by remember { derivedStateOf { tick.value * TICK_MS / 60_000 } }
    val now = remember(minute, s.backTick) { Calendar.getInstance() }
    // Idle: after three minutes untouched, only the wallpaper and the time remain.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            if (s.idleDue()) s.idle = true
        }
    }

    // The two rows are worked out again only when something they show has changed, or time has
    // moved on, and drawn again only if that made a difference: not on every press of the remote.
    val tonight by remember { derivedStateOf { tick.value; s.backTick; s.tonight() } }
    val dock by remember { derivedStateOf { tick.value; s.backTick; s.dock() } }
    val wx = s.wxCard()
    // Which tile is selected is read only by the few things that follow it, so moving along a row
    // redraws the two tiles involved and the hero, not the whole screen.
    val focused = remember { mutableStateOf<String?>(null) }
    fun select(k: String?) { focused.value = k; s.current = k }
    SideEffect { s.rows = listOf(dock, tonight) }
    val hero = remember {
        derivedStateOf {
            val f = focused.value
            (listOfNotNull(s.wxCard()) + tonight + dock).firstOrNull { it.key == f } ?: tonight.firstOrNull() ?: dock.firstOrNull()
        }
    }
    val inShelf = remember { derivedStateOf { val f = focused.value; f == "wx" || tonight.any { it.key == f } } }
    val at = remember { derivedStateOf { val f = focused.value; tonight.indexOfFirst { it.key == f } } }

    val ui by animateFloatAsState(if (s.idle || s.allApps) 0f else 1f, tween(if (s.allApps) 200 else 800), label = "ui")
    val drop by animateFloatAsState(if (s.idle) 1f else 0f, tween(600, easing = FastOutSlowInEasing), label = "drop")
    val dockA = remember { Animatable(1f) }

    // The selected tile tilts and catches the light when you land on it, then holds still. A screen
    // that never stops drawing never lets Android settle after switching apps, which keeps the app
    // you just left running for ten seconds more.
    val wob = remember { Animatable(.5f) }
    val shine = remember { Animatable(1f) }
    val landed = remember { mutableIntStateOf(0) }
    // More cards than fit on screen, so the row slides along to keep the selected one in view.
    val slide = remember { Animatable(0f) }
    Follow(s, hero, inShelf, at, tonight.size, landed, wob, shine, slide, dockA)

    val reqs = remember { HashMap<String, FocusRequester>() }
    fun req(k: String) = reqs.getOrPut(k) { FocusRequester() }
    fun focusOn(k: String?) { k?.let { runCatching { reqs[it]?.requestFocus() } } }

    // Pressing Home on the home screen, or waking, goes to the best guess: the first card.
    val firstKey = (tonight.firstOrNull() ?: dock.firstOrNull())?.key
    LaunchedEffect(s.homeTick) {
        if (firstKey == null) return@LaunchedEffect
        // The hero changes at once, so a wake-up doesn't rise with the last thing you selected.
        select(firstKey)
        withFrameNanos { }
        focusOn(firstKey)
    }
    // Otherwise focus stays where you left it, unless that tile has gone. Closing All apps goes
    // back to the tile that opened it: whatever caught focus as it closed doesn't count.
    val keys = (listOfNotNull(wx) + tonight + dock).map { it.key }
    var beforeAll by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.allApps) { if (s.allApps) beforeAll = focused.value }
    LaunchedEffect(keys, s.allApps) {
        if (s.allApps || firstKey == null) return@LaunchedEffect
        withFrameNanos { }
        val back = beforeAll?.takeIf { it in keys }
        beforeAll = null
        focusOn(back ?: if (s.moved && focused.value in keys) focused.value else firstKey)
    }
    LaunchedEffect(s.focusTick) {
        withFrameNanos { }
        focusOn(s.focusKey)
    }

    // Rain that's on its way shows as cloud first, drifting in from the side the weather is coming from.
    LaunchedEffect(s.wx, s.live) { s.wx?.let { s.live?.weather(if (it.kind == Sky.CLEAR && it.rainSoon) Sky.CLOUDY else it.kind, it.sun, it.cloud, it.windFrom, it.storm, it.windKmh) } }
    LaunchedEffect(s.idle, s.live) { s.live?.idle(s.idle) }

    val rise = remember { Animatable(1f) }
    var riseFor by remember { mutableIntStateOf(s.wakeTick) }
    LaunchedEffect(s.wakeTick) {
        if (s.wakeTick == riseFor) return@LaunchedEffect
        rise.snapTo(0f)
        riseFor = s.wakeTick
        // Once black is on screen here, the black cover put up for the wake can go, so the rise is seen.
        launch { withFrameNanos { }; withFrameNanos { }; s.risen() }
        rise.animateTo(1f, tween(WAKE_MS.toInt(), easing = LinearEasing))
    }
    // A wake shows black until the rise begins, never a frame of the screen as it was.
    fun riseNow() = if (s.wakeTick != riseFor) 0f else rise.value
    // The day's brief holds the title, cards and dock back, then hands over to them.
    val briefA by animateFloatAsState(if (s.brief != null) 1f else 0f, tween(if (s.brief != null) 0 else 700), label = "brief")
    LaunchedEffect(s.brief) { if (s.brief != null) { delay(WAKE_MS.toLong() + Brief.SHOWN_MS); s.brief = null } }
    var lastBrief by remember { mutableStateOf(s.brief) }
    SideEffect { s.brief?.let { lastBrief = it } }
    fun Modifier.rising(delay: Float, always: Boolean = false) = graphicsLayer {
        val p = riseAt(riseNow(), delay)
        alpha = if (always) p else p * (1 - briefA)
        translationY = z.px(20f) * (1 - p)
    }
    // Whether the rise is under way, not how far along: the frames in between only need drawing.
    val rising by remember { derivedStateOf { riseNow() < 1f } }

    val tile = remember(s, act) {
        TileSet(wob.asState(), shine.asState(), { select(it.key); s.focusAt = it.key; landed.intValue++ }, act::open, s::openMenu, s.rects)
    }
    val allTile = remember(s, act) { TileSet(wob.asState(), shine.asState(), { s.current = it.key; landed.intValue++ }, act::open, s::openMenu, s.rects) }

    Box(Modifier.fillMaxSize()) {
        if (s.liveOk) {
            AndroidView(
                factory = { ctx -> LiveView(ctx, { s.live = null; s.liveOk = false }, { s.drawn() }).also { s.live = it } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val hour = now.get(Calendar.HOUR_OF_DAY)
            Painted(Landscape.keyFor(hour, now.get(Calendar.MINUTE)), hero, if (hour >= 23 || hour < 5) .8f else 1f)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .48f to Color.Transparent, 1f to Color.Black.copy(alpha = .78f))))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .5f), .6f to Color.Transparent)))
        }

        Status(s, wx, now, req("wx"), { select(it.key); s.focusAt = it.key }, Modifier.align(Alignment.TopEnd).padding(top = z.d(42), end = z.d(60)).rising(200f, always = true))

        Box(Modifier.offset(z.d(72), z.d(176)).graphicsLayer { alpha = ui }.rising(300f)) { Hero(hero) }
        if (briefA > 0f) (s.brief ?: lastBrief)?.let { b ->
            Box(Modifier.offset(z.d(72), z.d(176)).rising(300f, always = true).graphicsLayer { alpha = briefA }) { BriefView(b) }
        }

        if (tonight.isNotEmpty()) {
            Row(
                Modifier
                    .offset(z.d(ROW_X), z.d(424))
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset { IntOffset(-slide.value.roundToInt(), 0) }
                    .graphicsLayer { alpha = ui }
                    .rising(420f),
                horizontalArrangement = Arrangement.spacedBy(z.d(CARD_GAP)),
            ) {
                tonight.forEachIndexed { i, c ->
                    key(c.key) {
                        Tile(c, s.app(c.pkg), CARD_W, 126f, false, req(c.key), tile, step = i + 4, thumb = s.thumbs[c.key])
                    }
                }
            }
        }

        val dockShape = RoundedCornerShape(z.d(28))
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = z.d(26))
                .rising(540f)
                .graphicsLayer { alpha = ui * dockA.value; translationY = z.px(150f) * drop }
                .background(Color.White.copy(alpha = .10f), dockShape)
                .border(1.dp, Color.White.copy(alpha = .15f), dockShape)
                .padding(horizontal = z.d(18), vertical = z.d(12)),
            horizontalArrangement = Arrangement.spacedBy(z.d(12)),
        ) {
            dock.forEachIndexed { i, c ->
                key(c.key) {
                    Tile(c, s.app(c.pkg), 100f, 56f, true, req(c.key), tile, step = i, moving = s.moving != null && s.moving == c.dockId)
                }
            }
        }

        if (s.allApps) AllApps(s, act, allTile)
        Menu(s)
        Panel(s)
        DetailLayer(s, act)
        SettingsScreen(s, act)
        // Last, so a message sent from All apps or the settings is seen above them, and down at the
        // edge there, clear of their rows.
        Toast(s, Modifier.align(Alignment.BottomCenter).padding(bottom = z.d(if (s.settings || s.allApps) 18 else 130)))
        if (rising) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1 - riseNow() * WAKE_MS / 1100f).coerceIn(0f, 1f) }.background(Color.Black))
        }
    }
}

/**
 * Everything that follows the selected tile: its tilt and shine, the backdrop taking its colour,
 * the row sliding to keep it in view, the dock stepping back. It draws nothing, and sits apart so
 * that these are all that a move along a row wakes.
 */
@Composable
private fun Follow(
    s: HomeState, hero: State<Card?>, inShelf: State<Boolean>, at: State<Int>, cards: Int, landed: State<Int>,
    wob: Animatable<Float, AnimationVector1D>, shine: Animatable<Float, AnimationVector1D>,
    slide: Animatable<Float, AnimationVector1D>, dockA: Animatable<Float, AnimationVector1D>,
) {
    val z = LocalDz.current
    val n = landed.value
    LaunchedEffect(n) {
        if (n == 0) return@LaunchedEffect
        launch {
            shine.snapTo(.3f)
            delay(250)
            shine.animateTo(.7f, tween(1600, easing = LinearEasing))
            shine.snapTo(1f)
        }
        wob.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
        wob.animateTo(0f, tween(2000, easing = FastOutSlowInEasing))
        wob.animateTo(.5f, tween(1200, easing = FastOutSlowInEasing))
    }

    // Waiting for you to settle means moving quickly along the row costs the backdrop nothing.
    val c = hero.value
    LaunchedEffect(c?.key, c?.hue, s.live) {
        c ?: return@LaunchedEffect
        val id = when (c.kind) {
            Kind.WEATHER -> null
            Kind.PS5 -> "ps5"
            Kind.SETTINGS -> "settings"
            Kind.ALL -> "all"
            else -> c.pkg
        }
        delay(140)
        s.live?.focus(id, if (c.kind == Kind.WEATHER) null else c.hue.rgb())
    }

    // The first five cards sit where they always have; the row only moves once you go past them.
    val i = at.value
    LaunchedEffect(i, cards) {
        if (i < 0) return@LaunchedEffect
        val step = CARD_W + CARD_GAP
        val most = ((cards - 1) * step - ROW_FIT).coerceAtLeast(0f)
        val now = slide.value / z.px(1)
        val want = now.coerceIn(i * step - ROW_FIT, i * step).coerceIn(0f, most)
        if (want != now) slide.animateTo(z.px(want), tween(320, easing = FastOutSlowInEasing))
    }

    // The dock steps back while you're among the cards.
    val shelf = inShelf.value
    LaunchedEffect(shelf) { dockA.animateTo(if (shelf) .7f else 1f, tween(400)) }
}

/* ---------- wallpaper ---------- */

/** The painted scenes, used only if the live backdrop can't start. */
object Walls {
    private val cache = LinkedHashMap<String, ImageBitmap>()

    @Synchronized
    fun get(key: String, widthPx: Int): ImageBitmap {
        cache[key]?.let { return it }
        val b = Landscape.paint(key, widthPx / 1280f).asImageBitmap()
        cache[key] = b
        while (cache.size > 2) cache.remove(cache.keys.first())
        return b
    }
}

/** The painted scene, washed with the selected tile's colour. */
@Composable
private fun Painted(key: String, hero: State<Card?>, dim: Float) {
    val tint by animateColorAsState(hero.value?.hue ?: Color(0xFF6A3A6C), tween(1000), label = "tint")
    Wallpaper(key, tint, dim)
}

@Composable
private fun Wallpaper(key: String, tint: Color, dim: Float) {
    val z = LocalDz.current
    val bmp by produceState<ImageBitmap?>(null, key, z.widthPx) {
        value = withContext(Dispatchers.Default) { Walls.get(key, z.widthPx) }
    }
    val kb = rememberInfiniteTransition(label = "kb")
    val t = kb.animateFloat(0f, 1f, infiniteRepeatable(tween(50_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "kbT")
    val gray = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    val tintNow by rememberUpdatedState(tint)
    Crossfade(bmp, animationSpec = tween(1500), label = "wall") { b ->
        if (b != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = t.value
                        val sc = 1.02f + .10f * p
                        scaleX = sc; scaleY = sc
                        translationX = -z.px(28f) * p
                        translationY = -z.px(10f) * p
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawBehind {
                        // 28% of the original scene over 72% of a greyscale copy overlaid with the
                        // app's colour: dark areas take the hue, bright areas stay pale.
                        val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        drawImage(b, dstSize = dst, colorFilter = gray)
                        drawRect(tintNow, blendMode = BlendMode.Overlay)
                        drawImage(b, dstSize = dst, alpha = .28f)
                        if (dim < 1f) drawRect(Color.Black.copy(alpha = 1f - dim))
                    },
            )
        }
    }
}

/* ---------- hero, status, toast ---------- */

@Composable
private fun Hero(hero: State<Card?>) {
    val z = LocalDz.current
    AnimatedContent(
        targetState = hero.value,
        contentKey = { it?.key },
        transitionSpec = {
            (fadeIn(tween(550)) + slideInVertically(tween(550, easing = FastOutSlowInEasing)) { z.px(14f).toInt() }) togetherWith
                fadeOut(tween(180))
        },
        label = "hero",
    ) { card ->
        if (card != null) {
            // Points take more room than a meta line, so that card sits a little higher, clear of the row.
            Column(if (card.notes.isNotEmpty()) Modifier.offset(y = -z.d(30)) else Modifier) {
                Box(Modifier.height(z.d(20))) { if (card.label.isNotEmpty()) T(card.label.uppercase(), 14f, 600, .7f, .16f) }
                Box(Modifier.height(z.d(116)).widthIn(max = z.d(1130)), contentAlignment = Alignment.CenterStart) { T(card.title, 74f, 600, spacing = -.025f) }
                if (card.meta.isNotEmpty()) T(card.meta, 26f, 400, .82f)
                if (card.sub.isNotEmpty()) Box(Modifier.padding(top = z.d(6))) { T(card.sub, 20f, 400, .6f) }
                if (card.notes.isNotEmpty()) Points(card.notes)
            }
        }
    }
}

/** A few short points under the hero's title, each after a small dot. They stop short of the row below. */
@Composable
private fun Points(notes: List<String>) {
    val z = LocalDz.current
    Column(Modifier.widthIn(max = z.d(980)), verticalArrangement = Arrangement.spacedBy(z.d(6))) {
        notes.forEach {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(z.d(6)).background(Color.White.copy(alpha = .7f), CircleShape))
                Spacer(Modifier.width(z.d(14)))
                T(it, 22f, 400, .86f)
            }
        }
    }
}

@Composable
private fun Status(s: HomeState, wx: Card?, now: Calendar, req: FocusRequester, onFocus: (Card) -> Unit, m: Modifier) {
    val z = LocalDz.current
    Row(
        m.onGloballyPositioned { s.rects[STATUS] = it.boundsInRoot() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(z.d(16)),
    ) {
        SkyPill(s)
        s.wx?.rainAt?.let {
            Row(
                Modifier
                    .background(Color(120, 170, 255).copy(alpha = .2f), RoundedCornerShape(50))
                    .padding(start = z.d(10), end = z.d(14), top = z.d(6), bottom = z.d(6)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(z.d(6)),
            ) {
                Canvas(Modifier.size(z.d(18))) { drop() }
                T(it, 19f)
            }
        }
        val w = s.wx
        if (w != null && wx != null) {
            var f by remember { mutableStateOf(false) }
            val lift by animateFloatAsState(if (f) 1f else 0f, tween(250), label = "chip")
            var down by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .focusRequester(req)
                    .onFocusChanged { f = it.isFocused; if (it.isFocused) { onFocus(wx); if (Sfx.armed) Sfx.move(9) } }
                    .focusable()
                    .onKeyEvent { e ->
                        if (!isOk(e)) return@onKeyEvent false
                        if (e.type == KeyEventType.KeyDown) down = true
                        else if (e.type == KeyEventType.KeyUp && down) { down = false; s.panel = true; Sfx.select() }
                        true
                    }
                    .graphicsLayer { scaleX = 1 + .08f * lift; scaleY = 1 + .08f * lift }
                    .height(z.d(48))
                    .background(Color.White.copy(alpha = lift), RoundedCornerShape(50))
                    .padding(horizontal = z.d(14)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(z.d(8)),
            ) {
                WeatherIcon(w.code, w.isDay, 28f)
                T("${w.temp}°", 26f, 400, color = lerp(Color.White, Color(0xFF111111), lift))
            }
        }
        T(Prefs.time(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)), 54f, 200)
    }
}

/**
 * The space station about to pass overhead, or passing now. It keeps its own time: during a pass
 * the way to look changes faster than the clock's minute does.
 */
@Composable
private fun SkyPill(s: HomeState) {
    val z = LocalDz.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val t by produceState(System.currentTimeMillis(), s.backTick) {
        while (true) { value = System.currentTimeMillis(); delay(15_000) }
    }
    val here = remember(t) { Place.get(ctx) }
    Heavens.pill(t, here.lat, here.lon, s.wx?.cloud ?: 0)?.let {
        Row(
            Modifier
                .background(Color(255, 207, 138).copy(alpha = .2f), RoundedCornerShape(50))
                .padding(start = z.d(12), end = z.d(16), top = z.d(6), bottom = z.d(6)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(z.d(8)),
        ) {
            Box(Modifier.size(z.d(9)).background(Color(255, 214, 150), CircleShape))
            T(it, 19f)
        }
    }
}

@Composable
private fun Toast(s: HomeState, m: Modifier) {
    val z = LocalDz.current
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(s.toast) {
        val t = s.toast ?: return@LaunchedEffect
        shown = t
        delay(2200)
        s.toast = null
    }
    AnimatedVisibility(s.toast != null, m, enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut()) {
        Box(Modifier.background(Color(0xC8121420), RoundedCornerShape(50)).padding(horizontal = z.d(28), vertical = z.d(14))) {
            T(s.toast ?: shown, 22f, 500)
        }
    }
}

/* ---------- tiles ---------- */

/** What every tile shares. */
private class TileSet(
    val wob: State<Float>, val shine: State<Float>, val onFocus: (Card) -> Unit,
    val onOpen: (Card) -> Unit, val onHold: (Card) -> Unit, val rects: MutableMap<String, Rect>,
)

@Composable
private fun Tile(
    c: Card, app: AppEntry?, w: Float, h: Float, dock: Boolean, req: FocusRequester?, t: TileSet,
    step: Int = 0, moving: Boolean = false, thumb: ImageBitmap? = null,
) {
    val z = LocalDz.current
    val scope = rememberCoroutineScope()
    var f by remember { mutableStateOf(false) }
    // A press only counts if it started here: a release left over from another app must do nothing.
    var down by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf<Job?>(null) }
    val lift by animateFloatAsState(if (f) 1f else 0f, tween(280, easing = FastOutSlowInEasing), label = "lift")
    val shape = RoundedCornerShape(z.d(if (dock) 12 else 16))
    val base = Modifier.size(z.d(w), z.d(h)).zIndex(if (f) 2f else 0f)
    val mod = (if (req != null) base.focusRequester(req) else base)
        .onGloballyPositioned { t.rects[c.key] = it.boundsInRoot() }
        .onFocusChanged { f = it.isFocused; if (it.isFocused) { t.onFocus(c); if (Sfx.armed) Sfx.move(step) } }
        .focusable()
        .onKeyEvent { e ->
            if (!isOk(e)) return@onKeyEvent false
            if (e.type == KeyEventType.KeyDown) {
                if (!down) {
                    down = true
                    hold = scope.launch { delay(520); down = false; t.onHold(c) }
                }
            } else if (e.type == KeyEventType.KeyUp && down) {
                down = false
                hold?.cancel()
                Sfx.select()
                t.onOpen(c)
            }
            true
        }
        .graphicsLayer {
            val sc = 1f + (if (dock) .26f else .10f) * lift
            scaleX = sc; scaleY = sc
            translationY = -z.px(if (moving) 16f else 8f) * lift
            if (dock && lift > 0f && !moving) {
                val k = t.wob.value
                rotationY = (-6f + 12f * k) * lift
                rotationX = (7f - 3f * k) * lift
                cameraDistance = 14f * density
            }
            shadowElevation = z.px(if (dock) 6f + 14f * lift else 8f + 16f * lift)
            this.shape = shape
            clip = false
        }
    Box(mod) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(bgFor(c, app))
                .drawWithContent {
                    drawContent()
                    if (f) {
                        // The light sweep across the selected tile.
                        val p = ((t.shine.value - .3f) / .4f).coerceIn(0f, 1f)
                        if (p > 0f && p < 1f) {
                            val x = size.width * (-.5f + 2f * p)
                            drawRect(
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = .38f), Color.Transparent),
                                    start = Offset(x - size.width * .3f, 0f),
                                    end = Offset(x + size.width * .3f, size.height),
                                ),
                            )
                        }
                    }
                },
        ) {
            TileContent(c, app, h, thumb)
            c.progress?.let { p ->
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = z.d(14), vertical = z.d(12))
                        .fillMaxWidth()
                        .height(z.d(5))
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = .28f)),
                ) { Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(Color.White)) }
            }
        }
        if (moving) Box(Modifier.fillMaxSize().border(z.d(3), Color.White, shape))
    }
}

private fun bgFor(c: Card, app: AppEntry?): Brush = when (c.kind) {
    Kind.PS5 -> Brush.linearGradient(listOf(Color(0xFF0D3A8F), Color(0xFF00266B)))
    Kind.SETTINGS, Kind.ALL -> SolidColor(Color(0xFF2A2F3A))
    Kind.SETUP, Kind.UPDATE -> Brush.linearGradient(listOf(Color(0xFF2B5C93), Color(0xFF16324F)))
    else -> SolidColor(if (app?.banner != null) Color.Black else lerp(app?.hue ?: Color.DarkGray, Color.Black, .45f))
}

@Composable
private fun TileContent(c: Card, app: AppEntry?, h: Float, thumb: ImageBitmap? = null) {
    val z = LocalDz.current
    if (thumb != null) {
        Box(Modifier.fillMaxSize()) {
            Image(thumb, c.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.38f to Color.Transparent, 1f to Color.Black.copy(alpha = .72f))))
            // The app's own art for what you're watching already carries its title.
            if (c.kind != Kind.RESUME) T(
                c.title, 19f, 600,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = z.d(14), end = z.d(12), bottom = z.d(if (c.progress != null) 24 else 11)),
            )
            app?.let { a ->
                val badge = a.banner ?: a.icon
                if (badge != null) {
                    Image(
                        badge, a.label,
                        Modifier.align(Alignment.TopEnd).padding(z.d(9)).height(z.d(24)).aspectRatio(16f / 9f).clip(RoundedCornerShape(z.d(6))),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        return
    }
    when (c.kind) {
        Kind.PS5 -> Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.Center)) { T("PS5", h * .34f, 800, spacing = .02f) }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = z.d(h * .11f), end = z.d(h * .14f))
                    .size(z.d(h * .16f))
                    .background(if (c.on) Color(0xFF5FD39A) else Color.White.copy(alpha = .3f), CircleShape),
            )
        }
        Kind.SETTINGS -> Canvas(Modifier.fillMaxSize()) { gear() }
        Kind.ALL -> Canvas(Modifier.fillMaxSize()) { dots() }
        else -> when {
            // Its own banner is just the word in small type.
            c.pkg == AIRPLAY -> Canvas(Modifier.fillMaxSize()) { airplay() }
            app?.banner != null -> Image(app.banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            app?.icon != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(app.icon, app.label, Modifier.fillMaxHeight(.64f).aspectRatio(1f))
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T(c.title, h * .2f, 600) }
        }
    }
}

/* ---------- tile menu ---------- */

@Composable
private fun Menu(s: HomeState) {
    val m = s.menu ?: return
    val z = LocalDz.current
    val r = s.rects[m.card.key] ?: return
    val width = z.px(226)
    val height = z.px(m.items.size * 48 + 18)
    val x = (r.center.x - width / 2).coerceIn(z.px(16), z.widthPx - width - z.px(16))
    val y = (r.top - height - z.px(12)).coerceAtLeast(z.px(16))
    Column(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(z.d(226))
            .background(Color(0xF0181A24), RoundedCornerShape(z.d(18)))
            .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(z.d(18)))
            .padding(z.d(8)),
    ) {
        m.items.forEachIndexed { i, a ->
            val on = i == s.menuIndex
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(z.d(46))
                    .background(if (on) Color.White else Color.Transparent, RoundedCornerShape(z.d(12)))
                    .padding(horizontal = z.d(14)),
                contentAlignment = Alignment.CenterStart,
            ) { T(a.label, 21f, 500, color = if (on) Color(0xFF111111) else Color.White) }
        }
    }
}

/* ---------- all apps ---------- */

@Composable
private fun AllApps(s: HomeState, act: Actions, t: TileSet) {
    val z = LocalDz.current
    val reqs = remember { mutableMapOf<String, FocusRequester>() }
    val apps = s.shownApps
    Box(Modifier.fillMaxSize().background(Color(0xF0080A10))) {
        Column(Modifier.padding(start = z.d(72), end = z.d(72), top = z.d(48))) {
            T("All apps", 40f, 600)
            Spacer(Modifier.height(z.d(20)))
            LazyVerticalGrid(
                GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(z.d(26)),
                verticalArrangement = Arrangement.spacedBy(z.d(22)),
                contentPadding = PaddingValues(start = z.d(8), end = z.d(8), top = z.d(14), bottom = z.d(80)),
            ) {
                itemsIndexed(apps, key = { _, a -> a.pkg }) { i, a ->
                    Column {
                        Tile(s.allCard(a), a, 164f, 92f, false, reqs.getOrPut(a.pkg) { FocusRequester() }, t, step = 2 + i % 6)
                        Spacer(Modifier.height(z.d(10)))
                        T(a.label, 16f, 400, .78f)
                    }
                }
            }
        }
    }
    // The first app when it opens; after one is hidden, the app that took its place. An app that
    // arrives while it's open leaves the selection alone.
    val was = remember { mutableListOf<String>() }
    val pkgs = apps.map { it.pkg }
    LaunchedEffect(pkgs) {
        val opening = was.isEmpty()
        val gone = was.indexOfFirst { it !in pkgs }
        was.clear(); was.addAll(pkgs)
        if (!opening && gone < 0) return@LaunchedEffect
        val at = if (gone < 0) 0 else gone.coerceAtMost(pkgs.lastIndex)
        withFrameNanos { }
        runCatching { reqs[pkgs.getOrNull(at)]?.requestFocus() }
    }
}

/* ---------- small drawings ---------- */

internal fun DrawScope.u() = size.width / 24f

internal fun DrawScope.sun() {
    val u = u(); val c = Offset(12 * u, 12 * u); val col = Color(0xFFFFD36B)
    drawCircle(col, 5 * u, c)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        drawLine(
            col,
            Offset(c.x + (7.5f * u) * cos(a).toFloat(), c.y + (7.5f * u) * sin(a).toFloat()),
            Offset(c.x + (9.5f * u) * cos(a).toFloat(), c.y + (9.5f * u) * sin(a).toFloat()),
            strokeWidth = 1.8f * u, cap = StrokeCap.Round,
        )
    }
}

internal fun DrawScope.moon() {
    val u = u()
    val full = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(12 * u, 12 * u), 8 * u)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(16.5f * u, 8.5f * u), 7 * u)) }
    drawPath(Path.combine(PathOperation.Difference, full, bite), Color(0xFFE6EBFF))
}

internal fun DrawScope.cloud() {
    val u = u(); val col = Color(0xFFCFD6E4)
    drawCircle(col, 4.5f * u, Offset(8.5f * u, 14.5f * u))
    drawCircle(col, 5.5f * u, Offset(13.5f * u, 11.5f * u))
    drawCircle(col, 4f * u, Offset(17.5f * u, 14.5f * u))
    drawRoundRect(col, Offset(5 * u, 14 * u), Size(16 * u, 5 * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u))
}

internal fun DrawScope.drop() {
    val u = u(); val col = Color(0xFF9CC8FF)
    drawPath(Path().apply { moveTo(12 * u, 3 * u); lineTo(6.6f * u, 13 * u); lineTo(17.4f * u, 13 * u); close() }, col)
    drawCircle(col, 6 * u, Offset(12 * u, 15 * u))
}

private fun DrawScope.gear() {
    val r = size.height
    val c = center
    drawCircle(Color.White, r * .22f, c, style = Stroke(r * .07f))
    drawCircle(Color.White, r * .08f, c, style = Stroke(r * .06f))
    for (i in 0 until 8) rotate(i * 45f, c) {
        drawLine(Color.White, Offset(c.x, c.y - r * .25f), Offset(c.x, c.y - r * .34f), strokeWidth = r * .1f, cap = StrokeCap.Round)
    }
}

private const val AIRPLAY = "com.apple.airplay.amazon.airplay"

/** A screen with a triangle rising into its open bottom edge. */
private fun DrawScope.airplay() {
    val r = size.height
    val c = center
    val w = r * .62f; val h = r * .42f
    val top = c.y - r * .26f
    val stroke = r * .065f
    fun tri(grow: Float) = Path().apply {
        moveTo(c.x, top + h * .62f - grow)
        lineTo(c.x + r * .17f + grow * 1.2f, top + h + r * .17f + grow * .6f)
        lineTo(c.x - r * .17f - grow * 1.2f, top + h + r * .17f + grow * .6f)
        close()
    }
    clipPath(tri(stroke * 1.4f), ClipOp.Difference) {
        drawRoundRect(Color.White, Offset(c.x - w / 2, top), Size(w, h), CornerRadius(r * .06f), style = Stroke(stroke))
    }
    drawPath(tri(0f), Color.White)
}

private fun DrawScope.dots() {
    val r = size.height
    for (i in -1..1) for (j in -1..1) drawCircle(Color.White, r * .045f, Offset(center.x + i * r * .17f, center.y + j * r * .17f))
}
