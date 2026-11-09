package com.firesky.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val RAIN = Color(0xFF7FB4FF)
private val WARM = Color(0xFFFFC27A)
private val SUN_COL = Color(0xFFFFD36B)

/** The icon for a weather code. */
@Composable
fun WeatherIcon(code: Int, day: Boolean, size: Float) {
    val z = LocalDz.current
    Canvas(Modifier.size(z.d(size))) {
        when (Sky.kindOf(code)) {
            Sky.CLEAR -> if (day) sun() else moon()
            Sky.RAIN -> { cloud(); rainLines() }
            Sky.SNOW -> { cloud(); flakes() }
            Sky.FOG -> fogLines()
            else -> cloud()
        }
    }
}

private fun DrawScope.rainLines() {
    val u = u()
    for (x in listOf(8f, 12.5f, 17f)) drawLine(RAIN, Offset(x * u, 20f * u), Offset((x - 1) * u, 23f * u), strokeWidth = 2 * u, cap = StrokeCap.Round)
}

private fun DrawScope.flakes() {
    val u = u()
    for (x in listOf(8f, 12.5f, 17f)) drawCircle(Color.White, 1.4f * u, Offset(x * u, 21.5f * u))
}

private fun DrawScope.fogLines() {
    val u = u(); val c = Color(0xFFCFD6E4)
    drawLine(c, Offset(4 * u, 8 * u), Offset(20 * u, 8 * u), strokeWidth = 2.2f * u, cap = StrokeCap.Round)
    drawLine(c, Offset(6 * u, 12.5f * u), Offset(20 * u, 12.5f * u), strokeWidth = 2.2f * u, cap = StrokeCap.Round)
    drawLine(c, Offset(4 * u, 17 * u), Offset(17 * u, 17 * u), strokeWidth = 2.2f * u, cap = StrokeCap.Round)
}

private fun clock(min: Int): String {
    val h = min / 60
    return Prefs.time(h, min % 60) + if (Prefs.clock24) "" else if (h < 12) " am" else " pm"
}

private fun hourLabel(h: Int, first: Boolean) = if (first) "Now" else Weather.hourName(h)

