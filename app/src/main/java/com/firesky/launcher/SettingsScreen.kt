package com.firesky.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.util.Locale

/** Where the steps for the one-time setup from a computer are, for the QR code on the TV. */
const val SETUP_PAGE = "https://github.com/jeffersonaaron25/firesky-launcher#install"

/**
 * A line of FireSky's settings. A choice cycles through [options] (stored values, "" for Auto) with
 * left, right or OK; an action runs on OK.
 */
class SettingRow(
    val label: String,
    val value: String,
    val hint: String,
    val key: String? = null,
    val options: List<String> = emptyList(),
    val run: (() -> Unit)? = null,
)

/** Countries offered for the streaming catalogue, after Auto. */
private val REGIONS = listOf("US", "GB", "CA", "AU", "NZ", "IE", "IN", "DE", "FR", "ES", "IT", "NL", "SE", "NO", "DK", "FI", "BR", "MX", "JP", "KR", "SG", "ZA", "AE")

private fun countryName(code: String) = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifEmpty { code }

fun settingRows(s: HomeState, act: Actions): List<SettingRow> {
    s.settingsTick
    fun auto(v: String) = "Auto · $v"
    val region = Prefs.chosen(Prefs.REGION).uppercase(Locale.ROOT)
    val ps5 = Prefs.chosen(Prefs.PS5).toIntOrNull() ?: 0
    val version = when {
        !BuildConfig.RELEASE -> "Your own build"
        s.update != null -> "${s.update!!.version} is ready · OK to install"
        else -> "${BuildConfig.VERSION_NAME} · up to date"
    }
    return listOf(
        SettingRow(
            "Temperature",
            when (Prefs.chosen(Prefs.TEMPERATURE)) { "celsius", "c" -> "°C"; "fahrenheit", "f" -> "°F"; else -> auto(if (Prefs.autoFahrenheit) "°F" else "°C") },
            "Auto follows your TV's country.", Prefs.TEMPERATURE, listOf("", "celsius", "fahrenheit"),
        ),
        SettingRow(
            "Wind",
            when (Prefs.chosen(Prefs.WIND)) { "mph" -> "mph"; "kmh", "km/h" -> "km/h"; else -> auto(if (Prefs.autoMph) "mph" else "km/h") },
            "Auto follows your TV's country.", Prefs.WIND, listOf("", "mph", "kmh"),
        ),
        SettingRow(
            "Clock",
            when (Prefs.chosen(Prefs.CLOCK)) { "12" -> "12-hour"; "24" -> "24-hour"; else -> auto(if (Prefs.system24) "24-hour" else "12-hour") },
            "Auto follows your TV's own setting.", Prefs.CLOCK, listOf("", "12", "24"),
        ),
        SettingRow(
            "Streaming country",
            if (region.isEmpty()) auto(countryName(Prefs.region)) else countryName(region),
            "Where your subscriptions are. New and Trending come from its catalogue.", Prefs.REGION, listOf("") + REGIONS,
        ),
        SettingRow(
            "PlayStation tile",
            if (ps5 > 0) "Always · HDMI $ps5" else "When it's plugged in",
            "Keep a PlayStation tile in the dock, or show one only when the TV sees it.", Prefs.PS5, listOf("0", "1", "2", "3", "4"),
        ),
        SettingRow(
            "While you watch",
            when (Prefs.chosen(Prefs.WATCH)) { "hold" -> "Only when ≡ is held"; "off" -> "Off"; else -> "Glance, clock and notes" },
            "Hold ≡ in any app for the time, the weather and your apps. A clock shows when you pause, and a note when the space station passes over or rain is near.",
            Prefs.WATCH, listOf("", "hold", "off"),
        ),
        SettingRow(
            "New and Trending", if (Tmdb.on) "On" else "Off",
            "What's new on your apps, from TMDB. Add a free key from your phone.", run = { act.phone(true) },
        ),
        SettingRow(
            "Home button", if (s.homeReady) "Opens FireSky" else "Needs a one-time step",
            if (s.homeReady) "Home and waking the TV come here." else "One command from a computer lets Home open FireSky.", run = { s.guide = true },
        ),
        SettingRow(
            "Hidden apps", if (s.hiddenApps.isEmpty()) "None" else "${s.hiddenApps.size} · OK to show them again",
            "Hide an app by holding OK on it in All apps.", run = { if (s.hiddenApps.isNotEmpty()) { s.showHiddenApps(); s.toast = "Hidden apps are back in All apps" } },
        ),
        SettingRow("Version", version, "FireSky looks for updates by itself.", run = { act.update() }),
    )
}

