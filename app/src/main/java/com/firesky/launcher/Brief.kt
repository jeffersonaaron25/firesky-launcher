package com.firesky.launcher

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The first time the TV wakes each day, the home screen holds back for a few seconds and says what
 * the day is: the date, the weather, the light, tonight's sky, and anything new to watch.
 */
object Brief {
    class Text(
        val label: String, val date: String,
        /** Null when the forecast is too old to repeat. */
        val weather: String?,
        val light: String, val notes: List<Almanac.Note>,
        /** Something new for you today, or null. */
        val extra: String?,
    )

    /** How long it stays once the screen has risen, unless a key is pressed first. */
    const val SHOWN_MS = 6000L
    /** A forecast older than this isn't the day's weather any more. */
    private const val OLD_MS = 3 * 3_600_000L

    private fun lower(s: String) = s.replaceFirstChar { it.lowercase(Locale.getDefault()) }
    private fun clock(min: Int) = Prefs.timeOfDay(min / 60, min % 60)

    /** The words, from the forecast and what's new. [w] is null when there's no forecast fit to show. */
    fun text(now: Calendar, w: Weather.Wx?, sun: Sun, notes: List<Almanac.Note>, fresh: Card?): Text {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = hour * 60 + now.get(Calendar.MINUTE)
        val evening = hour >= 17 || minute > sun.set
        val label = when { evening -> "This evening"; hour < 12 -> "Good morning"; else -> "Good afternoon" }
        val weather = w?.let {
            val today = it.days.firstOrNull()
            val range = if (evening) it.hours.minOfOrNull { h -> h.temp }?.let { low -> "down to $low° overnight" }
                else today?.let { d -> "high ${d.high}°, low ${d.low}°" }
            listOfNotNull("${it.condition}, ${it.temp}° now", range, lower(it.rainLine)).joinToString(" · ")
        }
        val light = when {
            evening -> "Sunrise ${clock(sun.rise)}"
            minute < sun.rise -> "Sunrise ${clock(sun.rise)} · sunset ${clock(sun.set)}"
            else -> "Sunset ${clock(sun.set)}" + (w?.more?.daylight?.let { " · ${it / 60} h ${it % 60} min of daylight" } ?: "")
        }
        val extra = fresh?.let { "${it.label} of ${it.title}" + if ("today" in it.meta) " today" else "" }
        return Text(label, SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now.time), weather, light, if (w == null) emptyList() else notes, extra)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("brief", Context.MODE_PRIVATE)

    /** The brief, if this is the day's first wake and it's wanted. Asking counts as showing it. */
    fun due(ctx: Context, s: HomeState, now: Calendar = Calendar.getInstance()): Text? {
        if (!Prefs.brief) return null
        val day = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)
        if (prefs(ctx).getInt("day", 0) == day) return null
        prefs(ctx).edit().putInt("day", day).apply()
        val w = s.wx?.takeIf { Weather.age(ctx) < OLD_MS }
        val here = Place.get(ctx)
        val notes = w?.let { runCatching { Almanac.tonight(it, now.timeInMillis, here.lat, here.lon) }.getOrNull() }.orEmpty()
        val fresh = runCatching { s.tonight().firstOrNull { it.key.startsWith("t-new-") } }.getOrNull()
        return text(now, w, s.wx?.sun ?: Sky.DEFAULT_SUN, notes, fresh)
    }
}

private val WARM = Color(0xFFFFC27A)

/** Laid out where the home screen names a title, in the same type, so the home screen can take its place without a jolt. */
@Composable
fun BriefView(b: Brief.Text) {
    val z = LocalDz.current
    Column {
        Box(Modifier.height(z.d(20))) { T(b.label.uppercase(), 14f, 600, .7f, .16f) }
        Box(Modifier.height(z.d(116)).widthIn(max = z.d(1130)), contentAlignment = Alignment.CenterStart) { T(b.date, 74f, 600, spacing = -.025f) }
        b.weather?.let { T(it, 26f, 400, .85f) }
        Spacer(Modifier.height(z.d(6)))
        T(b.light, 20f, 400, .6f)
        if (b.notes.isNotEmpty()) {
            Spacer(Modifier.height(z.d(44)))
            Row(horizontalArrangement = Arrangement.spacedBy(z.d(24))) {
                b.notes.forEach { n ->
                    Column(Modifier.width(z.d(276))) {
                        T(n.label.uppercase(), 13f, 600, .65f, .14f)
                        Spacer(Modifier.height(z.d(4)))
                        T(n.main, 21f, 500)
                        T(n.sub, 17f, 400, .7f)
                    }
                }
            }
        }
        b.extra?.let {
            Spacer(Modifier.height(z.d(34)))
            T(it, 20f, 500, color = WARM)
        }
    }
}