/** The forecast, over a frosted copy of the backdrop. Text stays at couch-readable sizes. */
@Composable
fun Panel(s: HomeState) {
    val z = LocalDz.current
    var glass by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(s.panel) { if (s.panel) glass = s.live?.glass()?.asImageBitmap() }
    AnimatedVisibility(
        s.panel && s.wx != null,
        enter = fadeIn(tween(350)) + slideInVertically(tween(350, easing = FastOutSlowInEasing)) { z.px(14f).toInt() },
        exit = fadeOut(tween(250)),
    ) {
        val w = s.wx ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0D15))) {
            // A 160-pixel picture stretched to the screen is already soft, which is the frost.
            glass?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = .55f) }
            Box(Modifier.fillMaxSize().background(Color(0xA8070A11)))
            Row(
                Modifier.fillMaxSize().padding(start = z.d(64), end = z.d(64), top = z.d(50), bottom = z.d(44)),
                horizontalArrangement = Arrangement.spacedBy(z.d(44)),
            ) {
                Column(Modifier.width(z.d(400))) {
                    T("${w.temp}°", 150f, 200, spacing = -.04f)
                    T(w.condition, 32f, 500)
                    Spacer(Modifier.height(z.d(6)))
                    T("Feels ${w.feels}° · Wind ${w.wind} ${Prefs.windUnit}", 22f, 400, .85f)
                    Spacer(Modifier.height(z.d(40)))
                    SunArc(w.sun)
                    Spacer(Modifier.height(z.d(6)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        T(clock(w.sun.rise), 19f, 400, .8f)
                        T(clock(w.sun.set), 19f, 400, .8f)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(z.d(22))) {
                    // With no rain coming the chart would be an empty line, so the words say it alone.
                    Section(w.rainLine) { if (w.rainSoon) RainBars(w.rain) }
                    Section("Next 12 hours") { Hours(w) }
                    Section("This week") { Days(w) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, body: @Composable () -> Unit) {
    val z = LocalDz.current
    Column {
        T(title.uppercase(), 17f, 500, .8f, .08f)
        Spacer(Modifier.height(z.d(8)))
        body()
    }
}

@Composable
private fun SunArc(sun: Sun) {
    val z = LocalDz.current
    Canvas(Modifier.fillMaxWidth().height(z.d(118))) {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val t = (m - sun.rise).toFloat() / (sun.set - sun.rise)
        val rx = size.width / 2 - 2; val ry = size.height * .85f; val cx = size.width / 2; val base = size.height * .95f
        val box = Rect(cx - rx, base - ry, cx + rx, base + ry)
        val line = Color.White.copy(alpha = .2f)
        drawArc(line, 180f, 180f, false, box.topLeft, box.size, style = Stroke(2f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 12f))))
        drawLine(line, Offset(0f, base), Offset(size.width, base))
        if (t in 0f..1f) {
            drawArc(SUN_COL, 180f, 180f * t, false, box.topLeft, box.size, style = Stroke(z.px(2.5f), cap = StrokeCap.Round))
            val a = PI * t
            drawCircle(SUN_COL, z.px(8f), Offset(cx - rx * cos(a).toFloat(), base - ry * sin(a).toFloat()))
        }
    }
}

@Composable
private fun RainBars(rain: List<Double>) {
    val z = LocalDz.current
    val top = maxOf(.5, rain.maxOrNull() ?: 0.0)
    Column {
        Canvas(Modifier.fillMaxWidth().height(z.d(62))) {
            val gap = z.px(2f)
            val w = (size.width - gap * (rain.size - 1)) / rain.size.coerceAtLeast(1)
            rain.forEachIndexed { i, v ->
                val h = (v / top).toFloat() * size.height
                if (h > 0) drawRoundRect(RAIN, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(z.px(4f)))
            }
            drawLine(Color.White.copy(alpha = .2f), Offset(0f, size.height), Offset(size.width, size.height))
        }
        Spacer(Modifier.height(z.d(5)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("Now", 18f, 400, .82f); T("1 h", 18f, 400, .82f); T("2 h", 18f, 400, .82f)
        }
    }
}

@Composable
private fun Hours(w: Weather.Wx) {
    val z = LocalDz.current
    Row(Modifier.fillMaxWidth()) {
        w.hours.forEachIndexed { i, h ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(z.d(3))) {
                T(hourLabel(h.hour, i == 0), 18f, 400, .82f)
                // "Now" is what it's doing now, not the forecast for this hour.
                if (i == 0) WeatherIcon(w.code, w.isDay, 27f) else WeatherIcon(h.code, h.hour in 7..17, 27f)
                T("${if (i == 0) w.temp else h.temp}°", 20f, 500)
                Box(Modifier.height(z.d(19))) { if (h.rainChance >= 30) T("${h.rainChance}%", 17f, color = RAIN) }
            }
        }
    }
}

@Composable
private fun Days(w: Weather.Wx) {
    val z = LocalDz.current
    val low = w.days.minOfOrNull { it.low } ?: 0
    val high = w.days.maxOfOrNull { it.high } ?: 1
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val name = remember { SimpleDateFormat("EEE", Locale.US) }
    fun at(v: Int) = (v - low).toFloat() / (high - low).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(z.d(3))) {
        w.days.forEachIndexed { i, d ->
            Row(Modifier.height(z.d(30)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(z.d(10))) {
                Box(Modifier.width(z.d(82))) { T(if (i == 0) "Today" else fmt.parse(d.date)?.let(name::format).orEmpty(), 19f) }
                Box(Modifier.width(z.d(34))) { WeatherIcon(d.code, true, 25f) }
                Box(Modifier.width(z.d(50))) { if (d.rainChance >= 30) T("${d.rainChance}%", 17f, color = RAIN) }
                Box(Modifier.width(z.d(38)), contentAlignment = Alignment.CenterEnd) { T("${d.low}°", 19f, 400, .8f) }
                Canvas(Modifier.weight(1f).height(z.d(14))) {
                    val y = size.height / 2; val h = z.px(6f)
                    drawRoundRect(Color.White.copy(alpha = .13f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
                    drawRoundRect(WARM, Offset(size.width * at(d.low), y - h / 2), Size(size.width * (at(d.high) - at(d.low)), h), CornerRadius(h / 2))
                    if (i == 0) {
                        drawCircle(Color(0xFF15181F), z.px(9f), Offset(size.width * at(w.temp).coerceIn(0f, 1f), y))
                        drawCircle(Color.White, z.px(7f), Offset(size.width * at(w.temp).coerceIn(0f, 1f), y))
                    }
                }
                Box(Modifier.width(z.d(38))) { T("${d.high}°", 19f) }
            }
        }
    }
}
