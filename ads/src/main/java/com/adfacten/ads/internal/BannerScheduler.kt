package com.adfacten.ads.internal

import com.adfacten.ads.AdError

/**
 * Banner refresh and retry timing, driven by a 1 s tick from the view (pure, unit-tested).
 *
 * - Refresh: the app's explicit setting wins. Off stays off, whatever the server says. Unset follows the
 *   placement's `refresh_s` (0 = off), else 60 s. Intervals are clamped to 30–120 s.
 * - Retry: after a failed load with nothing on screen, the banner tries again after the same interval
 *   (60 s when refresh is off) and never before the placement's backoff ends. A refusal (4xx) stops
 *   refresh and retry until the app calls `load()` again.
 * - Only seconds where the banner is at least half visible and its screen is active count.
 */
internal class BannerScheduler {
    /** The app's choice: null = not set, then the server decides. */
    var appAutoRefresh: Boolean? = null

    /** `refresh_s` from the last filled response (null = the server sent none). */
    var serverRefreshS: Int? = null
        private set

    /** True after a refusal; cleared by [onLoadCalled]. */
    var stopped: Boolean = false
        private set

    private var failed = false
    private var visibleSeconds = 0

    /** Seconds between refreshes of a shown ad, or null when refresh is off. */
    fun refreshSeconds(): Int? = effectiveRefreshSeconds(appAutoRefresh, serverRefreshS)

    /** Seconds between retries of an empty banner. */
    fun retrySeconds(): Int = refreshSeconds() ?: DEFAULT_REFRESH_S

    /** The app called `load()`: start over. */
    fun onLoadCalled() {
        stopped = false
        failed = false
        visibleSeconds = 0
    }

    fun onLoadStarted() {
        visibleSeconds = 0
    }

    fun onFilled(refreshS: Int?) {
        serverRefreshS = refreshS
        failed = false
        visibleSeconds = 0
    }

    /** [hasAd]: an earlier ad is still on screen (a failed refresh keeps it). */
    fun onFailed(error: AdError, hasAd: Boolean) {
        visibleSeconds = 0
        if (isRefusal(error.code)) stopped = true
        if (!hasAd) failed = true
    }

    /**
     * Called once a second. Returns true when a new load should start now.
     *
     * @param hasAd an ad is on screen
     * @param visible the banner is at least half visible, its screen resumed and the app did not pause it
     * @param backoffMs time left before the placement may be requested again
     */
    fun tick(hasAd: Boolean, visible: Boolean, backoffMs: Long): Boolean {
        if (stopped || !visible) return false
        val interval = when {
            hasAd -> refreshSeconds() ?: return false
            failed -> retrySeconds()
            else -> return false
        }
        if (visibleSeconds < interval) visibleSeconds++
        if (visibleSeconds < interval || backoffMs > 0) return false
        visibleSeconds = 0
        return true
    }

    companion object {
        const val DEFAULT_REFRESH_S = 60
        const val MIN_REFRESH_S = 30
        const val MAX_REFRESH_S = 120

        fun effectiveRefreshSeconds(appAutoRefresh: Boolean?, serverRefreshS: Int?): Int? {
            val s = when (appAutoRefresh) {
                false -> return null
                true -> serverRefreshS?.takeIf { it > 0 } ?: DEFAULT_REFRESH_S
                null -> serverRefreshS ?: DEFAULT_REFRESH_S
            }
            if (s <= 0) return null
            return s.coerceIn(MIN_REFRESH_S, MAX_REFRESH_S)
        }

        /** Errors that mean "this app or placement is not allowed": retrying will not help. */
        fun isRefusal(code: Int): Boolean = code == AdError.REJECTED || code == AdError.INVALID_REQUEST
    }
}
