package com.firesky.launcher

import android.content.Intent
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShowsTest {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun inDays(n: Int) = day.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, n) }.time)
    // A made-up show's second season: eight episodes, in minutes.
    private fun show(next: Shows.Ep? = Shows.Ep(3, 1, inDays(12)), last: Shows.Ep? = Shows.Ep(2, 8, "2026-01-21")) =
        Shows.Info(900001, mapOf(2 to listOf(44, 46, 39, 40, 49, 38, 35, 42)), next, last)

    @Test fun whatsLeftOfTheSeason() {
        assertEquals("7 more this season · about 5 h", Shows.rest(show(), 2, 1))
        assertEquals("2 more this season · 77 min", Shows.rest(show(), 2, 6))
        assertEquals("Last of the season", Shows.rest(show(), 2, 8))
    }

    @Test fun whatsComing() {
        assertTrue(Shows.coming(show(), 2)!!.startsWith("Season 3 on "))
        assertEquals("Next episode tomorrow", Shows.coming(show(next = Shows.Ep(2, 8, inDays(1))), 2))
        assertNull(Shows.coming(show(next = null), 2))
    }

    @Test fun aNewSeasonOutTodayGetsItsOwnCard() {
        val s = HomeState().apply {
            apps = listOf(AppEntry("com.netflix.ninja", "Netflix", Intent(), null, null, Color.Red))
            watching = listOf(Watching.Item("com.netflix.ninja", "SHOW1", "The Lighthouse Keepers", 2, 1, null, null, 0, "https://www.netflix.com/title/10000001", 0, 2617000, System.currentTimeMillis() - 3_600_000))
            shows = mapOf("The Lighthouse Keepers" to show(next = null, last = Shows.Ep(3, 1, inDays(0))))
        }
        val row = s.tonight()
        val cont = row.first()
        assertTrue(cont.meta, cont.meta.startsWith("S2 E1 · ends "))
        assertEquals("7 more this season · about 5 h", cont.sub)
        val fresh = row.first { it.key == "t-new-900001" }
        assertEquals("New season", fresh.label)
        assertEquals("S3 E1 · out today", fresh.meta)
        assertEquals("https://www.netflix.com/title/10000001", fresh.link)
    }
}
