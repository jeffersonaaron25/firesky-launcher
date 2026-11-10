package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun comparesVersionsAsNumbers() {
        assertTrue(Updater.newer("1.0.1", "1.0.0"))
        assertTrue(Updater.newer("1.10.0", "1.9.9"))
        assertTrue(Updater.newer("v2.0.0", "1.9.0"))
        assertFalse(Updater.newer("1.0.0", "1.0.0"))
        assertFalse(Updater.newer("1.2.0", "1.10.0"))
    }

    /** As the release workflow publishes them: this version's CHANGELOG points, then the install line and GitHub's own. */
    @Test fun theCardTakesTheFirstPointsOfTheNotesAsPlainWords() {
        val body = """
            - **Title pages:** Play/Pause on a card
            - More weather, from [Open-Meteo](https://open-meteo.com)
              and the sky tonight
            * `Quicker` Home
            - A fourth point

            To install, see [Install](https://example.com).

            ## What's Changed
            * A pull request by someone
        """.trimIndent().replace("\n", "\r\n")
        assertEquals(
            listOf("Title pages: Play/Pause on a card", "More weather, from Open-Meteo and the sky tonight", "Quicker Home"),
            Updater.notes(body),
        )
    }

    @Test fun onlyTheFirstListCounts() {
        val body = "- One\n- Two\n\nTo install, see [Install](x).\n\n## What's Changed\n* A pull request by someone"
        assertEquals(listOf("One", "Two"), Updater.notes(body))
    }

    @Test fun notesWithoutPointsGiveNone() {
        assertEquals(emptyList<String>(), Updater.notes("To install, see [Install](x).\n\n**Full Changelog**: https://example.com"))
        assertEquals(emptyList<String>(), Updater.notes(""))
    }
}
