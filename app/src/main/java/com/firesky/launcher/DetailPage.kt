package com.firesky.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.roundToInt

/** What a title's page can do, in the order its tiles sit. */
enum class DetailAct { PLAY, TRAILER, HIDE }

/** A title's page as it stands: what's known about it and which tile is picked. */
class Detail(val card: Card, val info: Details.Info, val acts: List<DetailAct>, val index: Int = 0) {
    fun at(i: Int) = Detail(card, info, acts, i.coerceIn(0, acts.lastIndex))

    companion object {
        /**
         * Play is always there. A trailer needs TMDB to have one and YouTube to play it; hiding needs
         * the card to be one that can be hidden.
         */
        fun acts(card: Card, info: Details.Info, youtube: Boolean) = listOfNotNull(
            DetailAct.PLAY,
            DetailAct.TRAILER.takeIf { info.trailer != null && youtube },
            DetailAct.HIDE.takeIf { card.hideId != null },
        )
    }
}

private fun isPlay(e: KeyEvent) = e.key == Key.MediaPlayPause || e.key == Key.MediaPlay
private fun isOk(e: KeyEvent) = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter

/**
 * Play/Pause on the home screen. On a card whose title is known it opens that title's page;
 * anywhere else it carries on with whatever you were last watching. Returns false for other keys.
 */
fun playKey(s: HomeState, act: Actions, e: KeyEvent): Boolean {
    if (!isPlay(e)) return false
    if (e.type != KeyEventType.KeyUp || s.allApps) return true
    val page = s.rows.flatten().firstOrNull { it.key == s.current }?.let(s::detailOf)
    val going = s.rows.getOrNull(1)?.firstOrNull { it.kind == Kind.RESUME || it.kind == Kind.KODI }
    when {
        page != null -> { s.detail = page; Sfx.select() }
        going != null -> { Sfx.select(); act.open(going) }
        else -> Sfx.edge()
    }
    return true
}

/** The keys on a title's page: left and right along the tiles, OK to press one, Play to play, Back to close. */
fun detailKey(s: HomeState, act: Actions, d: Detail, e: KeyEvent): Boolean {
    val up = e.type == KeyEventType.KeyUp
    when {
        e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> if (!up) {
            val to = d.at(d.index + if (e.key == Key.DirectionLeft) -1 else 1)
            if (to.index == d.index) Sfx.edge() else { s.detail = to; Sfx.move(to.index + 3) }
        }
        e.key == Key.Back -> if (up) { s.detail = null; Sfx.back() }
        isOk(e) || isPlay(e) -> if (up) {
            Sfx.select()
            when (if (isPlay(e)) DetailAct.PLAY else d.acts[d.index]) {
                // The page closes behind the app, so Home afterwards is the home screen.
                DetailAct.PLAY -> { s.detail = null; act.open(d.card) }
                // The page stays, so Back from the trailer comes back to it.
                DetailAct.TRAILER -> d.info.trailerLink?.let { act.trailer(d.card, it) }
                DetailAct.HIDE -> {
                    val row = s.tonight()
                    val i = row.indexOfFirst { it.key == d.card.key }
                    s.detail = null
                    s.hide(d.card, (row.getOrNull(i + 1) ?: row.getOrNull(i - 1) ?: s.dock().firstOrNull())?.key)
                }
            }
        }
    }
    return true
}

/**
 * The page over the home screen. It fades in while the title's picture rises behind the mountains,
 * the way the day does. The picture starts as the card's own and sharpens once the full one is read.
 */
@Composable
fun DetailLayer(s: HomeState, act: Actions) {
    val d = s.detail
    // The page that's closing keeps its words while it fades.
    var last by remember { mutableStateOf(d) }
    SideEffect { if (d != null) last = d }
    AnimatedVisibility(d != null, enter = fadeIn(tween(260)), exit = fadeOut(tween(200))) {
        val p = d ?: last ?: return@AnimatedVisibility
        val art by produceState(s.thumbs[p.card.key], p.card.key) { act.art(p.card)?.let { value = it } }
        val rise = remember(p.card.key) { Animatable(0f) }
        LaunchedEffect(p.card.key) { rise.animateTo(1f, tween(RISE_MS, easing = FastOutSlowInEasing)) }
        DetailPage(p, art, s.app(p.card.pkg)?.label.orEmpty(), s.linkFor(p.card) != null, s.wx?.sun ?: Sky.DEFAULT_SUN, rise.value, lift = s.live?.lift ?: 0f)
    }
}

private const val RISE_MS = 750
private const val W = 1280f
private const val H = 720f
private val WARM = Color(0xFFFFC27A)

