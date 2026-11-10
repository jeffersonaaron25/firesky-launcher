package com.firesky.launcher

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presses made while the picture of the home screen is up, kept for the real one. */
class TypeAheadTest {
    private val t = TypeAhead()
    private val D = KeyEvent.ACTION_DOWN
    private val U = KeyEvent.ACTION_UP
    private val RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
    private val OK = KeyEvent.KEYCODE_DPAD_CENTER

    private fun press(k: Int) { t.keep(k, D, 0); t.keep(k, U, 0) }

    @Test fun pressesComeBackInOrderAndOnlyOnce() {
        press(RIGHT); press(RIGHT); press(OK)
        assertTrue(t.any)
        assertEquals(listOf(RIGHT to D, RIGHT to U, RIGHT to D, RIGHT to U, OK to D, OK to U), t.take())
        assertFalse(t.any)
        assertEquals(emptyList<Pair<Int, Int>>(), t.take())
    }

    @Test fun aReleaseWhosePressCameBeforeThePictureIsIgnored() {
        t.keep(OK, U, 0)
        assertFalse(t.any)
    }

    @Test fun aHeldKeyCountsOnce() {
        t.keep(RIGHT, D, 0); t.keep(RIGHT, D, 1); t.keep(RIGHT, D, 2); t.keep(RIGHT, U, 0)
        assertEquals(listOf(RIGHT to D, RIGHT to U), t.take())
    }

    /** Played without its release, it would open the tile's menu half a second later. */
    @Test fun okStillHeldDownIsLeftOut() {
        press(RIGHT); press(OK); t.keep(OK, D, 0)
        assertEquals(listOf(RIGHT to D, RIGHT to U, OK to D, OK to U), t.take())
    }

    @Test fun anArrowStillHeldDownStillMoves() {
        t.keep(RIGHT, D, 0)
        assertEquals(listOf(RIGHT to D), t.take())
    }

    @Test fun keysThatMeanNothingOnTheHomeScreenAreNotKept() {
        press(KeyEvent.KEYCODE_BACK); press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); press(KeyEvent.KEYCODE_HOME)
        assertFalse(t.any)
    }

    @Test fun clearingForgetsEverything() {
        press(RIGHT); t.keep(OK, D, 0)
        t.clear()
        t.keep(OK, U, 0)
        assertFalse(t.any)
    }
}
