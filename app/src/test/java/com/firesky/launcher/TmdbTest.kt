package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbTest {
    private val nf = "com.netflix.ninja"
    private val hulu = "com.hulu.plus"
    private val prime = "com.amazon.firebat"
    private fun item(id: Int, pkg: String, new: Boolean = true, pop: Double = 10.0) =
        Tmdb.Item(id, false, "t$id", new, pkg, "/a.jpg", pop, null)

    @Test fun servicesYouUseGoFirstAndTakeTurns() {
        val items = listOf(item(1, nf), item(2, nf), item(3, hulu), item(4, prime))
        val got = Tmdb.pick(items, mapOf(hulu to 300.0, nf to 100.0), emptySet(), 3)
        assertEquals(listOf(3, 1, 4), got.map { it.id })
    }

    @Test fun newBeatsPopular() {
        val items = listOf(item(1, nf, new = false, pop = 900.0), item(2, nf, new = true, pop = 5.0))
        assertEquals(2, Tmdb.pick(items, emptyMap(), emptySet(), 1).single().id)
    }

    @Test fun hiddenTitlesStayHidden() {
        val items = listOf(item(1, nf), item(2, nf))
        assertEquals(listOf(2), Tmdb.pick(items, emptyMap(), setOf(items[0].key), 3).map { it.id })
    }

    @Test fun aShowOnTwoServicesAppearsOnce() {
        val items = listOf(item(1, nf), item(1, hulu), item(2, hulu))
        assertEquals(listOf(1, 2), Tmdb.pick(items, mapOf(nf to 10.0), emptySet(), 5).map { it.id })
    }

    @Test fun oneServiceCanStillFillTheRowIfItsAllThereIs() {
        val items = listOf(item(1, nf), item(2, nf), item(3, nf))
        assertEquals(3, Tmdb.pick(items, emptyMap(), emptySet(), 3).size)
    }

    @Test fun accentIsTheVividColourNotTheAverage() {
        val red = 0xFFC8201C.toInt(); val grey = 0xFF808080.toInt(); val black = 0xFF000000.toInt()
        val c = Tmdb.accent(IntArray(100) { if (it < 15) red else if (it < 60) grey else black })
        assertEquals(red and 0xFF0000 shr 16, c and 0xFF0000 shr 16)
    }

    @Test fun aDullPictureGetsANeutralColour() {
        val c = Tmdb.accent(IntArray(100) { 0xFF808080.toInt() })
        assertEquals(0xFF6A6A78.toInt(), c)
    }
}
