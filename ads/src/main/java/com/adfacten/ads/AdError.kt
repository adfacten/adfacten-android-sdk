package com.adfacten.ads

/**
 * Why a load or show failed. [code] is one of the constants below; [serverCode] carries the ad server's
 * machine code (`unknown_app`, `app_not_approved`, `placement_mismatch`, `coppa`, `test_only`, …) when there is one.
 */
public class AdError internal constructor(
    public val code: Int,
    public val message: String,
    public val serverCode: String? = null,
) {
    override fun toString(): String =
        "AdError(code=$code, message=$message" + (serverCode?.let { ", serverCode=$it" } ?: "") + ")"

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
    }
}
