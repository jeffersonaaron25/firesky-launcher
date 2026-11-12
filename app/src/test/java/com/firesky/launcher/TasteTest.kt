package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class TasteTest {
    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L
    private fun film() = Taste.Seen("Arrival", false, "com.amazon.firebat", 0f, 0f, 0, HashSet(), t0, t0)
    private fun show() = Taste.Seen("Severance", true, "com.netflix.ninja", 0f, 0f, 0, HashSet(), t0, t0)

    @Test fun aFilmWatchedToTheEndIsFinishedAndAgainIsTwice() {
        val s = film()
        Taste.step(s, .4f, null, null, t0)
        assertEquals(Taste.Verdict.WATCHING, Taste.verdict(s, t0))
        Taste.step(s, .9f, null, null, t0 + 3_600_000)
        assertEquals(Taste.Verdict.FINISHED, Taste.verdict(s, t0 + day))
        // The same report again doesn't count twice.
        Taste.step(s, .95f, null, null, t0 + 3_700_000)
        assertEquals(1, s.ends)
        // From the start again, weeks later, and to the end.
        Taste.step(s, .02f, null, null, t0 + 30 * day)
        Taste.step(s, .5f, null, null, t0 + 30 * day + 3_600_000)
        Taste.step(s, .9f, null, null, t0 + 30 * day + 7_200_000)
        assertEquals(Taste.Verdict.TWICE, Taste.verdict(s, t0 + 31 * day))
    }

    @Test fun skippingBackAndToTheEndAgainIsntASecondWatch() {
        val s = film()
        Taste.step(s, .9f, null, null, t0)
        Taste.step(s, .05f, null, null, t0 + 60_000)
        Taste.step(s, .95f, null, null, t0 + 120_000)
        assertEquals(1, s.ends)
    }

    @Test fun aShowFromAnAppThatGivesNoEpisodesCountsByDays() {
        val s = Taste.Seen("Slow Horses", true, "com.apple.atve.amazon.appletv", 0f, 0f, 0, HashSet(), t0, t0)
        Taste.step(s, .6f, null, null, t0)
        Taste.step(s, .9f, null, null, t0 + 3_600_000)
        Taste.step(s, .3f, null, null, t0 + day)
        assertEquals(1, s.episodes.size)
        Taste.step(s, .7f, null, null, t0 + day + 1_800_000)
        Taste.step(s, .8f, null, null, t0 + 2 * day)
        assertEquals(Taste.Verdict.FINISHED, Taste.verdict(s, t0 + 3 * day))
    }

    @Test fun aFilmLeftEarlyCountsOnlyOnceItsBeenLeftAWhile() {
        val s = film()
        Taste.step(s, .15f, null, null, t0)
        assertEquals(Taste.Verdict.WATCHING, Taste.verdict(s, t0 + 3 * day))
        assertEquals(Taste.Verdict.LEFT, Taste.verdict(s, t0 + 15 * day))
    }

    @Test fun aShowCountsByItsEpisodes() {
        val s = show()
        Taste.step(s, .3f, 1, 1, t0)
        assertEquals(Taste.Verdict.WATCHING, Taste.verdict(s, t0 + day))
        assertEquals(Taste.Verdict.LEFT, Taste.verdict(s, t0 + 20 * day))
        Taste.step(s, .5f, 1, 2, t0 + day)
        Taste.step(s, .1f, 1, 3, t0 + 2 * day)
        assertEquals(Taste.Verdict.FINISHED, Taste.verdict(s, t0 + 40 * day))
        for (e in 4..10) Taste.step(s, .5f, 1, e, t0 + (2 + e) * day)
        assertEquals(Taste.Verdict.TWICE, Taste.verdict(s, t0 + 40 * day))
    }
}
