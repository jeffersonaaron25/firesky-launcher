package com.firesky.launcher

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Where you stopped in Kodi, read from Kodi's own video database (a copy, so Kodi is never
 * disturbed). Needs the storage-read grant. Unlike Netflix, Kodi knows the title.
 */
object Kodi {
    const val PKG = "org.xbmc.kodi"
    private const val DIR = "/sdcard/Android/data/org.xbmc.kodi/files/.kodi/userdata/Database"
    private const val MAX_AGE_MS = 30L * 86_400_000

    class Item(val title: String, val path: String, val positionS: Double, val totalS: Double, val playedAt: Long) {
        val progress: Float? get() = if (totalS > 0) (positionS / totalS).toFloat().coerceIn(0f, 1f) else null
        val left: String get() {
            val m = ((totalS - positionS) / 60).toInt()
            return if (m < 60) "$m min left" else "${m / 60} h ${m % 60} min left"
        }
    }

    fun latest(ctx: Context): Item? = runCatching {
        val src = File(DIR).listFiles { f -> f.name.matches(Regex("""MyVideos\d+\.db""")) }
            ?.maxByOrNull { it.name.filter(Char::isDigit).toInt() } ?: return null
        val copy = File(ctx.cacheDir, "kodi.db")
        if (!copy.exists() || copy.lastModified() < src.lastModified()) {
            src.copyTo(copy, overwrite = true)
            copy.setLastModified(src.lastModified())
        }
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                """
                SELECT f.strFilename, p.strPath, f.lastPlayed, b.timeInSeconds, b.totalTimeInSeconds,
                       m.c00, e.c00, t.c00, e.c12, e.c13
                FROM bookmark b
                JOIN files f ON f.idFile = b.idFile
                LEFT JOIN path p ON p.idPath = f.idPath
                LEFT JOIN movie m ON m.idFile = f.idFile
                LEFT JOIN episode e ON e.idFile = f.idFile
                LEFT JOIN tvshow t ON t.idShow = e.idShow
                WHERE b.type = 1
                ORDER BY f.lastPlayed DESC LIMIT 1
                """.trimIndent(),
                null,
            ).use { c ->
                if (!c.moveToFirst()) return null
                val file = c.getString(0) ?: return null
                val dir = c.getString(1) ?: ""
                val played = parse(c.getString(2)) ?: return null
                if (System.currentTimeMillis() - played > MAX_AGE_MS) return null
                val title = when {
                    c.getString(7) != null -> "${c.getString(7)} · S${c.getString(8)} E${c.getString(9)}"
                    c.getString(5) != null -> c.getString(5)
                    else -> nameFrom(file)
                }
                val path = if (file.contains("://")) file else dir + file
                Item(title, path, c.getDouble(3), c.getDouble(4), played)
            }
        }
    }.onSuccess { Log.i("FireSkyKodi", "latest=${it?.title} ${it?.left}") }.onFailure { Log.w("FireSkyKodi", "read failed", it) }.getOrNull()

    private fun parse(s: String?): Long? = s?.let {
        runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(it)?.time }.getOrNull()
    }

    /** Add-on links carry the title in a name= parameter; plain files use the file name. */
    private fun nameFrom(file: String): String {
        if (file.startsWith("plugin://")) {
            Regex("""[?&]name=([^&]+)""").find(file)?.let { return Uri.decode(it.groupValues[1].replace("+", " ")) }
        }
        return Uri.decode(file.substringAfterLast('/')).substringBeforeLast('.').replace('.', ' ').replace('_', ' ')
    }
}