/** Text that wraps, for the description. */
@Composable
private fun Lines(text: String, size: Float, weight: Int, alpha: Float, lines: Int, height: Float, modifier: Modifier = Modifier) {
    val z = LocalDz.current
    BasicText(
        text, modifier,
        style = TextStyle(
            color = Color.White.copy(alpha = alpha), fontSize = z.sp(size), fontFamily = Outfit,
            fontWeight = FontWeight(weight), lineHeight = z.sp(size * height),
        ),
        maxLines = lines, overflow = TextOverflow.Ellipsis,
    )
}

private fun minuteNow() = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) + it.get(Calendar.SECOND) / 60f }

/**
 * The same place as the home screen, with the title's picture for a sky. The land is the land its
 * card had, in the same colour, and it stands in front of the picture as it does in front of the
 * real sky; where the hour has water, the picture is mirrored in it. [rise] brings the picture up
 * from behind the ridges, 0 to 1.
 */
@Composable
private fun TitleSky(art: ImageBitmap?, land: String?, hue: Color, sun: Sun, rise: Float, minute: Float?, lift: Float) {
    // The hour's own light, washed with the title's colour exactly as the backdrop washes it for the card.
    val v = remember(hue, sun, minute) {
        FloatArray(Sky.N).also { Sky.pack(minute ?: minuteNow(), sun, it); Sky.tint(it, floatArrayOf(hue.red * 255, hue.green * 255, hue.blue * 255)) }
    }
    // The backdrop's camera sets the land a little up or down the screen; the page's land sits where the backdrop's does.
    val hz = H * v[Sky.HZ] + lift
    val ridges = remember(land, hz) {
        val shape = Sky.shape(land ?: "all")
        List(4) { i ->
            val base = hz - (3 - i) * 30
            Path().apply {
                moveTo(0f, H)
                for (j in 0 until POINTS) lineTo(j / (POINTS - 1f) * W, base - shape[i][j])
                lineTo(W, H); close()
            }
        }
    }
    fun col(o: Int, a: Float = 1f) = Color(v[o] / 255f, v[o + 1] / 255f, v[o + 2] / 255f, a)
    Canvas(Modifier.fillMaxSize()) {
        fun DrawScope.scene() {
            drawRect(Brush.verticalGradient(0f to col(0), .55f * hz / H to col(3), hz / H to col(6), startY = 0f, endY = H), size = Size(W, H))
            art?.let {
                drawImage(it, dstOffset = IntOffset(0, ((1 - rise) * 56).roundToInt()), dstSize = IntSize(W.toInt(), H.toInt()), alpha = rise)
                // The picture gives way to the hour's own sky at the top, where the readings sit.
                drawRect(Brush.verticalGradient(0f to col(0, .92f), 1f to col(0, 0f), startY = 0f, endY = 190f), size = Size(W, 190f))
            }
            for (i in 0 until 4) {
                drawPath(ridges[i], col(9 + i * 3))
                if (i < 3) {
                    val y0 = hz - (3 - i) * 30 - (200f - i * 38)
                    drawRect(Brush.verticalGradient(0f to col(21, 0f), 1f to col(21, v[24]), startY = y0, endY = H), Offset(0f, y0), Size(W, H - y0))
                }
            }
        }
        scale(size.width / W, size.height / H, pivot = Offset.Zero) {
            val water = v[Sky.WATER] > .5f
            clipRect(0f, 0f, W, if (water) hz else H) { scene() }
            if (water) clipRect(0f, hz, W, H) {
                scale(1f, -1f, pivot = Offset(W / 2, hz)) { scene() }
                drawRect(Color(0xFF040810).copy(alpha = .4f), Offset(0f, hz), Size(W, H - hz))
            }
            // The same shades the backdrop paints in behind the home screen's words.
            drawRect(Brush.radialGradient(.35f / .95f to Color.Transparent, 1f to Color.Black.copy(alpha = .45f), center = Offset(W / 2, H / 2), radius = H * .95f), size = Size(W, H))
            drawRect(Brush.verticalGradient(.42f to Color.Transparent, 1f to Color.Black.copy(alpha = .8f), startY = 0f, endY = H), size = Size(W, H))
            drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .68f), .7f to Color.Transparent, startX = 0f, endX = W), size = Size(W, H))
        }
    }
}

/**
 * A title's own page. It is the home screen leaning in rather than a screen of its own: the title
 * stays exactly where the home screen had it, its picture becomes the sky, its score hangs where
 * the clock was, as a moon as full as the score is high, and the dock becomes what you can do.
 *
 * [service] is the app it plays in. [direct] is whether Play lands on the title itself; without a
 * link of its own the tile can only open the app, and says so. [minute] fixes the hour's light,
 * for tests; left out, it's now.
 */
