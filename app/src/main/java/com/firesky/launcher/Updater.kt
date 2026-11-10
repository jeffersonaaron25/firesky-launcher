package com.firesky.launcher

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * New releases from GitHub. A newer one is downloaded in the background, checked to be FireSky
 * signed with the same key, and offered on a card. Installing it takes one confirmation on the TV:
 * Android doesn't let an app update itself without asking.
 */
object Updater {
    private const val TAG = "FireSkyUpdate"
    const val REPO = "jeffersonaaron25/firesky-launcher"
    private const val FEED = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK = "FireSky.apk"
    private const val EVERY_MS = 6 * 3600_000L
    private const val RESULT = "com.firesky.launcher.INSTALL_RESULT"

    class Ready(val version: String, val file: File)

    /**
     * Android leaves you on Amazon's home once it has replaced the app. When the update was one you
     * accepted from FireSky, FireSky opens again; one installed from a computer leaves the screen alone.
     */
    class Reopen : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val p = c.getSharedPreferences("update", Context.MODE_PRIVATE)
            val at = p.getLong("installing", 0)
            p.edit().remove("installing").apply()
            if (System.currentTimeMillis() - at > 10 * 60_000L) return
            Log.i(TAG, "updated to ${BuildConfig.VERSION_NAME}, opening again")
            runCatching { c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Whether [a] is a later version than [b], comparing 1.2.3 numbers. */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until 3) {
            val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    /**
     * A newer release, downloaded and ready, or null. Asks GitHub at most every six hours unless
     * [now]. Only releases look: a build of your own is signed with a different key.
     */
    fun check(c: Context, now: Boolean = false): Ready? {
        if (!BuildConfig.RELEASE) return null
        val p = c.getSharedPreferences("update", Context.MODE_PRIVATE)
        val dir = File(c.filesDir, "updates").apply { mkdirs() }
        ready(c, dir)?.let { return it }
        val t = System.currentTimeMillis()
        if (!now && t - p.getLong("checked", 0) < EVERY_MS) return null
        p.edit().putLong("checked", t).apply()
        return runCatching {
            val feed = p.getString("feed", null) ?: FEED
            val json = JSONObject(get(feed).bufferedReader().readText())
            val version = json.getString("tag_name").removePrefix("v")
            if (!newer(version, BuildConfig.VERSION_NAME)) return@runCatching null
            val assets = json.getJSONArray("assets")
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name") == APK }?.getString("browser_download_url") ?: return@runCatching null
            dir.listFiles()?.forEach { it.delete() }
            val part = File(dir, "$version.part")
            get(url).use { input -> part.outputStream().use { input.copyTo(it) } }
            val file = File(dir, "FireSky-$version.apk")
            part.renameTo(file)
            Log.i(TAG, "downloaded $version")
            ready(c, dir)
        }.onFailure { Log.w(TAG, "check: $it") }.getOrNull()
    }

    /** A downloaded release that's newer than this one and really is FireSky, signed by the same key. */
    private fun ready(c: Context, dir: File): Ready? {
        val f = dir.listFiles()?.firstOrNull { it.name.startsWith("FireSky-") && it.name.endsWith(".apk") } ?: return null
        val version = f.name.removePrefix("FireSky-").removeSuffix(".apk")
        val ok = newer(version, BuildConfig.VERSION_NAME) && sameKey(c, f)
        if (!ok) { f.delete(); return null }
        return Ready(version, f)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun sameKey(c: Context, f: File): Boolean = runCatching {
        val pm = c.packageManager
        val theirs: PackageInfo = pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNATURES) ?: return false
        val mine = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES)
        val a = theirs.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        val b = mine.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        theirs.packageName == c.packageName && a.isNotEmpty() && a == b
    }.getOrDefault(false)

    /**
     * GitHub's answer, or the file it points to. A file: address works too, so an update can be
     * tried on a TV from a copy put on it, without publishing one: only adb can point FireSky at it.
     */
    private fun get(url: String): java.io.InputStream {
        val u = URL(url).openConnection()
        u.connectTimeout = 15_000; u.readTimeout = 30_000
        if (u is HttpURLConnection) {
            u.setRequestProperty("User-Agent", "FireSky/${BuildConfig.VERSION_NAME}")
            u.setRequestProperty("Accept", "application/vnd.github+json")
            if (u.responseCode !in 200..299) error("HTTP ${u.responseCode} for $url")
        }
        return u.inputStream
    }

    /**
     * Hands the release to Android's installer, which asks on screen. Returns false if it couldn't
     * start. Afterwards FireSky restarts as the new version.
     */
    fun install(c: Context, r: Ready): Boolean = runCatching {
        val app = c.applicationContext
        // The installer replies with the screen that asks you; that has to be opened from here.
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                Log.i(TAG, "install status $status ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    @Suppress("DEPRECATION")
                    (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } else {
                    // Cancelled or failed: nothing to come back from.
                    if (status != PackageInstaller.STATUS_SUCCESS) app.getSharedPreferences("update", Context.MODE_PRIVATE).edit().remove("installing").apply()
                    runCatching { app.unregisterReceiver(this) }
                }
            }
        }, IntentFilter(RESULT))
        app.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putLong("installing", System.currentTimeMillis()).apply()
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(app.packageName) }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite(APK, 0, r.file.length()).use { out -> r.file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val done = PendingIntent.getBroadcast(app, id, Intent(RESULT).setPackage(app.packageName), PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(done.intentSender)
        }
        true
    }.onFailure { Log.w(TAG, "install: $it") }.getOrDefault(false)
}
