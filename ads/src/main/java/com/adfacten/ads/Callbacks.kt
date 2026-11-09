package com.adfacten.ads

/** Result of an ad load. Always called on the main thread. */
public interface AdLoadCallback<T> {
    public fun onLoaded(ad: T)
    public fun onFailed(error: AdError)
}

/** Events of a full-screen ad (interstitial, rewarded, app open). Always called on the main thread. */
public interface FullScreenAdListener {
    /** The ad is on screen (the impression was recorded). */
    public fun onShown() {}
    public fun onClicked() {}
    /** The ad was dismissed. Resume your app here (and preload the next ad). */
    public fun onClosed() {}
    public fun onFailedToShow(error: AdError) {}
}

/** Called once when the user finished a rewarded ad. Client-side, for UX only. */
public fun interface OnUserEarnedRewardListener {
    public fun onUserEarnedReward(reward: AdReward)
}

/** Events of an [AdfactenBannerView]. Always called on the main thread. */
public interface BannerAdListener {
    public fun onLoaded() {}
    public fun onFailed(error: AdError) {}
    /** The banner creative is on screen (the impression was recorded). */
    public fun onShown() {}
    public fun onClicked() {}
}

/** Events of a [NativeAd] bound to a [NativeAdView]. Always called on the main thread. */
public interface NativeAdListener {
    public fun onShown() {}
    public fun onClicked() {}
}
