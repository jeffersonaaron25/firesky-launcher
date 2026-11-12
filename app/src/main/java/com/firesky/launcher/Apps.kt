package com.firesky.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Color as AColor

class AppEntry(
    val pkg: String,
    val label: String,
    val launch: Intent,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val hue: Color,
)

object Apps {
    /** The dock, in your order (see [Prefs.dock]). Everything else lives in All apps. */
    val DOCK get() = Prefs.dock

    /**
     * Amazon and Android ship dozens of launchable stubs (FireTV Player, "What should I watch next",
     * Kids, Luna, Live Shopping, file browsers). All of them stay hidden except these.
     * Prime Video is com.amazon.firebat; com.amazon.avod is the bare player behind it.
     */
    private val SYSTEM_KEEP = setOf(
        "com.amazon.firebat",
        "com.amazon.firetv.youtube",
        "com.amazon.cloud9",
        "com.amazon.venezia",
        "com.amazon.bueller.photos",
    )

    /**
     * Hidden even though they are third-party: this app, other launchers, Home redirects, and Fire
     * TV's AirPlay receiver, which AirPlay and screen mirroring use without it being opened, and which
     * Amazon only lets its own settings open.
     */
    private val HIDE = setOf(
        BuildConfig.APPLICATION_ID,
        "com.spocky.projengmenu",
        "io.github.toolicious.homeonfire",
        "com.apple.airplay.amazon.airplay",
    )

    internal fun shown(pkg: String): Boolean {
        if (pkg in HIDE || pkg in Prefs.hide) return false
        val system = pkg.startsWith("com.amazon.") || pkg.startsWith("com.android.")
        return !system || pkg in SYSTEM_KEEP
    }

    private val GREY = Color(0xFF5A6070)

    /*
     * Drawing every app's banner and icon takes a moment, so the list is made once and kept for as
     * long as the app runs, and made again only when an app is installed, removed or updated.
     */
    @Volatile private var held: List<AppEntry>? = null
    @Volatile private var stale = false
    private var watching = false

    /** Called, on any thread, when an app has been installed, removed or updated. */
    @Volatile var changed: (() -> Unit)? = null

    /** The list if it's already been made and is still right, without waiting. */
    fun ready(): List<AppEntry>? = held.takeIf { !stale }

    /** The list, made now if need be. Slow the first time, so not for the main thread. */
    @Synchronized
    fun get(ctx: Context): List<AppEntry> {
        watch(ctx.applicationContext)
        held?.let { if (!stale) return it }
        // Cleared first: an app that changes while the list is being made means making it again.
        stale = false
        return load(ctx).also { held = it }
    }

    private fun watch(app: Context) {
        if (watching) return
        watching = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { stale = true; changed?.invoke() }
        }, f)
    }

    private fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
                .forEach { seen.putIfAbsent(it.activityInfo.packageName, it) }
        }
        return seen.values
            .filter { shown(it.activityInfo.packageName) }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: Intent(Intent.ACTION_MAIN).setClassName(pkg, ri.activityInfo.name)
                val bannerD = ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
                val banner = bannerD?.let { draw(it, 320, 180) }
                val icon = runCatching { draw(ri.loadIcon(pm), 144, 144) }.getOrNull()
                AppEntry(
                    pkg = pkg,
                    label = ri.loadLabel(pm).toString(),
                    launch = launch,
                    banner = banner?.asImageBitmap(),
                    icon = icon?.asImageBitmap(),
                    hue = (banner ?: icon)?.let(::hueOf) ?: GREY,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun draw(d: Drawable, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(b))
        return b
    }

    /** The colour the screen takes on when this app is selected: its most saturated, bright pixels. */
    private fun hueOf(b: Bitmap): Color {
        var r = 0.0; var g = 0.0; var bl = 0.0; var sum = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until 12) for (x in 0 until 20) {
            val px = b.getPixel(x * b.width / 20, y * b.height / 12)
            if (AColor.alpha(px) < 128) continue
            AColor.colorToHSV(px, hsv)
            val w = hsv[1] * hsv[2] * hsv[1] + .01
            r += AColor.red(px) * w; g += AColor.green(px) * w; bl += AColor.blue(px) * w; sum += w
        }
        if (sum < 2.0) return GREY
        return Color((r / sum).toInt(), (g / sum).toInt(), (bl / sum).toInt())
    }
}
