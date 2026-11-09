package com.firesky.launcher

import android.content.Intent
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Netflix's hand-over to Fire TV, in the form the TV's log shows it (`logcat -v brief`). The titles,
 * IDs, times and links are made up. The second title's line is cut short, as the log's line limit
 * cuts long ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchingTest {
    private val nf = "com.netflix.ninja"
    private val p = "I/FTVIntegrationSDK( 5703): "
    private val link1 = "https://www.netflix.com/deeplink?payload=expireAt%3D1794250000%26iid%3D0a1b2c3d%26m%3D10000001%26source_type%3D27%26source_type_payload%3DgroupIndex%253D0%2526action%253Dmdp%2526movieId%253D10000001"
    private val link2 = "https://www.netflix.com/deeplink?payload=expireAt%3D1794250000%26iid%3D0a1b2c3d%26m%3D10000002%26source_type%3D27"
    private val art1 = "https://art.example.net/tile1.jpg"

    private val items = listOf(
        "${p}[DefaultDispatcher-worker-5][AmazonContinueWatchingReceiver] Fire TV Integration SDK function: setContinueWatchingItems invoked by package: $nf with data: List(",
        "$p\t1/2: AmazonContinueWatchingItem(opaqueId=AAAAaaaaBBBBbbbbCCCCcc==, durationMs=2617000, progressMs=0, lastWatchedTimestampMs=1794160000000, genres=[], contentType=1, isNextEpisodeInSeries=false)",
        "$p\t2/2: AmazonContinueWatchingItem(opaqueId=DDDDddddEEEE/eeeFFFF+f==, durationMs=8916000, progressMs=1003000, lastWatchedTimestampMs=1793840000000, genres=[], contentType=0, isNextEpisodeInSeries=false))",
    )

    private fun meta(n: Int, id: String, title: String, desc: String, link: String, locale: String, tail: String) =
        "$p\t$n/4: AmazonContinueWatchingItemMetadata(opaqueId=$id, title=$title, description=$desc, shortDescription=null, deeplinkUri=$link, locale=$locale, tileImage=AmazonImageMetadata(imageUri=$art1, heightPx=288, widthPx=512), $tail"

    private val full = "heroImage=AmazonImageMetadata(imageUri=https://art.example.net/hero1.jpg, heightPx=720, widthPx=1280), maturityRatingSystem=TVPG, maturityRating=TV-PG, statusFlags=[], releaseYear=2025, progressMs=0, durationMs=2617000, seasonNumber=2, episodeNumber=1, episodeTitle=Episode 1, actors=[], directors=[], isOriginal=null, removeIntentUri=$link1&focusRemoveFromContinueWatching=true)"

    private val metas = listOf(
        "${p}[DefaultDispatcher-worker-5][AmazonContinueWatchingReceiver] Fire TV Integration SDK function: setContinueWatchingItemMetadata invoked by package: $nf with data: List(",
        meta(1, "AAAAaaaaBBBBbbbbCCCCcc==", "Los guardianes del faro", "Dos fareros, en una isla remota", link1, "es_US", full),
        meta(2, "AAAAaaaaBBBBbbbbCCCCcc==", "The Lighthouse Keepers", "Two keepers of a remote lighthouse face storms, shipwrecks and each other, one winter at a time.", link1, "en_US", full),
        meta(3, "DDDDddddEEEE/eeeFFFF+f==", "Doble o nada", "Un jugador, una apuesta", link2, "es_US", "heroImage=AmazonImageMetadata(imageUri=https://art.example.net/hero2.jpg, hei"),
        meta(4, "DDDDddddEEEE/eeeFFFF+f==", "Double or Nothing", "A small-time gambler, a lucky streak, and a debt that keeps doubling.", link2, "en_US", "heroImage=AmazonImageMetadata(imageUri=https://art.example.net/hero2.jpg, hei"),
    )

    private fun read(lines: List<String>): List<Pair<String, List<Watching.Item>>> {
        val got = mutableListOf<Pair<String, List<Watching.Item>>>()
        val r = Watching.Reader("en_US") { pkg, l -> got += pkg to l }
        lines.forEach(r::line)
        return got
    }

    @Test fun readsTheTitlesInYourLanguageWithProgressAndLinks() {
        val got = read(items + metas)
        val (pkg, list) = got.last()
        assertEquals(nf, pkg)
        assertEquals(listOf("The Lighthouse Keepers", "Double or Nothing"), list.map { it.title })
        val keepers = list[0]
        assertEquals(2, keepers.season); assertEquals(1, keepers.episode)
        assertEquals("S2 E1", keepers.meta)
        assertNull(keepers.progress)
        assertEquals(art1, keepers.art)
        assertEquals(1794160000000, keepers.watchedAt)
        assertEquals(link1, keepers.linkAt(1794160000000))
        // A day later Netflix's own link has expired; the show's page still opens.
        assertEquals("https://www.netflix.com/title/10000001", keepers.linkAt(1794250000000))
    }

    @Test fun aLineTheLogCutShortStillGivesTheTitle() {
        val double = read(items + metas).last().second[1]
        assertNull(double.season)
        assertEquals("2 h 11 min left", double.meta)
        assertEquals(1003000f / 8916000, double.progress!!, 1e-6f)
        assertEquals("https://www.netflix.com/title/10000002", double.page)
    }

    @Test fun waitsForTheTitlesBeforeReportingTheFirstTime() {
        // Progress alone names nothing, so it isn't reported until the titles arrive.
        assertTrue(read(items).isEmpty())
        assertEquals(2, read(items + metas).last().second.size)
    }

    @Test fun anEmptyListMeansNothingToContinue() {
        val empty = "${p}[x][AmazonContinueWatchingReceiver] Fire TV Integration SDK function: setContinueWatchingItems invoked by package: $nf with data: List()"
        val got = read(items + metas + empty)
        assertEquals(nf to emptyList<Watching.Item>(), got.last().first to got.last().second)
    }

    @Test fun otherLogLinesAreIgnored() {
        assertTrue(read(listOf("I/ActivityManager( 3226): START u0 {act=android.intent.action.MAIN}", "garbage")).isEmpty())
    }

    @Test fun commasInsideAValueStayInIt() {
        val f = Watching.fields("opaqueId=a==, title=One, Two, description=x, y=z, locale=en_US, tileImage=T")
        assertEquals("One, Two", f["title"])
        assertEquals("x, y=z", f["description"])
        assertEquals("en_US", f["locale"])
        // The last value may have been cut off, so it isn't trusted.
        assertFalse("tileImage" in f)
    }

    /* ---- the Tonight row ---- */

    private fun state(resumeAt: Long?): HomeState = HomeState().apply {
        apps = listOf(AppEntry(nf, "Netflix", Intent(), null, null, Color.Red), AppEntry("org.xbmc.kodi", "Kodi", Intent(), null, null, Color.Blue))
        resume = resumeAt?.let { Resume.Info(nf, 2_160_000, 0, it) }
        watching = read(items + metas).last().second.take(1)
    }

    @Test fun theNamedCardReplacesTheBareNetflixOne() {
        val row = state(resumeAt = 1794160000000 + 30 * 60_000).tonight()
        assertEquals("The Lighthouse Keepers", row.first().title)
        assertEquals("Continue", row.first().label)
        assertTrue(row.none { it.key == "t-resume" })
    }

    @Test fun aBareResumeMuchNewerThanNetflixsListStaysToo() {
        val row = state(resumeAt = 1794160000000 + 3 * 3_600_000).tonight()
        assertEquals(listOf("t-resume", "t-w-AAAAaaaaBBBBbbbbCCCCcc=="), row.take(2).map { it.key })
    }

    @Test fun okOnTheNamedCardGoesToTheShow() {
        val s = state(resumeAt = null)
        val c = s.tonight().first()
        assertEquals(link1.takeIf { System.currentTimeMillis() < 1794250000000 - 600_000 } ?: "https://www.netflix.com/title/10000001", s.linkFor(c))
    }

    @Test fun aWakeIsHandledOnce() {
        assertTrue(Wake.claim())
        assertFalse(Wake.claim())
    }
}
