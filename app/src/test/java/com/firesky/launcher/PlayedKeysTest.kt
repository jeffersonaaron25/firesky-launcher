package com.firesky.launcher

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Presses made on the picture of the home screen are handed to the real one as key events through
 * the activity, the way MainActivity plays them. This checks they act as the same presses would
 * from the remote.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h720dp-land")
class PlayedKeysTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()
    private val s = HomeState()
    private val act = object : Actions {
        override fun open(c: Card) { opened += c.key; s.opening() }
        override fun amazonHome() {}
        override fun quickSettings() {}
    }

    @Before fun setUp() {
        s.load(rule.activity)
        s.apps = listOf("com.netflix.ninja" to "Netflix", "com.amazon.firebat" to "Prime Video", "com.hulu.plus" to "Hulu")
            .map { (pkg, label) -> AppEntry(pkg, label, Intent(), null, null, Color.Gray) }
        rule.mainClock.autoAdvance = false
        rule.setContent { Home(s, act) }
        rule.mainClock.advanceTimeBy(700)
    }

    private fun play(keys: List<Pair<Int, Int>>) = rule.runOnUiThread {
        val t = SystemClock.uptimeMillis()
        for ((code, action) in keys) rule.activity.dispatchKeyEvent(KeyEvent(t, t, action, code, 0))
    }

    @Test fun playedPressesMoveAndOpenLikeTheRemote() {
        val dock = s.dock().map { it.key }
        assertEquals(dock[0], s.focusAt)
        val typed = TypeAhead()
        for (k in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)) {
            typed.keep(k, KeyEvent.ACTION_DOWN, 0); typed.keep(k, KeyEvent.ACTION_UP, 0)
        }
        play(typed.take())
        rule.mainClock.advanceTimeBy(120)
        assertEquals(dock[2], s.focusAt)
        assertEquals(listOf(dock[2]), opened)
    }
}
