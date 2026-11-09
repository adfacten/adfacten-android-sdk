package com.adfacten.ads.internal

import com.adfacten.ads.AdError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BannerSchedulerTest {
    private val noFill = AdError(AdError.NO_FILL, "no fill")

    /** Ticks until the scheduler asks for a load; returns the number of ticks, or -1 if it never does. */
    private fun ticksUntilLoad(s: BannerScheduler, hasAd: Boolean, visible: Boolean = true, backoffMs: (Int) -> Long = { 0 }, max: Int = 2000): Int {
        for (i in 1..max) if (s.tick(hasAd, visible, backoffMs(i))) return i
        return -1
    }

    @Test fun refreshPrecedence() {
        // App unset: the server decides, 0 = off, missing = 60 s; always clamped to 30-120 s.
        assertEquals(60, BannerScheduler.effectiveRefreshSeconds(null, null))
        assertEquals(45, BannerScheduler.effectiveRefreshSeconds(null, 45))
        assertNull(BannerScheduler.effectiveRefreshSeconds(null, 0))
        assertEquals(30, BannerScheduler.effectiveRefreshSeconds(null, 5))
        assertEquals(120, BannerScheduler.effectiveRefreshSeconds(null, 600))
        // App off: the server cannot turn it back on.
        assertNull(BannerScheduler.effectiveRefreshSeconds(false, 45))
        assertNull(BannerScheduler.effectiveRefreshSeconds(false, null))
        // App on: the server interval if it has one, else 60 s; server 0 does not turn it off.
        assertEquals(45, BannerScheduler.effectiveRefreshSeconds(true, 45))
        assertEquals(60, BannerScheduler.effectiveRefreshSeconds(true, 0))
        assertEquals(60, BannerScheduler.effectiveRefreshSeconds(true, null))
    }

    @Test fun refreshesShownAdOnServerInterval() {
        val s = BannerScheduler()
        s.onLoadCalled(); s.onLoadStarted(); s.onFilled(40)
        assertEquals(40, ticksUntilLoad(s, hasAd = true))
    }

    @Test fun appOffBeatsServerInterval() {
        val s = BannerScheduler().apply { appAutoRefresh = false }
        s.onLoadCalled(); s.onLoadStarted(); s.onFilled(30)
        assertEquals(-1, ticksUntilLoad(s, hasAd = true))
    }

    @Test fun serverOffStopsRefreshWhenAppUnset() {
        val s = BannerScheduler()
        s.onLoadCalled(); s.onLoadStarted(); s.onFilled(0)
        assertEquals(-1, ticksUntilLoad(s, hasAd = true))
    }

    @Test fun retriesAfterFailedFirstLoad() {
        val s = BannerScheduler()
        s.onLoadCalled(); s.onLoadStarted(); s.onFailed(noFill, hasAd = false)
        assertEquals(60, ticksUntilLoad(s, hasAd = false))
        // Still empty after the retry fails: keeps going.
        s.onLoadStarted(); s.onFailed(noFill, hasAd = false)
        assertEquals(60, ticksUntilLoad(s, hasAd = false))
    }

    @Test fun retryUsesKnownServerIntervalAndStillRetriesWhenRefreshIsOff() {
        val s = BannerScheduler()
        s.onLoadCalled(); s.onFilled(40)
        s.onFailed(noFill, hasAd = false)
        assertEquals(40, ticksUntilLoad(s, hasAd = false))

        val off = BannerScheduler().apply { appAutoRefresh = false }
        off.onLoadCalled(); off.onFailed(noFill, hasAd = false)
        assertEquals(60, ticksUntilLoad(off, hasAd = false))
    }

    @Test fun retryWaitsForBackoff() {
        val s = BannerScheduler()
        s.onLoadCalled(); s.onFailed(noFill, hasAd = false)
        // Backoff still running for the first 100 ticks: no load before it ends.
        assertEquals(101, ticksUntilLoad(s, hasAd = false, backoffMs = { i -> if (i <= 100) 1000L else 0L }))
    }

    @Test fun retryFollowsRealBackoffLadder() {
        var now = 0L
        val backoff = NoFillBackoff(clock = { now }, random = Random(1))
        val s = BannerScheduler()
        s.onLoadCalled()
        val waits = ArrayList<Int>()
        repeat(4) {
            backoff.onNoFill("plc")
            s.onFailed(noFill, hasAd = false)
            var ticks = 0
            while (true) {
                ticks++; now += 1000
                if (s.tick(hasAd = false, visible = true, backoffMs = backoff.remainingMs("plc"))) break
            }
            waits.add(ticks)
        }
        // 30 s backoff < 60 s timer, then 60, 120, 240 s (+ up to 25 % jitter).
        assertEquals(60, waits[0])
        assertTrue(waits[1] in 60..75)
        assertTrue(waits[2] in 120..150)
        assertTrue(waits[3] in 240..300)
    }

    @Test fun networkErrorIsNotPenalised() {
        val s = BannerScheduler()
        s.onLoadCalled(); s.onFailed(AdError(AdError.NETWORK_ERROR, "offline"), hasAd = false)
        assertEquals(60, ticksUntilLoad(s, hasAd = false))
    }

    @Test fun neverRetriesWhileHidden() {
        val s = BannerScheduler()
        s.onLoadCalled(); s.onFailed(noFill, hasAd = false)
        assertEquals(-1, ticksUntilLoad(s, hasAd = false, visible = false))
        // Hidden time does not count; visible time does.
        repeat(59) { assertFalse(s.tick(hasAd = false, visible = true, backoffMs = 0)) }
        repeat(500) { assertFalse(s.tick(hasAd = false, visible = false, backoffMs = 0)) }
        assertTrue(s.tick(hasAd = false, visible = true, backoffMs = 0))
    }

    @Test fun refusalStopsRetryUntilLoad() {
        for (code in listOf(AdError.REJECTED, AdError.INVALID_REQUEST)) {
            val s = BannerScheduler()
            s.onLoadCalled(); s.onFailed(AdError(code, "no", "app_not_approved"), hasAd = false)
            assertTrue(s.stopped)
            assertEquals(-1, ticksUntilLoad(s, hasAd = false))
            s.onLoadCalled()
            assertFalse(s.stopped)
            s.onFailed(noFill, hasAd = false)
            assertEquals(60, ticksUntilLoad(s, hasAd = false))
        }
    }

    @Test fun refusalOnRefreshKeepsAdButStopsRefreshing() {
        val s = BannerScheduler()
        s.onLoadCalled(); s.onFilled(30)
        assertEquals(30, ticksUntilLoad(s, hasAd = true))
        s.onLoadStarted(); s.onFailed(AdError(AdError.REJECTED, "no", "placement_mismatch"), hasAd = true)
        assertEquals(-1, ticksUntilLoad(s, hasAd = true))
    }

    @Test fun failedRefreshWithAdOnScreenWaitsFullInterval() {
        val s = BannerScheduler()
        s.onLoadCalled(); s.onFilled(30)
        assertEquals(30, ticksUntilLoad(s, hasAd = true))
        s.onLoadStarted(); s.onFailed(noFill, hasAd = true)
        assertEquals(30, ticksUntilLoad(s, hasAd = true))
    }

    @Test fun noRetryBeforeFirstLoad() {
        assertEquals(-1, ticksUntilLoad(BannerScheduler(), hasAd = false))
    }
}
