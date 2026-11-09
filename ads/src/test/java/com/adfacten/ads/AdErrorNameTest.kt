package com.adfacten.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class AdErrorNameTest {
    @Test fun everyCodeHasItsSharedName() {
        val expected = mapOf(
            AdError.INTERNAL_ERROR to "internal_error",
            AdError.NOT_INITIALIZED to "not_initialized",
            AdError.INVALID_REQUEST to "invalid_request",
            AdError.NETWORK_ERROR to "network_error",
            AdError.NO_FILL to "no_fill",
            AdError.SERVER_ERROR to "server_error",
            AdError.INVALID_RESPONSE to "invalid_response",
            AdError.REJECTED to "rejected",
            AdError.AD_EXPIRED to "ad_expired",
            AdError.ALREADY_SHOWN to "already_shown",
            AdError.VIDEO_NOT_SUPPORTED to "video_not_supported",
            AdError.SHOW_FAILED to "show_failed",
            AdError.LOAD_IN_PROGRESS to "load_in_progress",
        )
        // Codes stay as they were in 1.0.x.
        assertEquals((0..12).toList(), expected.keys.sorted())
        for ((code, name) in expected) {
            assertEquals(name, AdError.nameOf(code))
            assertEquals(name, AdError(code, "m").name)
        }
    }

    @Test fun namesComeFromTheSharedVocabulary() {
        val shared = setOf(
            "internal_error", "not_initialized", "invalid_request", "network_error", "no_fill", "server_error",
            "invalid_response", "rejected", "ad_expired", "already_shown", "not_ready", "show_failed",
            "video_not_supported", "load_in_progress", "child_directed", "creative_failed",
        )
        for (code in -1..20) assertEquals(true, AdError.nameOf(code) in shared)
    }

    @Test fun unknownCodeIsInternalError() {
        assertEquals("internal_error", AdError.nameOf(99))
        assertEquals("internal_error", AdError.nameOf(-1))
    }

    @Test fun serverCodeIsKept() {
        val e = AdError(AdError.REJECTED, "not approved", "app_not_approved")
        assertEquals("rejected", e.name)
        assertEquals("app_not_approved", e.serverCode)
        assertEquals("AdError(code=7, name=rejected, message=not approved, serverCode=app_not_approved)", e.toString())
    }
}
