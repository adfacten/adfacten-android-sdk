package com.adfacten.ads.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdRequestBuilderTest {
    private val gaid = AdvertisingIdInfo("38400000-8cf0-11bd-b23e-10b96e40000d", false)
    private val noConsentInfo = ConsentSnapshot.EMPTY

    private fun device(identity: Identity) = DeviceSnapshot(
        osv = "15", make = "Samsung", model = "SM-A546B", identity = identity, lang = "tr",
        w = 1080, h = 2340, pxratio = 2.75f, connection = "wifi", carrier = "Turkcell", tzOffsetMin = 180,
    )

    private fun params(format: AdFormat = AdFormat.BANNER, coppa: Boolean = false, video: Boolean = false, userId: String? = null, custom: String? = null) =
        AdRequestParams("apk_1", "plc_1", format, if (format == AdFormat.BANNER) listOf("320x50") else emptyList(), test = true,
            coppa = coppa, userId = userId, custom = custom, videoSupported = video)

    @Test fun identityPrefersGaid() {
        val id = AdRequestBuilder.resolveIdentity(gaid, "appset-1", "sdk-1", false, noConsentInfo)
        assertEquals(gaid.id, id.ifa); assertEquals("gaid", id.ifaType); assertFalse(id.lmt)
    }

    @Test fun limitedOrZeroedGaidFallsBackToAppSetThenSdk() {
        val lat = AdRequestBuilder.resolveIdentity(AdvertisingIdInfo(gaid.id, true), "appset-1", "sdk-1", false, noConsentInfo)
        assertEquals("appset-1", lat.ifa); assertEquals("appset", lat.ifaType); assertTrue(lat.lmt)
        val zero = AdRequestBuilder.resolveIdentity(AdvertisingIdInfo("00000000-0000-0000-0000-000000000000", false), null, "sdk-1", false, noConsentInfo)
        assertEquals("sdk-1", zero.ifa); assertEquals("sdk", zero.ifaType); assertTrue(zero.lmt)
        val none = AdRequestBuilder.resolveIdentity(null, null, "sdk-1", false, noConsentInfo)
        assertEquals("sdk", none.ifaType); assertFalse(none.lmt)
    }

    @Test fun childDirectedNeverUsesDeviceIds() {
        val id = AdRequestBuilder.resolveIdentity(gaid, "appset-1", "sdk-1", true, noConsentInfo)
        assertEquals("sdk-1", id.ifa); assertEquals("sdk", id.ifaType); assertTrue(id.lmt)
    }

    @Test fun gdprWithoutPurpose1ConsentDoesNotSendGaid() {
        val denied = ConsentSnapshot(1, "CPx", "", "", "", purpose1 = false)
        assertEquals("appset", AdRequestBuilder.resolveIdentity(gaid, "appset-1", "sdk-1", false, denied).ifaType)
        val granted = ConsentSnapshot(1, "CPx", "", "", "", purpose1 = true)
        assertEquals("gaid", AdRequestBuilder.resolveIdentity(gaid, "appset-1", "sdk-1", false, granted).ifaType)
        val unknownCmp = ConsentSnapshot(1, "", "", "", "", purpose1 = null)
        assertEquals("appset", AdRequestBuilder.resolveIdentity(gaid, "appset-1", "sdk-1", false, unknownCmp).ifaType)
    }

    @Test fun buildsContractJson() {
        val consent = ConsentSnapshot(1, "CPtcf", "DBABMA~gpp", "2", "1YNN", true)
        val identity = AdRequestBuilder.resolveIdentity(gaid, null, "sdk-1", false, consent)
        val j = AdRequestBuilder.build(params(), device(identity), AppSnapshot("com.example.app", "1.2.3"), consent, "1.0.0")
        assertEquals(1, j.getInt("v"))
        assertEquals("apk_1", j.getString("app_key"))
        assertEquals("plc_1", j.getString("placement"))
        assertEquals("banner", j.getString("format"))
        assertEquals("320x50", j.getJSONArray("sizes").getString(0))
        assertTrue(j.getBoolean("test"))
        val d = j.getJSONObject("device")
        assertEquals("android", d.getString("os")); assertEquals("15", d.getString("osv"))
        assertEquals("Samsung", d.getString("make")); assertEquals("SM-A546B", d.getString("model"))
        assertEquals(gaid.id, d.getString("ifa")); assertEquals("gaid", d.getString("ifa_type")); assertFalse(d.getBoolean("lmt"))
        assertEquals("sdk-1", d.getString("sdk_id"))
        assertEquals("tr", d.getString("lang")); assertEquals(1080, d.getInt("w")); assertEquals(2340, d.getInt("h"))
        assertEquals(2.75, d.getDouble("pxratio"), 0.001); assertEquals("wifi", d.getString("connection"))
        assertEquals("Turkcell", d.getString("carrier")); assertEquals(180, d.getInt("tz_offset_min"))
        val app = j.getJSONObject("app")
        assertEquals("com.example.app", app.getString("bundle")); assertEquals("1.2.3", app.getString("version")); assertEquals("1.0.0", app.getString("sdk_version"))
        val regs = j.getJSONObject("regs")
        assertFalse(regs.getBoolean("coppa")); assertEquals(1, regs.getInt("gdpr"))
        assertEquals("CPtcf", regs.getString("tcf")); assertEquals("DBABMA~gpp", regs.getString("gpp"))
        assertEquals("2", regs.getString("gpp_sid")); assertEquals("1YNN", regs.getString("us_privacy"))
        assertFalse(j.has("user")) // banners never carry the user
        val kinds = j.getJSONArray("kinds").toString()
        assertEquals("[\"image\",\"html\"]", kinds)
    }

    @Test fun rewardedCarriesUserAndVideoKind() {
        val identity = AdRequestBuilder.resolveIdentity(null, null, "sdk-1", false, noConsentInfo)
        val long = "x".repeat(400)
        val j = AdRequestBuilder.build(params(AdFormat.REWARDED, video = true, userId = "u-42", custom = long), device(identity),
            AppSnapshot("b", "1"), noConsentInfo, "1.0.0")
        assertEquals("u-42", j.getJSONObject("user").getString("id"))
        assertEquals(256, j.getJSONObject("user").getString("custom").length)
        assertTrue(j.getJSONArray("kinds").toString().contains("video"))
        assertEquals(0, j.getJSONObject("regs").getInt("gdpr"))
    }

    @Test fun coppaFlagFromChildDirected() {
        val identity = AdRequestBuilder.resolveIdentity(gaid, null, "sdk-1", true, noConsentInfo)
        val j = AdRequestBuilder.build(params(coppa = true), device(identity), AppSnapshot("b", "1"), noConsentInfo, "1.0.0")
        assertTrue(j.getJSONObject("regs").getBoolean("coppa"))
        assertEquals("sdk", j.getJSONObject("device").getString("ifa_type"))
    }

    @Test fun userAgentFollowsContractAndIsAscii() {
        assertEquals(
            "AdfactenSDK/1.0.0 (android 15; SM-A546B) com.example.app/1.2.3",
            AdRequestBuilder.userAgent("1.0.0", "15", "SM-A546B", "com.example.app", "1.2.3"),
        )
        assertEquals(
            "AdfactenSDK/1.0.0 (android 14; Telefon_) com.ex/2.0-_",
            AdRequestBuilder.userAgent("1.0.0", "14", "Telefonı", "com.ex", "2.0-ş"),
        )
    }
}
