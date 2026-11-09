package com.adfacten.ads.internal

import com.adfacten.ads.internal.track.ViewTimer
import com.adfacten.ads.internal.track.ViewabilityMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewabilityTest {
    @Test fun ratioMath() {
        assertEquals(1f, ViewabilityMath.ratio(320, 50, 320, 50), 0f)
        assertEquals(0.5f, ViewabilityMath.ratio(320, 25, 320, 50), 0.0001f)
        assertEquals(0.25f, ViewabilityMath.ratio(160, 25, 320, 50), 0.0001f)
        assertEquals(0f, ViewabilityMath.ratio(0, 50, 320, 50), 0f)
        assertEquals(0f, ViewabilityMath.ratio(10, 10, 0, 0), 0f)
        assertEquals(1f, ViewabilityMath.ratio(400, 60, 320, 50), 0f) // clamped
        assertEquals(0.5f, ViewabilityMath.ratio(1080, 1170, 1080, 2340), 0.0001f) // no int overflow on big views
    }

    @Test fun needsContinuousTimeAboveThreshold() {
        val t = ViewTimer(0.5f, 1000)
        assertFalse(t.update(0.6f, 0))
        assertFalse(t.update(0.6f, 900))
        assertFalse(t.update(0.4f, 950)) // dipped: reset
        assertFalse(t.update(0.5f, 1000))
        assertFalse(t.update(0.9f, 1999))
        assertTrue(t.update(0.9f, 2000))
        assertFalse(t.update(1f, 5000)) // fires once
        assertTrue(t.satisfied)
    }

    @Test fun videoRuleTwoSeconds() {
        val t = ViewTimer(0.5f, 2000)
        var fired = 0
        var now = 0L
        while (now <= 3000) { if (t.update(1f, now)) fired++; now += 100 }
        assertEquals(1, fired)
    }
}
