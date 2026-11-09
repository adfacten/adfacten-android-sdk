package com.adfacten.ads.internal

import com.adfacten.ads.AdError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdResponseParserTest {
    private val serverNow = 1_794_160_000_000L - 50 * 60_000L // 50 min before expires_at

    private fun parse(body: String, status: Int = 200, format: AdFormat = AdFormat.BANNER, serverDate: Long? = serverNow, wall: Long = serverNow) =
        AdResponseParser.parse(status, body, format, serverDate, wall, nowElapsedMs = 1_000L)

    @Test fun parsesFullBannerResponse() {
        val r = parse(Fixtures.adJson()) as AdResult.Fill
        val ad = r.ad
        assertEquals("imp_tok_1", ad.id)
        assertEquals(AdFormat.BANNER, ad.format)
        assertEquals(CreativeKind.IMAGE, ad.kind)
        assertEquals("https://cdn.adfacten.com/a@2x.webp", ad.image!!.urlFor(2.75f))
        assertEquals("https://cdn.adfacten.com/a.webp", ad.image!!.urlFor(1.5f))
        assertEquals("https://trk.adfacten.com/i?imp=imp_tok_1&e=render", ad.trackers.render)
        assertEquals("https://trk.adfacten.com/i?imp=imp_tok_1&e=close", ad.trackers.close)
        assertEquals(0.5f, ad.viewRule.minRatio)
        assertEquals(1000L, ad.viewRule.minMs)
        assertEquals(5, ad.closeAfterS)
        assertEquals("coins", ad.reward!!.type)
        assertEquals(10, ad.reward!!.amount)
        assertEquals("Reklam", ad.label)
        assertNull(ad.refreshS)
        assertEquals(1_000L + 50 * 60_000L, ad.expiresAtElapsedMs)
    }

    @Test fun expiryUsesServerDateToCancelDeviceClockSkew() {
        // Device clock is 2 hours ahead; without the Date header the ad would look expired.
        val skewed = serverNow + 2 * 3_600_000L
        val r = parse(Fixtures.adJson(), wall = skewed) as AdResult.Fill
        assertEquals(1_000L + 50 * 60_000L, r.ad.expiresAtElapsedMs)
        assertTrue(parse(Fixtures.adJson(), serverDate = null, wall = skewed) is AdResult.Failure)
    }

    @Test fun ttlIsCappedAt55Minutes() {
        val r = parse(Fixtures.adJson(expiresAt = serverNow / 1000 + 24 * 3600)) as AdResult.Fill
        assertEquals(1_000L + AdResponseParser.MAX_TTL_MS, r.ad.expiresAtElapsedMs)
        assertTrue(!r.ad.isExpired(1_000L + AdResponseParser.MAX_TTL_MS - 1))
        assertTrue(r.ad.isExpired(1_000L + AdResponseParser.MAX_TTL_MS))
    }

    @Test fun noFillAndErrors() {
        assertTrue(parse("", status = 204) is AdResult.NoFill)
        val e = (parse("""{"code":"app_not_approved","message":"App is not approved"}""", status = 403) as AdResult.Failure).error
        assertEquals(AdError.REJECTED, e.code)
        assertEquals("app_not_approved", e.serverCode)
        assertEquals("App is not approved", e.message)
        val bad = (parse("""{"code":"invalid_request","message":"x"}""", status = 400) as AdResult.Failure).error
        assertEquals(AdError.INVALID_REQUEST, bad.code)
        assertEquals(AdError.SERVER_ERROR, (parse("oops", status = 502) as AdResult.Failure).error.code)
        assertEquals(AdError.REJECTED, (parse("<html>", status = 403) as AdResult.Failure).error.code)
    }

    @Test fun rejectsInvalidAds() {
        fun code(body: String, f: AdFormat = AdFormat.BANNER) = (parse(body, format = f) as AdResult.Failure).error.code
        assertEquals(AdError.INVALID_RESPONSE, code("not json"))
        assertEquals(AdError.INVALID_RESPONSE, code(Fixtures.adJson(kind = "hologram")))
        assertEquals(AdError.INVALID_RESPONSE, code(Fixtures.adJson(format = "interstitial"))) // format mismatch
        assertEquals(AdError.INVALID_RESPONSE, code(Fixtures.adJson(kind = "video"))) // video banner
        assertEquals(AdError.INVALID_RESPONSE, code("""{"ad":{"format":"banner","kind":"image"}}""")) // no id
        assertEquals(AdError.INVALID_RESPONSE, code("""{"ad":{"id":"x","format":"banner","kind":"image","image":{"url":"javascript:alert(1)"}}}"""))
    }

    @Test fun defaultsForOptionalFields() {
        val r = parse("""{"ad":{"id":"x","format":"interstitial","kind":"video","vast":"<VAST/>","click_url":"intent://evil","refresh_s":45}}""", format = AdFormat.INTERSTITIAL) as AdResult.Fill
        assertNull(r.ad.clickUrl) // only http(s) clicks are accepted
        assertNull(r.ad.trackers.render)
        assertEquals(2000L, r.ad.viewRule.minMs) // video default
        assertEquals(AdResponseParser.DEFAULT_TTL_MS + 1_000L, r.ad.expiresAtElapsedMs)
        assertEquals(45, r.ad.refreshS)
        assertNull(r.ad.label)
    }

    @Test fun toleratesUnknownFormatsAndExtraFields() {
        val chat = parse("""{"ad":{"id":"x","format":"chat","kind":"image","image":{"url":"https://c/a.png"}}}""") as AdResult.Failure
        assertEquals(AdError.INVALID_RESPONSE, chat.error.code)
        val extra = parse(Fixtures.adJson(extra = ""","test":true,"context":{"query":"q"},"cards":[{"a":1}],"future":null""")) as AdResult.Fill
        assertTrue(extra.ad.test)
        val placement = (parse("""{"code":"placement_mismatch","message":"m"}""", status = 400) as AdResult.Failure).error
        assertEquals(AdError.REJECTED, placement.code)
        assertEquals("placement_mismatch", placement.serverCode)
    }

    @Test fun closeAfterIsClamped() {
        val r = parse(Fixtures.adJson(format = "interstitial", extra = ""), format = AdFormat.INTERSTITIAL) as AdResult.Fill
        assertEquals(5, r.ad.closeAfterS)
        val big = parse("""{"ad":{"id":"x","format":"interstitial","kind":"html","html":"<p>","close_after_s":999}}""", format = AdFormat.INTERSTITIAL) as AdResult.Fill
        assertEquals(60, big.ad.closeAfterS)
    }
}
