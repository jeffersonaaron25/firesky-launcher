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
import org.junit.Assert.assertNotEquals
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
        override fun changed(key: String) { this@HomeScreenTest.changed += key }
    }
    private val changed = mutableListOf<String>()

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

    @Test fun menuButtonOpensTheSelectedTilesOptions() {
        press(Key.Menu); settle()
        assertEquals(listOf(MenuAct.HIDE_CARD), s.menu!!.items)
        assertFalse(s.panel)
        press(Key.Menu); settle()
        assertNull(s.menu)
        press(Key.DirectionDown); press(Key.Menu); settle()
        assertEquals(listOf(MenuAct.MOVE, MenuAct.HIDE), s.menu!!.items)
        press(Key.Back)
        assertTrue(opened.isEmpty())
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

    @Test fun aTitleCanBeMarkedSeenAndLeavesTheRow() {
        Taste.init(RuntimeEnvironment.getApplication())
        var relearned = 0
        s.relearn = { relearned++ }
        s.titles = (1..10).map { Tmdb.Item(it, false, "Show $it", true, "com.netflix.ninja", "/b$it.jpg", 100.0 - it, null) }
        s.titles.forEach { s.thumbs["t-${it.key}"] = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }
        settle()
        val card = s.tonight().first { it.kind == Kind.TITLE }
        s.openMenu(card)
        assertEquals(listOf(MenuAct.NOT_INTERESTED, MenuAct.LIKED, MenuAct.DISLIKED), s.menu!!.items)
        // Seen it, liked it.
        press(Key.DirectionDown); press(Key.DirectionCenter); settle()
        assertNull(s.menu)
        assertTrue(s.tonight().none { it.key == card.key })
        assertTrue(card.hideId in s.saidCards)
        assertFalse(card.hideId in s.hiddenCards)
        assertEquals(1, relearned)
        // Kept with the watch history, so the picks learn from it.
        val ctx = RuntimeEnvironment.getApplication()
        val until = SystemClock.uptimeMillis() + 3000
        while (card.hideId !in Taste.said(ctx) && SystemClock.uptimeMillis() < until) Thread.sleep(20)
        assertTrue(card.hideId in Taste.said(ctx))
        // Not interested hides the next one, and counts against it.
        val next = s.tonight().first { it.kind == Kind.TITLE }
        s.openMenu(next)
        press(Key.DirectionCenter); settle()
        assertTrue(next.hideId in s.hiddenCards)
        assertEquals(2, relearned)
        // Cards that aren't titles FireSky offered keep a plain Hide.
        s.openMenu(s.tonight().first { it.kind != Kind.TITLE && it.hideId != null })
        assertEquals(MenuAct.HIDE_CARD, s.menu!!.items.first())
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

    /** The picture of the home screen for coming back to it is taken just before the idle screen, never of it. */
    @Test fun thePictureIsTakenBeforeTheIdleScreen() {
        var pictures = 0
        s.beforeIdle = { assertFalse(s.idle); pictures++ }
        s.goIdle(); s.goIdle()
        assertEquals(1, pictures)
        assertTrue(s.nearIdle())
        s.idle = false
        assertTrue("the home screen is still coming back", s.nearIdle())
        assertFalse(s.nearIdle(SystemClock.uptimeMillis() + HomeState.IDLE_LEAVE_MS + 1))
    }

    /** Home from FireSky's own settings is the home screen, as Back is. */
    @Test fun homeClosesSettings() {
        s.settings = true; s.guide = true; settle()
        s.home(); settle()
        assertFalse(s.settings)
        assertFalse(s.guide)
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

    private fun playing() {
        s.apps = s.apps + app("com.spotify.tv.android", "Spotify", 0xFF1DB954)
        s.music = Music.Track("com.spotify.tv.android", "A Song", "An Artist", null, intArrayOf(0xFFE04040.toInt(), 0xFF4060E0.toInt()), "k")
        settle()
    }

    /** OK on the music's card shows the music on its own, with its app a press away; Back goes home. */
    @Test fun theMusicCardShowsTheMusicOnItsOwn() {
        playing()
        assertEquals(HomeState.MUSIC_CARD, s.tonight().first().key)
        press(Key.DirectionCenter); settle()
        assertTrue(s.idle)
        assertTrue(opened.isEmpty())
        rule.onAllNodesWithText("Open Spotify").assertCountEquals(1)
        press(Key.Back); settle()
        assertFalse(s.idle)
        assertTrue(opened.isEmpty())
    }

    @Test fun okOnTheMusicOpensItsApp() {
        playing()
        s.idle = true; settle()
        press(Key.DirectionCenter); settle()
        assertFalse(s.idle)
        assertEquals(listOf("a-com.spotify.tv.android"), opened)
    }

    /** The remote's play keys are the music's while it's on screen: they don't wake the home screen. */
    @Test fun playKeysGoOnToTheMusic() {
        playing()
        s.idle = true; settle()
        press(Key.MediaPlayPause); settle()
        assertTrue(s.idle)
        assertTrue(opened.isEmpty())
        // And on its card, Play is the music's too, not a title page.
        s.idle = false; settle()
        press(Key.MediaPlayPause); settle()
        assertNull(s.detail)
        assertTrue(opened.isEmpty())
    }

    /** Without music, the idle screen has no button, and OK wakes the home screen as any key does. */
    @Test fun noMusicNoButton() {
        s.idle = true; settle()
        rule.onAllNodesWithText("Open", substring = true).assertCountEquals(0)
        press(Key.DirectionCenter); settle()
        assertFalse(s.idle)
        assertTrue(opened.isEmpty())
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

    /** What decides whether presses made on a picture of the home screen can be played on the real one. */
    @Test fun theShapeOfTheScreenFollowsItsTilesAndTheOneSelected() {
        val first = s.first()
        assertEquals("t-resume", first)
        assertEquals(first, s.focusAt)
        val shape = s.shape(s.focusAt)
        press(Key.DirectionDown)
        assertEquals(s.dock().first().key, s.focusAt)
        assertNotEquals("another tile is selected", shape, s.shape(s.focusAt))
        assertEquals(shape, s.shape(first))
        s.resume = null
        settle()
        assertNotEquals("a card has gone", shape, s.shape(first))
    }

    @Test fun aCardThatChangesIsShownWithoutAPress() {
        rule.onAllNodesWithText("1 min in").assertCountEquals(1)
        s.resume = Resume.Info("com.netflix.ninja", 300_000, 600_000, System.currentTimeMillis())
        settle()
        rule.onAllNodesWithText("5 min in").assertCountEquals(1)
        rule.onAllNodesWithText("1 min in").assertCountEquals(0)
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

    @Test fun fireSkySettingsOpenFromTheSettingsTileAndChangeThings() {
        Prefs.init(RuntimeEnvironment.getApplication())
        // From Auto, whatever this build's own defaults are.
        Prefs.set(Prefs.TEMPERATURE, ""); Prefs.set(Prefs.CLOCK, ""); Prefs.set(Prefs.PLACE, ""); Prefs.set(Prefs.MOTION, "")
        try {
            s.openMenu(s.dock().first { it.dockId == "settings" })
            assertEquals(MenuAct.FIRESKY, s.menu!!.items.first())
            press(Key.DirectionCenter); settle()
            assertTrue(s.settings)
            // The place first: mountains, then water, and the backdrop is told.
            press(Key.DirectionRight)
            assertEquals(Places.WATER, Prefs.chosen(Prefs.PLACE))
            assertEquals(listOf(Prefs.PLACE), changed)
            press(Key.DirectionLeft)
            assertEquals("", Prefs.chosen(Prefs.PLACE))
            // Then the home screen's motion, on until turned off.
            press(Key.DirectionDown); press(Key.DirectionRight)
            assertFalse(Prefs.motion)
            press(Key.DirectionLeft)
            assertTrue(Prefs.motion)
            // Then the temperature: Auto, then °C.
            press(Key.DirectionDown); press(Key.DirectionRight)
            assertEquals("celsius", Prefs.chosen(Prefs.TEMPERATURE))
            assertFalse(Prefs.fahrenheit)
            press(Key.DirectionLeft)
            assertEquals("", Prefs.chosen(Prefs.TEMPERATURE))
            // Two down is the clock.
            press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionRight)
            assertTrue(!Prefs.clock24 && Prefs.chosen(Prefs.CLOCK) == "12")
            press(Key.Back); settle()
            assertFalse(s.settings)
            assertTrue(opened.isEmpty())
        } finally { Prefs.forget() }
    }

    /** More settings than fit on screen: the list follows the selection down to the last. */
    @Test fun theLastSettingCanBeReached() {
        s.settings = true; settle()
        repeat(settingRows(s, act).size) { press(Key.DirectionDown) }
        settle()
        rule.onAllNodesWithText("Version").assertCountEquals(1)
        rule.onAllNodesWithText("Place").assertCountEquals(0)
        repeat(settingRows(s, act).size) { press(Key.DirectionUp) }
        settle()
        rule.onAllNodesWithText("Place").assertCountEquals(1)
        s.settings = false
    }

    @Test fun untilHomeWorksTheFirstCardSaysHow() {
        s.homeReady = false
        settle()
        assertEquals(Kind.SETUP, s.tonight().first().kind)
        s.homeReady = true
        settle()
        assertTrue(s.tonight().none { it.kind == Kind.SETUP })
    }

    @Test fun theUpdateCardSaysWhatItBringsWhileSelected() {
        s.homeReady = true
        s.update = Updater.Ready("1.2.0", java.io.File("FireSky-1.2.0.apk"), listOf("See what an update brings", "Quicker Home"))
        settle()
        val update = s.tonight().first()
        assertEquals(Kind.UPDATE, update.kind)
        repeat(3) { if (s.focusAt != update.key) press(Key.DirectionLeft) }
        assertEquals(update.key, s.focusAt)
        settle()
        rule.onAllNodesWithText("UPDATE · OK TO INSTALL").assertCountEquals(1)
        rule.onAllNodesWithText("See what an update brings").assertCountEquals(1)
        rule.onAllNodesWithText("Quicker Home").assertCountEquals(1)
        press(Key.DirectionRight); settle()
        rule.onAllNodesWithText("See what an update brings").assertCountEquals(0)
    }

    @Test fun anUpdateWithoutNotesJustSaysItsReady() {
        s.homeReady = true
        s.update = Updater.Ready("1.2.0", java.io.File("FireSky-1.2.0.apk"))
        settle()
        repeat(3) { if (s.focusAt != "t-update") press(Key.DirectionLeft) }
        assertEquals("t-update", s.focusAt)
        settle()
        rule.onAllNodesWithText("Ready to install · OK to update").assertCountEquals(1)
    }

    @Test fun theStepsOpenAndBackClosesThem() {
        s.guide = true; settle()
        press(Key.DirectionRight)
        press(Key.Back); settle()
        assertFalse(s.guide)
        assertTrue(opened.isEmpty())
    }

    @Test fun allAppsAddsToTheDockAndHidesApps() {
        s.apps = s.apps + app("com.spotify.tv.android", "Spotify", 0xFF1DB954)
        settle()
        val spotify = s.app("com.spotify.tv.android")!!
        s.allApps = true; settle()
        s.openMenu(s.allCard(spotify))
        assertEquals(listOf(MenuAct.ADD_DOCK, MenuAct.HIDE_APP), s.menu!!.items)
        press(Key.DirectionCenter); settle()
        val dock = s.dock().mapNotNull { it.dockId }
        assertTrue("com.spotify.tv.android" in dock)
        // In before Settings and All apps, which stay at the end.
        assertEquals(listOf("settings", "all"), dock.takeLast(2))
        s.openMenu(s.allCard(spotify))
        assertEquals(MenuAct.UNDOCK, s.menu!!.items.first())
        press(Key.DirectionCenter); settle()
        assertTrue(s.dock().none { it.dockId == "com.spotify.tv.android" })
        s.openMenu(s.allCard(spotify))
        press(Key.DirectionDown); press(Key.DirectionCenter); settle()
        assertTrue(s.shownApps.none { it.pkg == "com.spotify.tv.android" })
        s.showHiddenApps()
        assertTrue(s.shownApps.any { it.pkg == "com.spotify.tv.android" })
        assertTrue(opened.isEmpty())
    }

    @Test fun holdingOkOrMenuInAllAppsOffersTheDock() {
        s.apps = s.apps + app("com.spotify.tv.android", "Spotify", 0xFF1DB954)
        settle()
        press(Key.DirectionDown)
        repeat(s.dock().size - 1) { press(Key.DirectionRight) }
        press(Key.DirectionCenter); settle()
        assertTrue(s.allApps)
        hold(Key.DirectionCenter)
        assertTrue(s.menu!!.card.key.startsWith("all-"))
        press(Key.Back)
        press(Key.Menu); settle()
        assertTrue(s.menu!!.card.key.startsWith("all-"))
        press(Key.Back)
        assertTrue(opened.isEmpty())
    }
}
