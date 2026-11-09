package com.firesky.launcher

import android.content.Context
import android.content.Intent
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

    /** Hidden even though they are third-party: this app, other launchers, and Home redirects. */
    private val HIDE = setOf(
        BuildConfig.APPLICATION_ID,
        "com.spocky.projengmenu",
        "io.github.toolicious.homeonfire",
    )

    private fun shown(pkg: String): Boolean {
        if (pkg in HIDE || pkg in Prefs.hide) return false
        val system = pkg.startsWith("com.amazon.") || pkg.startsWith("com.android.")
        return !system || pkg in SYSTEM_KEEP
    }

    private val GREY = Color(0xFF5A6070)

    fun load(ctx: Context): List<AppEntry> {
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
