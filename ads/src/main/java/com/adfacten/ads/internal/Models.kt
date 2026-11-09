package com.adfacten.ads.internal

import com.adfacten.ads.AdReward

internal enum class AdFormat(val wire: String) {
    BANNER("banner"), INTERSTITIAL("interstitial"), REWARDED("rewarded"), NATIVE("native"), APP_OPEN("app_open");

    val isFullScreen: Boolean get() = this == INTERSTITIAL || this == REWARDED || this == APP_OPEN

    companion object {
        fun fromWire(s: String?): AdFormat? = values().firstOrNull { it.wire == s }
    }
}

internal enum class CreativeKind(val wire: String) {
    IMAGE("image"), HTML("html"), VIDEO("video"), NATIVE("native");

    companion object {
        fun fromWire(s: String?): CreativeKind? = values().firstOrNull { it.wire == s }
    }
}

internal class ImageAsset(val url: String, val url2x: String?, val fallback: String?, val w: Int = 0, val h: Int = 0) {
    /** The best URL for the screen density; `fallback` (jpg) is tried if decoding fails. */
    fun urlFor(density: Float): String = if (density >= 2f && !url2x.isNullOrEmpty()) url2x else url
}

internal class NativeAssets(
    val title: String,
    val body: String,
    val brand: String,
    val cta: String,
    val image: ImageAsset?,
    val icon: ImageAsset?,
)

internal class Trackers(val render: String?, val view: String?, val complete: String?, val close: String?)

internal class ViewRule(val minRatio: Float, val minMs: Long)

internal class AdData(
    val id: String,
    val format: AdFormat,
    val kind: CreativeKind,
    val w: Int,
    val h: Int,
    val image: ImageAsset?,
    val html: String?,
    val vast: String?,
    val native: NativeAssets?,
    val clickUrl: String?,
    val trackers: Trackers,
    val viewRule: ViewRule,
    val closeAfterS: Int,
    val reward: AdReward?,
    val label: String?,
    /** Test ad: never billed. */
    val test: Boolean,
    /** Banner refresh interval in seconds (null = SDK default, 0 = off). */
    val refreshS: Int?,
    /** Expiry on the device's monotonic clock (SystemClock.elapsedRealtime). */
    val expiresAtElapsedMs: Long,
) {
    fun isExpired(nowElapsedMs: Long): Boolean = nowElapsedMs >= expiresAtElapsedMs
}

internal sealed class AdResult {
    class Fill(val ad: AdData) : AdResult()
    object NoFill : AdResult()
    class Failure(val error: com.adfacten.ads.AdError) : AdResult()
}
