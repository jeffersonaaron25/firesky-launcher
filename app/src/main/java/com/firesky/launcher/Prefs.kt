package com.firesky.launcher

import java.util.Locale

/**
 * Your choices, read from local.properties when the app is built (local.properties.example lists
 * them all). Anything you leave out follows the TV: its country decides the units and which
 * streaming catalogue to show, and its own setting decides a 12 or 24-hour clock.
 */
object Prefs {
    private fun list(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private val country get() = Locale.getDefault().country.uppercase(Locale.ROOT)

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

    val fahrenheit get() = when (BuildConfig.TEMPERATURE.lowercase(Locale.ROOT)) {
        "fahrenheit", "f" -> true
        "celsius", "c" -> false
        else -> country in setOf("US", "LR", "MM")
    }

    val mph get() = when (BuildConfig.WIND.lowercase(Locale.ROOT)) {
        "mph" -> true
        "kmh", "km/h" -> false
        else -> country in setOf("US", "GB", "LR", "MM")
    }
    val windUnit get() = if (mph) "mph" else "km/h"

    /** The TV's own 12/24-hour setting, read when the app starts. */
    @Volatile var system24 = false
    val clock24 get() = when (BuildConfig.CLOCK.trim()) {
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
    val region get() = BuildConfig.REGION.trim().uppercase(Locale.ROOT).ifEmpty { country.ifEmpty { "US" } }

    /** The language titles are shown in, such as en-US. */
    val language get() = BuildConfig.LANGUAGE.trim().ifEmpty { Locale.getDefault().toLanguageTag() }

    /** The HDMI port for a PlayStation tile that's always in the dock, or 0 to show one only when it's plugged in. */
    val ps5Port get() = BuildConfig.PS5_PORT
}
