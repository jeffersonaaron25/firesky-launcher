package com.firesky.launcher

import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h720dp-land")
class BriefTest {
    @get:Rule val rule = createComposeRule()

    private val sun = Sun(7 * 60 + 4, 17 * 60 + 3)
    private fun at(h: Int, m: Int = 0) = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 10, h, m, 0) }
    private val hours = listOf(9, 11, 14, 12, 8, 7).mapIndexed { i, t -> Weather.Hour(8 + i, t, 0, 0) }
    private val days = listOf(Weather.Day("2026-11-10", 6, 15, 0, 0))
    private fun wx(rainAt: String? = "Rain at 4 PM") =
        Weather.Wx(9, 6, 5, 0, true, rainAt, emptyList(), hours, days, sun, more = Weather.More(daylight = 599))
    private val notes = listOf(Almanac.Note("Stars", "Clear until 3 AM", "Cloud now 6%"))

    @Test fun theMorningSaysWhatTheDayHolds() {
        val b = Brief.text(at(7, 45), wx(), sun, notes, null)
        assertEquals("Good morning", b.label)
        assertTrue(b.date, b.date.contains("10") && b.date.contains("November"))
        assertEquals("Clear, 9° now · high 15°, low 6° · rain at 4 PM", b.weather)
        assertEquals("Sunset ${Prefs.timeOfDay(17, 3)} · 9 h 59 min of daylight", b.light)
        assertEquals(notes, b.notes)
        assertNull(b.extra)
    }

    @Test fun otherTimesOfDayHaveTheirOwnWords() {
        assertEquals("Good afternoon", Brief.text(at(14), wx(), sun, notes, null).label)
        val evening = Brief.text(at(18, 40), wx(null), sun, notes, null)
        assertEquals("This evening", evening.label)
        assertEquals("Clear, 9° now · down to 7° overnight · dry for the next 12 hours", evening.weather)
        assertEquals("Sunrise ${Prefs.timeOfDay(7, 4)}", evening.light)
        assertEquals("Sunrise ${Prefs.timeOfDay(7, 4)} · sunset ${Prefs.timeOfDay(17, 3)}", Brief.text(at(5, 30), wx(), sun, notes, null).light)
    }

    @Test fun anOldForecastIsLeftOutRatherThanRepeated() {
        val b = Brief.text(at(7, 45), null, sun, notes, null)
        assertNull(b.weather)
        assertTrue(b.notes.isEmpty())
        assertEquals("Sunset ${Prefs.timeOfDay(17, 3)}", b.light)
    }

    @Test fun aNewEpisodeGetsALine() {
        val card = Card("t-new-5", Kind.TITLE, "com.netflix.ninja", "New episode", "Harbour Lights", "S2 E5 · out today", Color.Red)
        assertEquals("New episode of Harbour Lights today", Brief.text(at(7, 45), wx(), sun, notes, card).extra)
        assertEquals("New season of Harbour Lights", Brief.text(at(7, 45), wx(), sun, notes, Card("t-new-5", Kind.TITLE, null, "New season", "Harbour Lights", "S3 E1 · out now", Color.Red)).extra)
    }

    @Test fun itsOnlyForTheFirstWakeOfTheDayAndCanBeTurnedOff() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs.init(ctx)
        val s = HomeState()
        assertNotNull(Brief.due(ctx, s, at(7, 45)))
        assertNull(Brief.due(ctx, s, at(9)))
        val tomorrow = at(7, 45).apply { add(Calendar.DAY_OF_YEAR, 1) }
        Prefs.set(Prefs.BRIEF, "off")
        assertNull(Brief.due(ctx, s, tomorrow))
        Prefs.set(Prefs.BRIEF, "")
        assertNotNull(Brief.due(ctx, s, tomorrow))
        Prefs.forget()
    }

    @Test fun itShowsInPlaceOfTheTitleAndAnyPressPutsItAwayAndStillCounts() {
        val opened = mutableListOf<String>()
        val s = HomeState()
        s.load(RuntimeEnvironment.getApplication())
        s.apps = listOf(AppEntry("com.netflix.ninja", "Netflix", Intent(), null, null, Color.Red))
        s.resume = Resume.Info("com.netflix.ninja", 60_000, 600_000, System.currentTimeMillis())
        rule.mainClock.autoAdvance = false
        rule.setContent { Home(s, object : Actions { override fun open(c: Card) { opened += c.key }; override fun amazonHome() {} }) }
        rule.mainClock.advanceTimeBy(700)
        s.brief = Brief.text(at(7, 45), wx(), sun, notes, null)
        rule.mainClock.advanceTimeBy(300)
        rule.onAllNodesWithText("Clear, 9° now · high 15°, low 6° · rain at 4 PM").assertCountEquals(1)
        rule.onAllNodesWithText("Clear until 3 AM").assertCountEquals(1)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.mainClock.advanceTimeBy(1200)
        assertNull(s.brief)
        assertEquals(listOf("t-resume"), opened)
        rule.onAllNodesWithText("Clear until 3 AM").assertCountEquals(0)
    }

    @Test fun leftAloneItGoesByItself() {
        val s = HomeState()
        s.load(RuntimeEnvironment.getApplication())
        rule.mainClock.autoAdvance = false
        rule.setContent { Home(s, object : Actions { override fun open(c: Card) {}; override fun amazonHome() {} }) }
        rule.mainClock.advanceTimeBy(500)
        s.brief = Brief.text(at(7, 45), wx(), sun, notes, null)
        rule.mainClock.advanceTimeBy(3000)
        assertNotNull(s.brief)
        rule.mainClock.advanceTimeBy(6000)
        assertNull(s.brief)
    }
}
