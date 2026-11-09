package com.firesky.launcher

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Drives the real home screen with remote presses, on the Mac, so nothing needs the TV. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h720dp-land")
class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private val opened = mutableListOf<String>()
    private var amazon = 0
    private val s = HomeState()
    private val act = object : Actions {
        override fun open(c: Card) {
            when (c.kind) {
                Kind.ALL -> s.allApps = true
                else -> { opened += c.key; s.opening() }
            }
        }
        override fun amazonHome() { amazon++ }
    }

    private fun app(pkg: String, label: String, hue: Long) = AppEntry(pkg, label, Intent(), null, null, Color(hue))

    @Before fun setUp() {
        s.load(RuntimeEnvironment.getApplication())
        s.apps = listOf(
            app("com.netflix.ninja", "Netflix", 0xFFB0101B),
            app("com.amazon.firebat", "Prime Video", 0xFF0A8FD0),
            app("com.hulu.plus", "Hulu", 0xFF14B86A),
        )
        s.resume = Resume.Info("com.netflix.ninja", 60_000, 600_000, System.currentTimeMillis())
        s.wx = Weather.parse(javaClass.classLoader!!.getResourceAsStream("open-meteo.json")!!.bufferedReader().readText())
        rule.mainClock.autoAdvance = false
        rule.setContent { Home(s, act) }
        settle()
    }

    private fun settle(ms: Long = 700) = rule.mainClock.advanceTimeBy(ms)

    private fun press(k: Key) {
        rule.onRoot().performKeyInput { pressKey(k) }
        settle(120)
    }

    private fun hold(k: Key) {
        rule.onRoot().performKeyInput { keyDown(k) }
        settle(640)
        rule.onRoot().performKeyInput { keyUp(k) }
        settle(120)
    }

    @Test fun firstCardHasFocusAndOkOpensIt() {
        press(Key.DirectionCenter)
        assertEquals(listOf("t-resume"), opened)
    }

    @Test fun aSecondOkWhileTheAppOpensIsIgnored() {
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        assertEquals(1, opened.size)
    }

    @Test fun okWorksAgainOnceTheAppHasHadTimeToOpen() {
        press(Key.DirectionCenter)
        s.openingUntil = 0
        press(Key.DirectionCenter)
        assertEquals(2, opened.size)
    }

    @Test fun menuButtonOpensAndClosesTheWeather() {
        press(Key.Menu); settle()
        assertTrue(s.panel)
        press(Key.Back); settle()
        assertFalse(s.panel)
    }

    private fun panelWith(vararg rain: Double) {
        val w = s.wx!!
        val hours = w.hours.map { Weather.Hour(it.hour, it.temp, 3, 0) }
        val next = Weather.next(false, 3, rain.toList(), hours)
        s.wx = Weather.Wx(w.temp, w.feels, w.wind, 3, w.isDay, next, rain.toList(), hours, w.days, w.sun)
        s.panel = true
        settle()
    }

    @Test fun noRainChartWhenItsDry() {
        panelWith(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        rule.onAllNodesWithText("Dry for the next 12 hours".uppercase()).assertCountEquals(1)
        rule.onAllNodesWithText("1 h").assertCountEquals(0)
    }

    @Test fun rainChartWhenRainIsComing() {
        panelWith(0.0, 0.0, 0.3, 0.8, 0.2, 0.0, 0.0, 0.0)
        rule.onAllNodesWithText("Rain in 30 min".uppercase()).assertCountEquals(1)
        rule.onAllNodesWithText("1 h").assertCountEquals(1)
        // The chip on the home screen says exactly what the panel says.
        rule.onAllNodesWithText("Rain in 30 min").assertCountEquals(1)
    }

    @Test fun weatherOpensFromTheChip() {
        press(Key.DirectionUp)
        press(Key.DirectionCenter); settle()
        assertTrue(s.panel)
    }

    @Test fun holdingOkOpensTheMenuAndItsReleasePicksNothing() {
        press(Key.DirectionDown)
        hold(Key.DirectionCenter)
        assertNotNull(s.menu)
        assertTrue(opened.isEmpty())
        assertEquals(listOf(MenuAct.MOVE, MenuAct.HIDE), s.menu!!.items)
        press(Key.Back)
        assertNull(s.menu)
    }

    @Test fun moveShiftsTheDockAndKeepsIt() {
        press(Key.DirectionDown)
        val first = s.dock().first().dockId
        hold(Key.DirectionCenter)
        press(Key.DirectionCenter)
        assertEquals(first, s.moving)
        press(Key.DirectionRight)
        assertEquals(first, s.dock()[1].dockId)
        press(Key.DirectionCenter)
        assertNull(s.moving)
        val again = HomeState().apply { load(RuntimeEnvironment.getApplication()); apps = s.apps }
        assertEquals(first, again.dock()[1].dockId)
    }

    @Test fun hideMovesATileOutOfTheDockAndResetBringsItBack() {
        press(Key.DirectionDown)
        val first = s.dock().first()
        hold(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        settle()
        assertFalse(s.dock().any { it.key == first.key })
        s.resetDock()
        settle()
        assertTrue(s.dock().any { it.key == first.key })
    }

    @Test fun settingsTileOffersAmazonHome() {
        val c = s.dock().first { it.dockId == "settings" }
        s.openMenu(c)
        assertTrue(MenuAct.AMAZON in s.menu!!.items)
        assertFalse(MenuAct.HIDE in s.menu!!.items)
    }

    @Test fun cardsCanBeHidden() {
        hold(Key.DirectionCenter)
        assertEquals(listOf(MenuAct.HIDE_CARD), s.menu!!.items)
        press(Key.DirectionCenter)
        settle()
        assertTrue(s.tonight().none { it.key == "t-resume" })
    }

    @Test fun closingAllAppsGoesBackToItsTile() {
        press(Key.DirectionDown)
        // All apps is the last tile, however many the dock has.
        repeat(s.dock().size - 1) { press(Key.DirectionRight) }
        press(Key.DirectionCenter); settle()
        assertTrue(s.allApps)
        press(Key.DirectionDown); press(Key.DirectionRight)
        press(Key.Back); settle()
        assertFalse(s.allApps)
        // The hero names what's selected: the All apps tile again, not whatever caught focus.
        rule.onAllNodesWithText("All apps").assertCountEquals(1)
    }

    @Test fun backingOutOfAllAppsWorks() {
        s.allApps = true; settle()
        press(Key.Back); settle()
        assertFalse(s.allApps)
    }

    @Test fun idleTimerDoesntCountWhileYoureInAnotherApp() {
        s.shown = false
        s.lastInput = SystemClock.uptimeMillis() - HomeState.IDLE_MS * 10
        assertFalse(s.idleDue())
    }

    @Test fun comingBackIsNeverTheIdleScreen() {
        s.lastInput = SystemClock.uptimeMillis() - HomeState.IDLE_MS * 10
        s.idle = true
        s.back()
        assertFalse(s.idle)
        assertFalse(s.idleDue())
        // Left alone on the home screen, it still goes idle as before.
        assertTrue(s.idleDue(SystemClock.uptimeMillis() + HomeState.IDLE_MS + 1))
    }

    @Test fun wakingRunsTheRise() {
        s.woke(); settle(200); settle(1400)
        assertEquals(1, s.wakeTick)
    }

    @Test fun goesIdleAndAPressBringsTheScreenBack() {
        s.idle = true; settle()
        press(Key.DirectionRight); settle()
        assertFalse(s.idle)
    }

    @Test fun eightCardsAndTheRowSlidesToTheOneYouPick() {
        s.titles = (1..10).map { Tmdb.Item(it, false, "Show $it", true, "com.netflix.ninja", "/b$it.jpg", 100.0 - it, null) }
        s.titles.forEach { s.thumbs["t-${it.key}"] = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }
        settle()
        val row = s.tonight()
        assertEquals(HomeState.MAX_CARDS, row.size)
        val screen = rule.onRoot().fetchSemanticsNode().size.width
        // The first five fit without the row moving.
        repeat(4) { press(Key.DirectionRight) }
        settle()
        assertTrue(s.rects.getValue(row[4].key).right <= screen)
        assertTrue(s.rects.getValue(row[0].key).left > 0)
        repeat(row.size - 5) { press(Key.DirectionRight) }
        settle()
        val last = s.rects.getValue(row.last().key)
        assertTrue("last card off screen: $last of $screen", last.left >= 0 && last.right <= screen)
        press(Key.DirectionCenter)
        assertEquals(listOf(row.last().key), opened)
        // And back to the start, where the first card is where it began.
        repeat(row.size - 1) { press(Key.DirectionLeft) }
        settle()
        assertTrue(s.rects.getValue(row[0].key).left > 0)
    }

    @Test fun cardsArrivingDontPullFocusOffTheDock() {
        press(Key.DirectionDown); press(Key.DirectionRight)
        val tile = s.dock()[1].key
        s.titles = (1..10).map { Tmdb.Item(it, false, "Show $it", true, "com.netflix.ninja", "/b$it.jpg", 100.0 - it, null) }
        s.titles.forEach { s.thumbs["t-${it.key}"] = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }
        settle()
        press(Key.DirectionCenter)
        assertEquals(listOf(tile), opened)
    }

    @Test fun pastTheEndOfTheDockGoesNowhere() {
        s.titles = (1..10).map { Tmdb.Item(it, false, "Show $it", true, "com.netflix.ninja", "/b$it.jpg", 100.0 - it, null) }
        s.titles.forEach { s.thumbs["t-${it.key}"] = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }
        settle()
        press(Key.DirectionDown)
        // Three more than it takes to reach All apps, the last tile.
        repeat(s.dock().size + 2) { press(Key.DirectionRight) }
        press(Key.DirectionCenter); settle()
        assertTrue(s.allApps)
        assertTrue(opened.isEmpty())
    }
}
