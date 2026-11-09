package com.adfacten.ads.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NoFillBackoffTest {
    private var now = 0L

    @Test fun noFillWaitsAtLeast30sWithJitterAndDoubles() {
        val b = NoFillBackoff({ now }, Random(7))
        assertEquals(0L, b.remainingMs("p"))
        b.onNoFill("p")
        val first = b.remainingMs("p")
        assertTrue("first=$first", first in 30_000L..37_500L)
        assertEquals(0L, b.remainingMs("other")) // per placement
        now += first
        assertEquals(0L, b.remainingMs("p"))
        b.onNoFill("p")
        assertTrue(b.remainingMs("p") in 60_000L..75_000L)
        repeat(10) { b.onNoFill("p") }
        assertTrue(b.remainingMs("p") in 900_000L..1_125_000L) // capped at 15 min (+jitter)
        b.onFill("p")
        assertEquals(0L, b.remainingMs("p"))
    }

    @Test fun jitterSpreadsDevices() {
        val waits = (1..20).map { seed -> NoFillBackoff({ 0L }, Random(seed)).apply { onNoFill("p") }.remainingMs("p") }.toSet()
        assertTrue(waits.size > 10)
        assertTrue(waits.all { it >= NoFillBackoff.MIN_MS })
    }

    @Test fun rejectionWaitsFiveMinutes() {
        val b = NoFillBackoff({ now }, Random(1))
        b.onRejected("p")
        assertTrue(b.remainingMs("p") in 300_000L..375_000L)
    }
}
