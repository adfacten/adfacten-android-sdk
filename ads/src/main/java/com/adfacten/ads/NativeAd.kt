package com.adfacten.ads

import android.content.Context
import android.graphics.Bitmap
import com.adfacten.ads.internal.AdFormat
import com.adfacten.ads.internal.AdLoader
import com.adfacten.ads.internal.LoadedAd
import com.adfacten.ads.internal.safe

/**
 * A native ad: you lay out its assets in your own views inside a [NativeAdView], which records the
 * impression, the view and clicks. Always show [adLabel] (or let NativeAdView add the badge).
 */
public class NativeAd internal constructor(internal val loaded: LoadedAd, private val defaultLabel: String) {
    private val n get() = loaded.data.native!!

    public val title: String get() = n.title
    public val body: String get() = n.body
    /** The advertiser / brand name. */
    public val brand: String get() = n.brand
    public val callToAction: String get() = n.cta
    /** The disclosure text to display, e.g. "Reklam". */
    public val adLabel: String get() = loaded.data.label ?: defaultLabel
    public val imageUrl: String? get() = n.image?.url
    public val imageWidth: Int get() = n.image?.w ?: 0
    public val imageHeight: Int get() = n.image?.h ?: 0
    public val iconUrl: String? get() = n.icon?.url
    /** The main image, already downloaded (null if it has none or the download failed). */
    public val image: Bitmap? get() = loaded.bitmap
    public val icon: Bitmap? get() = loaded.iconBitmap

    public var listener: NativeAdListener? = null

    public val isExpired: Boolean get() = safe("isExpired", true) { loaded.isExpired }

    internal var boundView: NativeAdView? = null

    /** Unbinds the ad from its view. Call when the ad is no longer needed (e.g. onDestroy). */
    public fun destroy() {
        safe("native destroy") { boundView?.setNativeAd(null) }
    }

    public companion object {
        @JvmStatic
        public fun load(context: Context, placementId: String, callback: AdLoadCallback<NativeAd>) {
            safe("native load") {
                val label = context.getString(R.string.adf_label)
                AdLoader.load(placementId, AdFormat.NATIVE, emptyList(), null, null) { ad, err ->
                    if (ad != null) callback.onLoaded(NativeAd(ad, label)) else callback.onFailed(err ?: AdError(AdError.INTERNAL_ERROR, "unknown"))
                }
            }
        }
    }
}
