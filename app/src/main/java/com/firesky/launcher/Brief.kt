package com.firesky.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * The first time the TV wakes each day, and again the first time after sunset, the home screen holds
 * back and says what the day is: the date, the weather, the light, tonight's sky, and anything new to
 * watch. It stays until a key is pressed, with "Go to home" picked, so there's time to read it.
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

    /** Wakes the home screen as if the TV had just woken, so the brief can be seen at once from adb. */
    @Volatile var wake: (() -> Unit)? = null
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

    /** For trying it out over adb: today's briefs not yet shown, so the next wake has one. */
    fun again(ctx: Context) = prefs(ctx).edit().remove("day").remove("evening").apply()

    /**
     * Whether a wake now gets a brief: the day's first, or the first after sunset. One brief at a
     * time: a day whose first wake is after sunset has only the evening's.
     */
    internal fun wanted(lastDay: Int, lastEvening: Int, day: Int, minute: Int, sunset: Int): Boolean =
        lastDay != day || (minute >= sunset && lastEvening != day)

    /** The brief, if this is the day's first wake or the first after sunset, and it's wanted. Asking counts as showing it. */
    fun due(ctx: Context, s: HomeState, now: Calendar = Calendar.getInstance()): Text? {
        if (!Prefs.brief) return null
        val day = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val sunset = s.wx?.sun?.set ?: Sky.DEFAULT_SUN.set
        val p = prefs(ctx)
        if (!wanted(p.getInt("day", 0), p.getInt("evening", 0), day, minute, sunset)) return null
        p.edit().putInt("day", day).apply { if (minute >= sunset) putInt("evening", day) }.apply()
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
        // Picked, as the remote's OK will press it: any key goes home.
        Spacer(Modifier.height(z.d(40)))
        Box(
            Modifier.background(Color.White, RoundedCornerShape(z.d(14))).height(z.d(56)).padding(horizontal = z.d(28)),
            contentAlignment = Alignment.Center,
        ) { T("Go to home", 21f, 600, color = Color(0xFF0B0E15)) }
    }
}
