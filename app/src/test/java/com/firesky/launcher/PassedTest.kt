package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassedTest {
    @Test fun threeDaysPassedOverAndItRestsFromTheNext() {
        val e = Passed.Entry()
        assertFalse(Passed.see(e, 100))
        // Many looks in a day are one day.
        assertFalse(Passed.see(e, 100))
        assertFalse(Passed.see(e, 101))
        assertFalse(e.resting(102))
        assertTrue(Passed.see(e, 102))
        // Still there for the rest of the day it was passed over a third time, then away three weeks.
        assertFalse(e.resting(102))
        assertTrue(e.resting(103))
        assertTrue(e.resting(103 + Passed.REST_DAYS - 1L))
        assertFalse(e.resting(103 + Passed.REST_DAYS.toLong()))
    }

    @Test fun passedOverAgainItRestsLonger() {
        val e = Passed.Entry()
        listOf(1L, 2, 3).forEach { Passed.see(e, it) }
        val back = e.restUntil
        listOf(back, back + 1, back + 2).forEach { Passed.see(e, it) }
        assertEquals(2L * Passed.REST_DAYS, e.restUntil - e.restFrom)
    }

    @Test fun aLongGapStartsTheCountAgain() {
        val e = Passed.Entry()
        Passed.see(e, 10)
        Passed.see(e, 11)
        assertFalse(Passed.see(e, 40))
        assertEquals(1, e.days)
    }
}
