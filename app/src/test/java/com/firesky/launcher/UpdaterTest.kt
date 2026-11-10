package com.firesky.launcher

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
}
