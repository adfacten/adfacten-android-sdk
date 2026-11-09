package com.adfacten.ads

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.adfacten.ads.internal.AdFormat
import com.adfacten.ads.internal.AdLoader
import com.adfacten.ads.internal.LoadedAd
import com.adfacten.ads.internal.Sdk
import com.adfacten.ads.internal.fullscreen.AdfactenFullScreenActivity
import com.adfacten.ads.internal.fullscreen.FullScreenRegistry
import com.adfacten.ads.internal.fullscreen.FullScreenSession
import com.adfacten.ads.internal.safe

/** Common part of [InterstitialAd], [RewardedAd] and [AppOpenAd]. Each loaded ad can be shown once. */
public abstract class FullScreenAd internal constructor(
    internal val loaded: LoadedAd,
    private val format: AdFormat,
) {
    /** Show events. Set it before calling `show`. */
    public var listener: FullScreenAdListener? = null

    public val placementId: String get() = loaded.placementId

    /** An ad is valid for about 55 minutes after loading; an expired ad cannot be shown. */
    public val isExpired: Boolean get() = safe("isExpired", true) { loaded.isExpired }

    private var showCalled = false

    internal fun showInternal(activity: Activity, reward: OnUserEarnedRewardListener?) {
        safe("show") {
            val error = when {
                showCalled -> AdError(AdError.ALREADY_SHOWN, "this ad was already shown; load a new one")
                loaded.isExpired -> AdError(AdError.AD_EXPIRED, "the ad expired; load a new one")
                activity.isFinishing -> AdError(AdError.SHOW_FAILED, "the activity is finishing")
                FullScreenRegistry.isShowing() -> AdError(AdError.SHOW_FAILED, "another Adfacten ad is already on screen")
                else -> null
            }
            if (error != null) {
                val l = listener
                Sdk.post { l?.onFailedToShow(error) }
                return@safe
            }
            showCalled = true
            val token = FullScreenRegistry.put(FullScreenSession(loaded, format, { listener }, reward))
            try {
                activity.startActivity(
                    Intent(activity, AdfactenFullScreenActivity::class.java).putExtra(AdfactenFullScreenActivity.EXTRA_TOKEN, token),
                )
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(android.R.anim.fade_in, 0)
            } catch (e: Exception) {
                FullScreenRegistry.remove(token)
                val l = listener
                Sdk.post { l?.onFailedToShow(AdError(AdError.SHOW_FAILED, "could not start the ad activity: ${e.message}")) }
            }
        }
    }

    internal companion object {
        fun <T> loadFor(
            placementId: String,
            format: AdFormat,
            custom: String?,
            callback: AdLoadCallback<T>,
            wrap: (LoadedAd) -> T,
        ) {
            safe("load") {
                AdLoader.load(placementId, format, emptyList(), custom, null) { ad, err ->
                    if (ad != null) callback.onLoaded(wrap(ad)) else callback.onFailed(err ?: AdError(AdError.INTERNAL_ERROR, "unknown"))
                }
            }
        }
    }
}

/** A full-screen ad shown at natural breaks (between levels, screens…). */
public class InterstitialAd private constructor(loaded: LoadedAd) : FullScreenAd(loaded, AdFormat.INTERSTITIAL) {
    public fun show(activity: Activity) {
        showInternal(activity, null)
    }

    public companion object {
        /** Loads an interstitial for [placementId] (`plc_…`). [callback] runs on the main thread. */
        @JvmStatic
        public fun load(context: Context, placementId: String, callback: AdLoadCallback<InterstitialAd>) {
            loadFor(placementId, AdFormat.INTERSTITIAL, null, callback) { InterstitialAd(it) }
        }
    }
}

/**
 * A full-screen ad the user opts into in exchange for a reward.
 *
 * The reward is granted SERVER-SIDE: Adfacten calls your reward callback URL (signed with your reward secret)
 * after a valid completion. [OnUserEarnedRewardListener] is for UX only — never grant value from it alone.
 */
public class RewardedAd private constructor(loaded: LoadedAd) : FullScreenAd(loaded, AdFormat.REWARDED) {
    /** The reward configured on the placement, or null if the server sent none. */
    public val reward: AdReward? get() = loaded.data.reward

    public fun show(activity: Activity, onUserEarnedReward: OnUserEarnedRewardListener) {
        showInternal(activity, onUserEarnedReward)
    }

    public companion object {
        /**
         * Loads a rewarded ad. [customData] (≤ 256 chars) is echoed as `custom` in the server-side reward callback;
         * the user id comes from [AdfactenConfig.userId] / [Adfacten.setUserId].
         */
        @JvmStatic
        @JvmOverloads
        public fun load(context: Context, placementId: String, callback: AdLoadCallback<RewardedAd>, customData: String? = null) {
            loadFor(placementId, AdFormat.REWARDED, customData, callback) { RewardedAd(it) }
        }
    }
}

/** A full-screen ad shown when the app comes to the foreground (cold start or resume). */
public class AppOpenAd private constructor(loaded: LoadedAd) : FullScreenAd(loaded, AdFormat.APP_OPEN) {
    public fun show(activity: Activity) {
        showInternal(activity, null)
    }

    public companion object {
        @JvmStatic
        public fun load(context: Context, placementId: String, callback: AdLoadCallback<AppOpenAd>) {
            loadFor(placementId, AdFormat.APP_OPEN, null, callback) { AppOpenAd(it) }
        }
    }
}
