package com.firesky.launcher

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Title cards you keep passing over step aside. A card is passed over on a day you used the home
 * screen with it in view and didn't open it. After [DAYS] such days it rests from the next day for
 * [REST_DAYS], and for twice that if it comes back and is passed over again. Opening it, or its
 * title page, forgets all that. Kept on the TV.
 */
object Passed {
    private const val TAG = "FireSkyPassed"
    const val DAYS = 3
    const val REST_DAYS = 21
    /** Passed over this long ago, it starts again from nothing. */
    private const val FORGET_DAYS = 14
    /** The day turns at 4 in the morning, so a late evening counts with the evening. */
    private const val DAY_STARTS_MS = 4 * 3_600_000L

    class Entry(var days: Int = 0, var lastDay: Long = -100_000L, var restFrom: Long = 0, var restUntil: Long = 0, var rests: Int = 0) {
        fun resting(day: Long) = day in restFrom until restUntil
    }

    /** One more look at [e] on [day]. Counts once a day; true when that sends it to rest. */
    internal fun see(e: Entry, day: Long): Boolean {
        if (day <= e.lastDay || e.resting(day)) return false
        if (day - e.lastDay > FORGET_DAYS) e.days = 0
        e.days++
        e.lastDay = day
        if (e.days < DAYS) return false
        e.rests++
        e.days = 0
        e.restFrom = day + 1
        e.restUntil = e.restFrom + REST_DAYS.toLong() * (1 shl (e.rests - 1).coerceAtMost(4))
        return true
    }

    fun today(now: Long = System.currentTimeMillis()): Long =
        Math.floorDiv(now + TimeZone.getDefault().getOffset(now) - DAY_STARTS_MS, 86_400_000L) + shift

    // ---------- For trying it out over adb ----------

    /** While trying it out: counted apart from the real counts, which are left alone, and kept nowhere. */
    @Volatile private var trial: LinkedHashMap<String, Entry>? = null
    @Volatile private var shift = 0L

    /** Tries it out with the date moved on by [days]; null ends the trial and goes back to the real counts. */
    fun trial(days: Long?) {
        if (days == null) { trial = null; shift = 0 } else { if (trial == null) trial = LinkedHashMap(); shift = days }
        Log.i(TAG, if (days == null) "trial over" else "trial: day ${today()}")
        version++
    }

    private fun map(): LinkedHashMap<String, Entry> = trial ?: all

    // ---------- Kept on disk ----------

    @Volatile private var app: Context? = null
    private val io = Executors.newSingleThreadExecutor { Thread(it, "passed").apply { isDaemon = true } }
    private val all = LinkedHashMap<String, Entry>()
    /** Changes when what's resting does, so the row is worked out again. */
    private var version by mutableIntStateOf(0)

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        // Read at once, small as it is, so a resting card isn't shown for a moment and then taken away.
        load(c.applicationContext)
    }

    private fun file(c: Context) = File(c.filesDir, "passed.json")

    private fun load(c: Context) {
        val read = runCatching { JSONObject(file(c).readText()) }.getOrNull() ?: return
        synchronized(all) {
            for (k in read.keys()) read.optJSONArray(k)?.let { a ->
                all[k] = Entry(a.optInt(0), a.optLong(1), a.optLong(2), a.optLong(3), a.optInt(4))
            }
        }
        version++
    }

    private fun save(c: Context) {
        if (trial != null) return
        val today = today()
        val o = JSONObject()
        synchronized(all) {
            // Nothing to remember about a card long gone and not resting.
            all.entries.removeAll { (_, e) -> today - e.lastDay > 120 && today >= e.restUntil }
            all.forEach { (k, e) -> o.put(k, JSONArray().put(e.days).put(e.lastDay).put(e.restFrom).put(e.restUntil).put(e.rests)) }
        }
        runCatching {
            val tmp = File(file(c).path + ".part")
            tmp.writeText(o.toString())
            tmp.renameTo(file(c))
        }.onFailure { Log.w(TAG, "couldn't keep what was passed over", it) }
    }

    /** The cards resting on [day]: left out of the row. */
    fun resting(day: Long = today()): Set<String> {
        version
        return synchronized(all) { map().filterValues { it.resting(day) }.keys.toSet() }
    }

    /** [keys] were in view while the home screen was in use. */
    fun seen(keys: Collection<String>) {
        val c = app ?: return
        if (keys.isEmpty()) return
        val day = today()
        // Most presses come after today's look has been counted: nothing to do.
        val fresh = synchronized(all) { keys.filter { k -> map()[k]?.let { it.lastDay < day } ?: true } }
        if (fresh.isEmpty()) return
        io.execute {
            val rested = synchronized(all) { fresh.count { k -> see(map().getOrPut(k) { Entry() }, day) } }
            Log.i(TAG, "${fresh.size} in view on day $day" + if (rested > 0) "; $rested passed over for $DAYS days, resting from tomorrow" else "")
            save(c)
        }
    }

    /** Opened, or its page looked at: not passed over after all. */
    fun opened(key: String?) {
        val c = app ?: return
        if (key == null) return
        io.execute {
            val had = synchronized(all) { map().remove(key) != null }
            if (had) { save(c); version++ }
        }
    }
}
