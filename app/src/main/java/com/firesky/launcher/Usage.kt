package com.firesky.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** What the TV already records about which apps you open. Nothing leaves the TV. */
object Usage {
    private const val DAY = 86_400_000L

    class Usual(val pkg: String, val whenText: String)

    /** [approx]: really used at some point, but before the log begins, so the date isn't known. */
    class LastUse(val at: Long, val approx: Boolean = false)

    /** Real sessions from the TV's event log, which only reaches back about ten days. */
    class History(val sessions: List<Session>, val from: Long)

    fun granted(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun history(ctx: Context): History? {
        if (!granted(ctx)) return null
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val raw = usm.queryEvents(now - 28 * DAY, now)
        val e = UsageEvents.Event()
        val log = ArrayList<Sessions.Ev>()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            when (e.eventType) {
                Sessions.FOREGROUND, Sessions.BACKGROUND, Sessions.SCREEN_ON, Sessions.SCREEN_OFF ->
                    log += Sessions.Ev(e.timeStamp, e.eventType, e.packageName?.let(Resume::alias))
            }
        }
        return History(Sessions.from(log, now), log.firstOrNull()?.time ?: now)
    }

    /** Minutes really spent in each app, as far back as the log goes. */
    fun minutes(h: History?): Map<String, Double> {
        val out = HashMap<String, Double>()
        h?.sessions?.forEach { out[it.pkg] = (out[it.pkg] ?: 0.0) + (it.end - it.start) / 60_000.0 }
        return out
    }

    /**
     * When each app was last really on screen. Not UsageStats.lastTimeUsed: that moves whenever an
     * app posts a notification, which Netflix does in bursts as the TV wakes. Kept in our own
     * storage, because the TV's log forgets after about ten days.
     */
    fun lastUsed(ctx: Context, h: History?): Map<String, LastUse> {
        if (h == null) return emptyMap()
        val prefs = ctx.getSharedPreferences("lastUse", Context.MODE_PRIVATE)
        val seen = HashMap<String, Long>()
        prefs.all.forEach { (k, v) -> if (v is Long) seen[k] = v }
        h.sessions.forEach { seen[it.pkg] = max(seen[it.pkg] ?: 0L, it.end) }
        prefs.edit().apply { seen.forEach { (k, v) -> putLong(k, v) } }.apply()

        val out = HashMap<String, LastUse>()
        seen.forEach { (k, v) -> out[k] = LastUse(v) }

        // Apps not seen in the log: the yearly totals say whether they were ever really used.
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, now - 400 * DAY, now).orEmpty()
            .sortedByDescending { it.lastTimeUsed }
            .forEach { st ->
                val pkg = Resume.alias(st.packageName)
                if (pkg in out || st.totalTimeInForeground < Sessions.MIN_MS || st.lastTimeUsed <= 0) return@forEach
                // The log shows no real use since it began, so anything later was a notification.
                out[pkg] = if (st.lastTimeUsed < h.from) LastUse(st.lastTimeUsed) else LastUse(h.from, approx = true)
            }
        return out
    }

    /**
     * The app you most often have open around this time, within an hour and a half of now, across
     * the days the log covers. The same weekday counts fully, other days a third. Needs 45 minutes.
     */
    fun usual(h: History?, candidates: Set<String>): Usual? {
        if (h == null) return null
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        val nowDow = cal.get(Calendar.DAY_OF_WEEK)
        val nowH = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val score = HashMap<String, Double>()
        val sameDay = HashMap<String, Double>()
        for (s in h.sessions) {
            if (s.pkg !in candidates) continue
            val mins = ((s.end - s.start) / 60_000.0).coerceAtMost(240.0)
            if (mins < 2) continue
            cal.timeInMillis = s.start
            val hr = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
            var dh = abs(hr - nowH)
            if (dh > 12) dh = 24 - dh
            if (dh > 1.5) continue
            val same = cal.get(Calendar.DAY_OF_WEEK) == nowDow
            score[s.pkg] = (score[s.pkg] ?: 0.0) + mins * (if (same) 1.0 else .35)
            if (same) sameDay[s.pkg] = (sameDay[s.pkg] ?: 0.0) + mins
        }
        val best = score.maxByOrNull { it.value } ?: return null
        if (best.value < 45) return null
        val period = when (nowH.toInt()) {
            in 5..11 -> "mornings"; in 12..16 -> "afternoons"; in 17..21 -> "evenings"; else -> "nights"
        }
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now))
        val text = if ((sameDay[best.key] ?: 0.0) >= best.value * .6) "$day $period" else "Most $period"
        return Usual(best.key, text)
    }
}
