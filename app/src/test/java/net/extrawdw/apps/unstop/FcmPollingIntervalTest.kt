package net.extrawdw.apps.unstop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmPollingIntervalTest {
    @Test
    fun supportsOnlySelectableIntervals() {
        assertTrue(FcmPollingInterval.isSupported(30_000L))
        assertTrue(FcmPollingInterval.isSupported(60_000L))
        assertTrue(FcmPollingInterval.isSupported(120_000L))
        assertFalse(FcmPollingInterval.isSupported(45_000L))
    }

    @Test
    fun unsupportedIntervalFallsBackToThirtySeconds() {
        assertEquals(30_000L, FcmPollingInterval.normalize(45_000L))
    }
}
