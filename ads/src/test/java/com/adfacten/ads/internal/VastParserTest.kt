package com.adfacten.ads.internal

import com.adfacten.ads.internal.vast.VastException
import com.adfacten.ads.internal.vast.VastParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VastParserTest {
    @Test fun parsesInline() {
        val v = VastParser.parse(Fixtures.VAST)
        assertEquals("vad1", v.adId)
        assertEquals(30_500L, v.durationMs)
        assertEquals(5_000L, v.skipOffsetMs)
        assertEquals(listOf("https://trk.example/imp?cb=[CACHEBUSTING]", "https://trk.example/imp2"), v.impressions)
        assertEquals(listOf("https://trk.example/err?code=[ERRORCODE]"), v.errors)
        assertEquals(listOf("https://trk.example/start"), v.urls("start"))
        assertEquals(listOf("https://trk.example/q1"), v.urls("firstQuartile"))
        assertEquals(listOf("https://trk.example/mid"), v.urls("midpoint"))
        assertEquals(listOf("https://trk.example/q3"), v.urls("thirdQuartile"))
        assertEquals(2, v.urls("complete").size)
        assertEquals("https://advertiser.example/landing", v.clickThrough)
        assertEquals(listOf("https://trk.example/click"), v.clickTracking)
        assertEquals(5, v.mediaFiles.size)
    }

    @Test fun selectsMediaBySizeAndBitrate() {
        val files = VastParser.parse(Fixtures.VAST).mediaFiles
        // 1080p phone on wifi: 1080 file is 6000 kbps (over the 4000 budget) → 720p wins.
        assertEquals("https://cdn.example/720.mp4", VastParser.selectMediaFile(files, 1080, 2340, cellular = false)!!.url)
        // Cellular budget 1500 kbps → 360p.
        assertEquals("https://cdn.example/360.mp4", VastParser.selectMediaFile(files, 1080, 2340, cellular = true)!!.url)
        // Small screen → 360p.
        assertEquals("https://cdn.example/360.mp4", VastParser.selectMediaFile(files, 480, 800, cellular = false)!!.url)
        // Only HLS/webm → nothing playable.
        assertNull(VastParser.selectMediaFile(files.filter { !it.url.endsWith(".mp4") }, 1080, 1920, false))
    }

    @Test fun rejectsWrapperAndBrokenXml() {
        fun assertFails(xml: String, part: String) {
            try { VastParser.parse(xml); fail("expected failure") } catch (e: VastException) { assertTrue(e.message, e.message!!.contains(part)) }
        }
        assertFails("""<VAST version="4.0"><Ad><Wrapper><VASTAdTagURI>https://x</VASTAdTagURI></Wrapper></Ad></VAST>""", "Wrapper")
        assertFails("""<VAST version="4.0"></VAST>""", "no Ad")
        assertFails("not xml", "not valid XML")
        assertFails("""<VAST><Ad><InLine><Creatives><Creative><Linear><Duration>00:00:10</Duration></Linear></Creative></Creatives></InLine></Ad></VAST>""", "MediaFile")
        assertFails("""<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><VAST><Ad><InLine/></Ad></VAST>""", "")
    }

    @Test fun timesAndOffsets() {
        assertEquals(3_723_250L, VastParser.parseTime("01:02:03.250"))
        assertNull(VastParser.parseTime("bad"))
        assertEquals(7_500L, VastParser.parseOffset("25%", 30_000L))
        assertEquals(5_000L, VastParser.parseOffset("00:00:05", 30_000L))
    }

    @Test fun expandsMacros() {
        val u = VastParser.expandMacros("https://t/e?c=[ERRORCODE]&cb=[CACHEBUSTING]&p=[ADPLAYHEAD]", 1_700_000_000_123L, errorCode = 405, positionMs = 61_500)
        assertTrue(u, u.startsWith("https://t/e?c=405&cb="))
        assertTrue(u, u.endsWith("&p=00:01:01.500"))
        assertTrue(!u.contains("["))
    }
}