@Composable
fun DetailPage(d: Detail, art: ImageBitmap?, service: String, direct: Boolean, sun: Sun = Sky.DEFAULT_SUN, rise: Float = 1f, minute: Float? = null, lift: Float = 0f) {
    val z = LocalDz.current
    val i = d.info
    val c = d.card
    val land = when (c.kind) { Kind.PS5 -> "ps5"; Kind.SETTINGS -> "settings"; Kind.ALL -> "all"; else -> c.pkg }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        TitleSky(art, land, c.hue, sun, rise, minute, lift)

        Details.score(i)?.let { score ->
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = z.d(42), end = z.d(60)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(z.d(14)),
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    T("TMDB", 14f, 600, .7f, .16f)
                    T(Details.votes(i), 17f, 400, .7f)
                }
                Canvas(Modifier.size(z.d(40))) { moonDisc(center, size.minDimension / 2, (i.score / 10).toFloat().coerceIn(0f, 1f), true) }
                T(score, 54f, 200)
            }
        }

        // Where the home screen has it, to the pixel, so opening the page moves nothing you were reading.
        Column(Modifier.offset(z.d(72), z.d(176))) {
            Box(Modifier.height(z.d(20))) { if (c.label.isNotEmpty()) T(c.label.uppercase(), 14f, 600, .7f, .16f) }
            Box(Modifier.height(z.d(116)).widthIn(max = z.d(1130)), contentAlignment = Alignment.CenterStart) { T(i.title, 74f, 600, spacing = -.025f) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(z.d(14))) {
                if (i.rated.isNotEmpty()) {
                    Box(Modifier.border(z.d(1.3f), Color.White.copy(alpha = .5f), RoundedCornerShape(z.d(5))).padding(horizontal = z.d(8), vertical = z.d(1))) {
                        T(i.rated, 19f, 500, .86f)
                    }
                }
                T(Details.facts(i), 26f, 400, .82f)
            }
            Column(Modifier.width(z.d(700)).graphicsLayer { alpha = rise; translationY = z.px(14f) * (1 - rise) }) {
                if (i.overview.isNotEmpty()) {
                    Spacer(Modifier.height(z.d(26)))
                    Lines(i.overview, 22f, 400, .9f, 3, 1.38f)
                }
                // Partway through a show, the card already knows where you are and when this episode ends.
                val going = c.meta.takeIf { c.kind == Kind.RESUME && it.isNotEmpty() && it != service }
                    ?.let { m -> listOf(m, c.sub.substringBefore(" · ")).filter { it.isNotEmpty() }.joinToString(" · ") }
                (going ?: Details.tonight(i, System.currentTimeMillis()))?.let {
                    Spacer(Modifier.height(z.d(16)))
                    T(it, 20f, 500, color = WARM)
                }
                val people = listOfNotNull(i.cast.takeIf { it.isNotEmpty() }?.let { "With ${it.joinToString(", ")}" }, i.by.ifEmpty { null })
                if (people.isNotEmpty()) {
                    Spacer(Modifier.height(z.d(6)))
                    people.forEach { T(it, 19f, 400, .6f) }
                }
            }
        }

        // The dock, become what you can do with this title.
        val bar = RoundedCornerShape(z.d(28))
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = z.d(14), bottom = z.d(26))
                .background(Color.White.copy(alpha = .10f), bar).border(1.dp, Color.White.copy(alpha = .15f), bar)
                .padding(z.d(16)),
            horizontalArrangement = Arrangement.spacedBy(z.d(12)), verticalAlignment = Alignment.CenterVertically,
        ) {
            d.acts.forEachIndexed { n, act ->
                ActTile(
                    when (act) {
                        DetailAct.PLAY -> if (direct) "▶  Play on $service" else "▶  Open $service"
                        DetailAct.TRAILER -> "Trailer"
                        DetailAct.HIDE -> "Hide this"
                    },
                    n == d.index,
                )
            }
        }
    }
}

@Composable
private fun ActTile(label: String, picked: Boolean) {
    val z = LocalDz.current
    // The picked tile turns white and stays its size, so the bar's edges stay even around it.
    Box(
        Modifier
            .background(if (picked) Color.White else Color.White.copy(alpha = .09f), RoundedCornerShape(z.d(14)))
            .height(z.d(56)).padding(horizontal = z.d(28)),
        contentAlignment = Alignment.Center,
    ) {
        T(label, 21f, 600, color = if (picked) Color(0xFF0B0E15) else Color.White)
    }
}
