package com.firesky.launcher

import android.content.Intent
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

/** Play/Pause on the home screen, and a title's own page, driven with remote presses. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h720dp-land")
class DetailKeysTest {
    @get:Rule val rule = createComposeRule()

    private val opened = mutableListOf<String>()
    private val trailers = mutableListOf<String>()
    private val s = HomeState()
    private val act = object : Actions {
        override fun open(c: Card) { opened += c.key; s.opening() }
        override fun amazonHome() {}
        override fun trailer(c: Card, link: String) { trailers += link }
    }

    private fun app(pkg: String, label: String) = AppEntry(pkg, label, Intent(), null, null, Color(0xFF808080))

    private fun info(id: Int, trailer: String?) = Details.Info(
        Details.key(false, id), "Show $id", "What Show $id is about.", "2025", "TV-14", "2 seasons", listOf("Drama"), 8.2, 900, listOf("Ada Reyes"), "Created by Jo Lind", trailer,
    )

    @Before fun setUp() {
        s.load(RuntimeEnvironment.getApplication())
        s.apps = listOf(app("com.netflix.ninja", "Netflix"), app(YOUTUBE, "YouTube"))
        s.resume = Resume.Info("com.netflix.ninja", 60_000, 600_000, System.currentTimeMillis())
        s.titles = (1..3).map { Tmdb.Item(it, false, "Show $it", true, "com.netflix.ninja", "/b$it.jpg", 100.0 - it, "https://www.netflix.com/title/$it") }
        s.titles.forEach { s.thumbs["t-${it.key}"] = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }
        // The first title has a trailer, the second doesn't, and nothing is known about the third.
        s.details = listOf(info(1, "abc123"), info(2, null)).associateBy { it.key }
        rule.mainClock.autoAdvance = false
        rule.setContent { Home(s, act) }
        settle()
    }

    private fun settle(ms: Long = 700) = rule.mainClock.advanceTimeBy(ms)

    private fun press(k: Key) {
        rule.onRoot().performKeyInput { pressKey(k) }
        settle(120)
    }

    /** The row is the paused Netflix, then the three titles. */
    private fun toTitle(n: Int) = repeat(n) { press(Key.DirectionRight) }

    @Test fun playOnATitleOpensItsPage() {
        toTitle(1)
        assertEquals("t-title:t1", s.current)
        press(Key.MediaPlayPause); settle()
        assertEquals("Show 1", s.detail!!.info.title)
        assertEquals(listOf(DetailAct.PLAY, DetailAct.TRAILER, DetailAct.HIDE), s.detail!!.acts)
        assertTrue(opened.isEmpty())
        rule.onAllNodesWithText("What Show 1 is about.").assertCountEquals(1)
        rule.onAllNodesWithText("▶  Play on Netflix").assertCountEquals(1)
        assertFalse(s.plain)
    }

    @Test fun backClosesThePageAndLeavesYouOnTheCard() {
        toTitle(1)
        press(Key.MediaPlayPause); settle()
        press(Key.Back); settle()
        assertNull(s.detail)
        press(Key.DirectionCenter)
        assertEquals(listOf("t-title:t1"), opened)
    }

    @Test fun playAnywhereElseCarriesOnWithWhatYouWereWatching() {
        press(Key.DirectionDown)
        press(Key.MediaPlayPause)
        assertNull(s.detail)
        assertEquals(listOf("t-resume"), opened)
    }

    @Test fun aTitleNothingIsKnownAboutCarriesOnToo() {
        toTitle(3)
        assertEquals("t-title:t3", s.current)
        press(Key.MediaPlayPause)
        assertNull(s.detail)
        assertEquals(listOf("t-resume"), opened)
    }

    @Test fun okOnACardStillGoesStraightIn() {
        toTitle(1)
        press(Key.DirectionCenter)
        assertNull(s.detail)
        assertEquals(listOf("t-title:t1"), opened)
    }

    @Test fun okOnThePagePlaysAndThePageCloses() {
        toTitle(1)
        press(Key.MediaPlayPause); settle()
        press(Key.DirectionCenter)
        assertEquals(listOf("t-title:t1"), opened)
        assertNull(s.detail)
    }

    @Test fun playOnThePagePlaysWhicheverButtonIsPicked() {
        toTitle(1)
        press(Key.MediaPlayPause); settle()
        press(Key.DirectionRight)
        press(Key.MediaPlayPause)
        assertEquals(listOf("t-title:t1"), opened)
        assertTrue(trailers.isEmpty())
    }

    @Test fun theTrailerOpensAndThePageStaysForWhenYouComeBack() {
        toTitle(1)
        press(Key.MediaPlayPause); settle()
        press(Key.DirectionRight)
        assertEquals(1, s.detail!!.index)
        press(Key.DirectionCenter)
        assertEquals(listOf("https://www.youtube.com/watch?v=abc123"), trailers)
        assertNotNull(s.detail)
        assertTrue(opened.isEmpty())
    }

    @Test fun theButtonsStopAtTheirEnds() {
        toTitle(1)
        press(Key.MediaPlayPause); settle()
        press(Key.DirectionLeft)
        assertEquals(0, s.detail!!.index)
        repeat(5) { press(Key.DirectionRight) }
        assertEquals(2, s.detail!!.index)
        // The arrows are the page's while it's open: the card under it hasn't changed.
        assertEquals("t-title:t1", s.current)
    }

    @Test fun aTitleWithNoTrailerHasNoTrailerButton() {
        toTitle(2)
        press(Key.MediaPlayPause); settle()
        assertEquals(listOf(DetailAct.PLAY, DetailAct.HIDE), s.detail!!.acts)
        rule.onAllNodesWithText("Trailer").assertCountEquals(0)
    }

    @Test fun withoutYouTubeTheresNoTrailerButtonEither() {
        s.apps = s.apps.filter { it.pkg != YOUTUBE }
        settle()
        toTitle(1)
        press(Key.MediaPlayPause); settle()
        assertEquals(listOf(DetailAct.PLAY, DetailAct.HIDE), s.detail!!.acts)
    }

    @Test fun hidingFromThePageTakesTheCardAway() {
        toTitle(1)
        press(Key.MediaPlayPause); settle()
        repeat(2) { press(Key.DirectionRight) }
        press(Key.DirectionCenter); settle()
        assertNull(s.detail)
        assertTrue(s.tonight().none { it.key == "t-title:t1" })
        assertTrue(opened.isEmpty())
    }

    @Test fun theOptionsMenuOffersDetailsAfterHide() {
        toTitle(1)
        press(Key.Menu); settle()
        assertEquals(listOf(MenuAct.HIDE_CARD, MenuAct.DETAILS), s.menu!!.items)
        press(Key.DirectionDown)
        press(Key.DirectionCenter); settle()
        assertEquals("Show 1", s.detail?.info?.title)
        assertTrue(opened.isEmpty())
    }

    @Test fun homeClosesThePage() {
        toTitle(1)
        press(Key.MediaPlayPause); settle()
        s.home()
        assertNull(s.detail)
    }

    @Test fun aShowYoureWatchingHasAPageButAFilmDoesnt() {
        val card = Card("w-1", Kind.RESUME, "com.netflix.ninja", "Continue", "Show 2", "S1 E2", Color.Red)
        assertNull(s.detailKey(card))
        assertEquals("title:t1", s.detailKey(Card("t-title:t1", Kind.TITLE, "com.netflix.ninja", "New", "Show 1", "", Color.Red)))
        assertEquals("title:t44", s.detailKey(Card("t-new-44", Kind.TITLE, "com.netflix.ninja", "New episode", "Show 44", "", Color.Red)))
    }
}
