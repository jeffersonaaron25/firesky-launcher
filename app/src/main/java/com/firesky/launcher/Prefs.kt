package com.firesky.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Your choices. What you pick in FireSky's settings on the TV comes first; a build of your own can
 * set defaults in local.properties (local.properties.example lists them). Anything left on Auto
 * follows the TV: its country decides the units and which streaming catalogue to show, and its own
 * setting decides a 12 or 24-hour clock.
 */
object Prefs {
    private fun list(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** The TV's own country, as set on the TV. */
    val country get() = Locale.getDefault().country.uppercase(Locale.ROOT)

    @Volatile private var store: SharedPreferences? = null

    fun init(c: Context) {
        store = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /** Back to the build's defaults alone, for tests. */
    fun forget() { store = null }

    /** What was picked on the TV ("" for Auto), or else the build's default. */
    private fun pick(key: String, built: String) = store?.getString(key, null) ?: built

    /** A choice made on the TV. "" means Auto. */
    fun set(key: String, value: String) { store?.edit()?.putString(key, value)?.apply() }

    /** The raw choice for [key], for the settings screen: "" is Auto. */
    fun chosen(key: String): String = when (key) {
        TEMPERATURE -> pick(key, BuildConfig.TEMPERATURE)
        WIND -> pick(key, BuildConfig.WIND)
        CLOCK -> pick(key, BuildConfig.CLOCK)
        REGION -> pick(key, BuildConfig.REGION)
        PS5 -> pick(key, BuildConfig.PS5_PORT.toString())
        else -> store?.getString(key, null) ?: ""
    }.trim().lowercase(Locale.ROOT)

    const val TEMPERATURE = "temperature"
    const val WIND = "wind"
    const val CLOCK = "clock"
    const val REGION = "region"
    const val PS5 = "ps5.port"
    const val TMDB = "tmdb.key"
    /** What FireSky does over other apps: "" for everything, "hold" for only the glance when ≡ is held, "off". */
    const val WATCH = "watch"

    /** The land under the sky: "" for mountains, a place from [Places.KINDS], or shuffled by app ("shuffle") or by day ("daily"). */
    const val PLACE = "place"

    /** The home screen moving gently when left alone: "" for on, "off". */
    const val MOTION = "motion"
    val motion get() = chosen(MOTION) != "off"

    /** The day's brief on the first wake: "" for on, "off". */
    const val BRIEF = "brief"
    val brief get() = chosen(BRIEF) != "off"

    /** Holding ≡ over another app brings up the glance. */
    val glance get() = chosen(WATCH) != "off"
    /** A clock when you pause, and a word when the space station passes over or rain is near. */
    val nudges get() = chosen(WATCH).isEmpty()

    /** What Auto means here, from the TV's country. */
    val autoFahrenheit get() = country in setOf("US", "LR", "MM")
    val autoMph get() = country in setOf("US", "GB", "LR", "MM")

    /** Popular apps, in this order, for anyone who hasn't picked their own. Missing ones are skipped. */
    val DEFAULT_DOCK = listOf(
        "com.netflix.ninja",
        "com.amazon.firebat",
        "com.amazon.firetv.youtube",
        "com.disney.disneyplus",
        "com.hbo.hbonow",
        "com.hulu.plus",
        "com.apple.atve.amazon.appletv",
        "com.peacocktv.peacockandroid",
        "com.cbs.ott",
        "com.plexapp.android",
        "org.xbmc.kodi",
    )

    /** The dock's apps, in order. Apps that aren't installed are skipped. */
    val dock: List<String> by lazy { list(BuildConfig.DOCK).ifEmpty { DEFAULT_DOCK } }

    /** Apps kept out of All apps as well as the dock. */
    val hide: Set<String> by lazy { list(BuildConfig.HIDE).toSet() }

    val fahrenheit get() = when (chosen(TEMPERATURE)) {
        "fahrenheit", "f" -> true
        "celsius", "c" -> false
        else -> autoFahrenheit
    }

    val mph get() = when (chosen(WIND)) {
        "mph" -> true
        "kmh", "km/h" -> false
        else -> autoMph
    }
    val windUnit get() = if (mph) "mph" else "km/h"

    /** The TV's own 12/24-hour setting, read when the app starts. */
    @Volatile var system24 = false
    val clock24 get() = when (chosen(CLOCK)) {
        "24" -> true
        "12" -> false
        else -> system24
    }

    /** "9:41" or "21:41". */
    fun time(h: Int, m: Int) = if (clock24) "%d:%02d".format(Locale.US, h, m) else "%d:%02d".format(Locale.US, if (h % 12 == 0) 12 else h % 12, m)

    /** "9:41 PM" or "21:41". */
    fun timeOfDay(h: Int, m: Int) = if (clock24) time(h, m) else time(h, m) + if (h < 12) " AM" else " PM"

    /** "9 PM" or "21:00", for the hourly forecast. */
    fun hour(h: Int) = if (clock24) "%d:00".format(Locale.US, h) else "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}"

    /** Which country's streaming catalogue "New" and "Trending" come from, as TMDB names it. */
    val region get() = chosen(REGION).uppercase(Locale.ROOT).ifEmpty { country.ifEmpty { "US" } }

    /** The language titles are shown in, such as en-US. */
    val language get() = BuildConfig.LANGUAGE.trim().ifEmpty { Locale.getDefault().toLanguageTag() }

    /** The HDMI port for a PlayStation tile that's always in the dock, or 0 to show one only when it's plugged in. */
    val ps5Port get() = chosen(PS5).toIntOrNull() ?: 0

    /** No PlayStation tile or card at all, even with one plugged in. */
    val ps5Off get() = chosen(PS5) == "off"

    /** The TMDB key that turns on New and Trending: added from a phone, or a build's own. */
    val tmdbKey get() = (store?.getString(TMDB, null) ?: BuildConfig.TMDB_KEY).trim()
}
