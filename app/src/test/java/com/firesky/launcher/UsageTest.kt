package com.firesky.launcher

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The TV's record of which apps were open is read a little at a time: only what's new since the last look. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UsageTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val tv = shadowOf(ctx.getSystemService(UsageStatsManager::class.java))
    private val hour = 3_600_000L

    @Before fun setUp() {
        shadowOf(ctx.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName, AppOpsManager.MODE_ALLOWED)
        Usage.forget()
    }

    private fun used(pkg: String, at: Long, minutes: Int) {
        tv.addEvent(pkg, at, Sessions.FOREGROUND)
        tv.addEvent(pkg, at + minutes * 60_000L, Sessions.BACKGROUND)
    }

    private fun apps() = Usage.history(ctx)!!.sessions.map { it.pkg }

    @Test fun whatsNewIsAddedToWhatWasAlreadyRead() {
        val now = System.currentTimeMillis()
        used("netflix", now - 5 * hour, 40)
        assertEquals(listOf("netflix"), apps())
        used("hulu", now - 2 * hour, 25)
        assertEquals(listOf("netflix", "hulu"), apps())
        // Looking again adds nothing twice.
        assertEquals(listOf("netflix", "hulu"), apps())
    }

    @Test fun aLittleAtATimeComesToTheSameAsAllAtOnce() {
        val now = System.currentTimeMillis()
        used("netflix", now - 9 * hour, 40)
        apps()
        used("hulu", now - 6 * hour, 25)
        apps()
        used("netflix", now - 3 * hour, 55)
        val h = Usage.history(ctx)!!
        Usage.forget()
        val all = Usage.history(ctx)!!
        assertEquals(all.sessions.map { Triple(it.pkg, it.start, it.end) }, h.sessions.map { Triple(it.pkg, it.start, it.end) })
        assertEquals(all.from, h.from)
        assertEquals(Usage.minutes(all), Usage.minutes(h))
    }

    @Test fun anAppStillOpenCountsUpToNowHoweverOftenItsLookedAt() {
        val now = System.currentTimeMillis()
        tv.addEvent("netflix", now - 10 * 60_000L, Sessions.FOREGROUND)
        repeat(3) { assertEquals(10.0, Usage.minutes(Usage.history(ctx))["netflix"]!!, .05) }
        // And once it closes, it ends there.
        tv.addEvent("netflix", now - 4 * 60_000L, Sessions.BACKGROUND)
        assertEquals(6.0, Usage.minutes(Usage.history(ctx))["netflix"]!!, .001)
    }

    @Test fun onlyWhatsNewIsAskedFor() {
        val now = System.currentTimeMillis()
        used("netflix", now - 2 * hour, 40)
        apps()
        // Something from before the last thing read isn't looked for again until everything is,
        // which happens a few times a day and whenever the app starts.
        used("hulu", now - 5 * hour, 25)
        assertEquals(listOf("netflix"), apps())
        Usage.forget()
        assertEquals(listOf("hulu", "netflix"), apps())
    }
}
