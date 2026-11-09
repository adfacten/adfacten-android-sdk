package com.adfacten.ads

/**
 * SDK configuration passed to [Adfacten.initialize].
 *
 * Kotlin: `AdfactenConfig(testMode = true)`. Java: `new AdfactenConfig.Builder().setTestMode(true).build()`.
 *
 * Consent (IAB TCF v2, GPP, US Privacy) is read automatically from the app's default SharedPreferences, where
 * any IAB-compliant CMP stores it. Nothing needs to be passed here for it.
 */
public class AdfactenConfig @JvmOverloads constructor(
    /** Request test ads. Test ads are never billed. Turn it off for release builds. */
    public val testMode: Boolean = false,
    public val logLevel: LogLevel = LogLevel.WARN,
    /**
     * Set to true if the app (or this user) is directed at children (COPPA / Google Families).
     * The SDK then never reads the advertising id or App Set ID and sends `regs.coppa = true`;
     * Adfacten does not serve ads to child-directed traffic.
     */
    public val childDirected: Boolean = false,
    /** Your own user id. Sent only with rewarded requests and echoed in the server-side reward callback. */
    public val userId: String? = null,
    /** Ad server base URL. Change only for local testing. */
    public val endpoint: String = DEFAULT_ENDPOINT,
) {
    public class Builder {
        private var testMode = false
        private var logLevel = LogLevel.WARN
        private var childDirected = false
        private var userId: String? = null
        private var endpoint = DEFAULT_ENDPOINT

        public fun setTestMode(value: Boolean): Builder = apply { testMode = value }
        public fun setLogLevel(value: LogLevel): Builder = apply { logLevel = value }
        public fun setChildDirected(value: Boolean): Builder = apply { childDirected = value }
        public fun setUserId(value: String?): Builder = apply { userId = value }
        public fun setEndpoint(value: String): Builder = apply { endpoint = value }
        public fun build(): AdfactenConfig = AdfactenConfig(testMode, logLevel, childDirected, userId, endpoint)
    }

    public companion object {
        public const val DEFAULT_ENDPOINT: String = "https://trk.adfacten.com"
    }
}
