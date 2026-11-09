package com.adfacten.ads.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WrapperTest {
    @Test fun knownNamesAreKept() {
        for (n in listOf("flutter", "react_native", "unity", "other")) assertEquals(n, WrapperInfo.of(n, "1.0.0").name)
        assertEquals("react_native", WrapperInfo.of(" React_Native ", "1.0.0").name)
    }

    @Test fun unknownNamesBecomeOther() {
        for (n in listOf("xamarin", "react-native", "", "flutter2", "FLUTTER!")) assertEquals("other", WrapperInfo.of(n, "1").name)
    }

    @Test fun versionValidation() {
        assertEquals("1.0.1", WrapperInfo.of("flutter", "1.0.1").version)
        assertEquals("2.0.0-beta.1+42", WrapperInfo.of("flutter", " 2.0.0-beta.1+42 ").version)
        assertEquals("12345678901234567890", WrapperInfo.of("flutter", "12345678901234567890").version)
        for (bad in listOf("", "123456789012345678901", "1.0 beta", "1.0/2", "1.0;x", "sürüm"))
            assertEquals("unknown", WrapperInfo.of("flutter", bad).version)
    }

    @Test fun userAgentSuffix() {
        val ua = AdRequestBuilder.userAgent("1.0.2", "15", "Pixel 8", "com.example.app", "3.1", WrapperInfo.of("react_native", "1.0.0"))
        assertEquals("AdfactenSDK/1.0.2 (android 15; Pixel 8) com.example.app/3.1 Wrapper/react_native-1.0.0", ua)
        val plain = AdRequestBuilder.userAgent("1.0.2", "15", "Pixel 8", "com.example.app", "3.1")
        assertEquals("AdfactenSDK/1.0.2 (android 15; Pixel 8) com.example.app/3.1", plain)
    }

    private fun request(wrapper: WrapperInfo?) = AdRequestBuilder.build(
        AdRequestParams("apk_1", "plc_1", AdFormat.BANNER, listOf("320x50"), test = true, coppa = false, userId = null, custom = null, videoSupported = false),
        DeviceSnapshot("15", "Google", "Pixel 8", Identity("sdk-1", "sdk", false, "sdk-1"), "tr", 1080, 2400, 2.6f, "wifi", "", 180),
        AppSnapshot("com.example.app", "3.1"),
        ConsentSnapshot.EMPTY,
        "1.0.2",
        wrapper,
    )

    @Test fun requestCarriesAppWrapper() {
        val app = request(WrapperInfo.of("flutter", "1.0.1")).getJSONObject("app")
        assertEquals("com.example.app", app.getString("bundle"))
        assertEquals("1.0.2", app.getString("sdk_version"))
        val w = app.getJSONObject("wrapper")
        assertEquals("flutter", w.getString("name"))
        assertEquals("1.0.1", w.getString("version"))
        assertEquals(2, w.length())
    }

    @Test fun plainNativeAppSendsNoWrapper() {
        val app = request(null).getJSONObject("app")
        assertFalse(app.has("wrapper"))
        assertTrue(app.has("sdk_version"))
    }
}
