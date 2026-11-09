package com.adfacten.ads

/**
 * Why a load or show failed. [code] is one of the constants below and [name] its stable string form
 * (`no_fill`, `network_error`, …), shared with the iOS SDK. [serverCode] carries the ad server's machine code
 * (`unknown_app`, `app_not_approved`, `placement_mismatch`, `coppa`, `test_only`, …) when there is one.
 */
public class AdError internal constructor(
    public val code: Int,
    public val message: String,
    public val serverCode: String? = null,
) {
    /** Stable, platform-independent error name, e.g. `no_fill`. Prefer it over [code] in cross-platform code. */
    public val name: String get() = nameOf(code)

    override fun toString(): String =
        "AdError(code=$code, name=$name, message=$message" + (serverCode?.let { ", serverCode=$it" } ?: "") + ")"

    public companion object {
        public const val INTERNAL_ERROR: Int = 0
        public const val NOT_INITIALIZED: Int = 1
        public const val INVALID_REQUEST: Int = 2
        public const val NETWORK_ERROR: Int = 3
        /** No ad available right now. The SDK backs off for at least 30 s per placement after a no-fill. */
        public const val NO_FILL: Int = 4
        public const val SERVER_ERROR: Int = 5
        public const val INVALID_RESPONSE: Int = 6
        /** The server refused the request (app or placement not approved, bundle mismatch, COPPA, …). See [serverCode]. */
        public const val REJECTED: Int = 7
        /** The ad was loaded more than ~55 minutes ago. Load a new one. */
        public const val AD_EXPIRED: Int = 8
        public const val ALREADY_SHOWN: Int = 9
        /** A video ad needs the optional `com.adfacten:ads-video` artifact. */
        public const val VIDEO_NOT_SUPPORTED: Int = 10
        public const val SHOW_FAILED: Int = 11
        /** A load for this ad is already running. */
        public const val LOAD_IN_PROGRESS: Int = 12

        /** The stable name for a [code]; unknown codes map to `internal_error`. */
        @JvmStatic
        public fun nameOf(code: Int): String = when (code) {
            NOT_INITIALIZED -> "not_initialized"
            INVALID_REQUEST -> "invalid_request"
            NETWORK_ERROR -> "network_error"
            NO_FILL -> "no_fill"
            SERVER_ERROR -> "server_error"
            INVALID_RESPONSE -> "invalid_response"
            REJECTED -> "rejected"
            AD_EXPIRED -> "ad_expired"
            ALREADY_SHOWN -> "already_shown"
            VIDEO_NOT_SUPPORTED -> "video_not_supported"
            SHOW_FAILED -> "show_failed"
            LOAD_IN_PROGRESS -> "load_in_progress"
            else -> "internal_error"
        }
    }
}