/** Keys while the settings are open: up and down pick a line, left, right and OK change it, Back closes. */
fun settingsKey(s: HomeState, act: Actions, e: KeyEvent): Boolean {
    val rows = settingRows(s, act)
    val down = e.type == KeyEventType.KeyDown
    val up = e.type == KeyEventType.KeyUp
    val row = rows.getOrNull(s.settingsIndex) ?: rows.first()
    fun turn(by: Int) {
        val k = row.key ?: return
        val now = row.options.indexOfFirst { it.equals(Prefs.chosen(k), ignoreCase = true) }.coerceAtLeast(0)
        Prefs.set(k, row.options[(now + by + row.options.size) % row.options.size])
        s.settingsTick++
        Sfx.move(3)
        act.changed(k)
    }
    when (e.key) {
        Key.DirectionUp, Key.DirectionDown -> if (down) {
            val i = s.settingsIndex + if (e.key == Key.DirectionUp) -1 else 1
            if (i in rows.indices) { s.settingsIndex = i; Sfx.move(i + 2) } else Sfx.edge()
        }
        Key.DirectionLeft, Key.DirectionRight -> if (down) { if (row.key != null) turn(if (e.key == Key.DirectionLeft) -1 else 1) else Sfx.edge() }
        Key.Back, Key.Menu -> if (up) { s.settings = false; Sfx.back() }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (up) { if (row.key != null) turn(1) else { Sfx.select(); row.run?.invoke() } }
        else -> return false
    }
    return true
}

@Composable
fun SettingsScreen(s: HomeState, act: Actions) {
    val z = LocalDz.current
    AnimatedVisibility(s.settings, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
        val rows = settingRows(s, act)
        val at = s.settingsIndex.coerceIn(rows.indices)
        Box(Modifier.fillMaxSize().background(Color(0xFF080A11))) {
            Row(Modifier.fillMaxSize().padding(start = z.d(72), end = z.d(72), top = z.d(56)), horizontalArrangement = Arrangement.spacedBy(z.d(56))) {
                Column(Modifier.width(z.d(330))) {
                    T("FIRESKY", 16f, 600, .6f, spacing = .14f)
                    T("Settings", 46f, 600)
                    Spacer(Modifier.height(z.d(26)))
                    // Wrapped by hand: T keeps to one line.
                    hintLines(rows[at].hint).forEach { T(it, 20f, 400, .72f) }
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(z.d(6))) {
                    rows.forEachIndexed { i, r ->
                        val on = i == at
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(z.d(60))
                                .background(if (on) Color.White else Color.White.copy(alpha = .05f), RoundedCornerShape(z.d(14)))
                                .padding(horizontal = z.d(22)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val ink = if (on) Color(0xFF111111) else Color.White
                            T(r.label, 22f, 500, color = ink)
                            T(if (on && r.key != null) "‹  ${r.value}  ›" else r.value, 21f, 400, if (on) 1f else .7f, color = ink)
                        }
                    }
                }
            }
        }
    }
    QrPage(
        s.phoneUrl != null, "New and Trending",
        if (s.phoneUrl.isNullOrEmpty()) listOf("FireSky couldn't open the page.", "Is the TV connected to your home network?")
        else listOf("Scan this with your phone's camera.", "The page explains how to get a free TMDB key,", "and saves it to the TV."),
        s.phoneUrl?.takeIf { it.isNotEmpty() },
    )
    QrPage(
        s.guide, "Let Home open FireSky",
        listOf(
            "Fire TV lets an app take over the Home button only",
            "after a one-time step from a computer on this network.",
            "",
            "1.  Settings › My Fire TV › Developer options › ADB debugging: On",
            "2.  On the computer, open the page and run its one command",
            "3.  When the TV asks, choose Always allow",
            "",
            Phone.lanAddress()?.let { "This TV's address: $it" } ?: "",
        ),
        SETUP_PAGE,
    )
}

/** Splits a hint into lines that fit the left column. */
private fun hintLines(text: String, width: Int = 30): List<String> {
    val out = mutableListOf<String>()
    var line = ""
    for (w in text.split(' ')) {
        line = if (line.isEmpty()) w else if (line.length + 1 + w.length <= width) "$line $w" else { out += line; w }
    }
    if (line.isNotEmpty()) out += line
    return out
}

@Composable
private fun QrPage(shown: Boolean, title: String, lines: List<String>, url: String?) {
    val z = LocalDz.current
    AnimatedVisibility(shown, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
        val code = remember(url) { url?.let(Phone::qr) }
        Box(Modifier.fillMaxSize().background(Color(0xFF070910))) {
            Row(Modifier.fillMaxSize().padding(start = z.d(90), end = z.d(90)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(z.d(64))) {
                Column(Modifier.weight(1f)) {
                    T("FIRESKY", 16f, 600, .6f, spacing = .14f)
                    T(title, 42f, 600)
                    Spacer(Modifier.height(z.d(22)))
                    lines.forEach { if (it.isEmpty()) Spacer(Modifier.height(z.d(14))) else T(it, 21f, 400, .82f) }
                    Spacer(Modifier.height(z.d(26)))
                    url?.let { T(it.removePrefix("https://"), 17f, 400, .5f) }
                    Spacer(Modifier.height(z.d(10)))
                    T("Back to close", 17f, 400, .5f)
                }
                code?.let {
                    Box(Modifier.size(z.d(300)).background(Color.White, RoundedCornerShape(z.d(18))).padding(z.d(14))) {
                        Image(it, "QR code", Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
                    }
                }
            }
        }
    }
}
