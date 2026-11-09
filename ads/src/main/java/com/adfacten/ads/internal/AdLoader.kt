package com.adfacten.ads.internal

import android.graphics.Bitmap
import android.os.SystemClock
import com.adfacten.ads.AdError
import com.adfacten.ads.BuildConfig
import com.adfacten.ads.internal.vast.VastAd
import com.adfacten.ads.internal.vast.VastException
import com.adfacten.ads.internal.vast.VastMediaFile
import com.adfacten.ads.internal.vast.VastParser
import java.io.IOException

/** A served ad with its creative already downloaded/parsed, ready to render without waiting. */
internal class LoadedAd(
    val placementId: String,
    val data: AdData,
    val bitmap: Bitmap? = null,
    val iconBitmap: Bitmap? = null,
    val vast: VastAd? = null,
    val media: VastMediaFile? = null,
) {
    val isExpired: Boolean get() = data.isExpired(SystemClock.elapsedRealtime())
}

internal object AdLoader {
    /** Loads on a background thread; [callback] runs on the main thread inside the SDK's safety net. */
    fun load(
        placementId: String,
        format: AdFormat,
        sizes: List<String>,
        custom: String?,
        imageTarget: Pair<Int, Int>?,
        callback: (LoadedAd?, AdError?) -> Unit,
    ) {
        val deliver = { ad: LoadedAd?, err: AdError? ->
            Sdk.post { callback(ad, err) } // host code: exceptions are the app's own
        }
        val st = Sdk.state
        if (st == null) {
            deliver(null, AdError(AdError.NOT_INITIALIZED, "call Adfacten.initialize() first"))
            return
        }
        if (placementId.isBlank()) {
            deliver(null, AdError(AdError.INVALID_REQUEST, "placement id is empty"))
            return
        }
        Sdk.net.execute {
            val (ad, err) = try {
                loadBlocking(st, placementId, format, sizes, custom, imageTarget)
            } catch (t: Throwable) {
                Logger.e("ad load failed", t)
                null to AdError(AdError.INTERNAL_ERROR, t.javaClass.simpleName)
            }
            if (err != null) Logger.i("load $placementId (${format.wire}) failed: $err") else Logger.i("load $placementId (${format.wire}) filled: ${ad?.data?.id?.take(12)}…")
            deliver(ad, err)
        }
    }

    private fun loadBlocking(
        st: SdkState,
        placementId: String,
        format: AdFormat,
        sizes: List<String>,
        custom: String?,
        imageTarget: Pair<Int, Int>?,
    ): Pair<LoadedAd?, AdError?> {
        val wait = st.backoff.remainingMs(placementId)
        if (wait > 0) return null to AdError(AdError.NO_FILL, "no fill; next request allowed in ${(wait + 999) / 1000} s")

        val cfg = st.config
        val consent = st.device.consent()
        val device = st.device.snapshot(st.sdkId, cfg.childDirected, consent)
        val params = AdRequestParams(
            appKey = st.appKey,
            placement = placementId,
            format = format,
            sizes = sizes,
            test = cfg.testMode,
            coppa = cfg.childDirected,
            userId = st.userId,
            custom = custom,
            videoSupported = VideoSupport.isAvailable,
        )
        val body = AdRequestBuilder.build(params, device, st.device.app, consent, BuildConfig.SDK_VERSION).toString()
        Logger.d("POST /m/v1/ad $body")

        val resp = try {
            st.http.postJson(cfg.endpoint.trimEnd('/') + "/m/v1/ad", body)
        } catch (e: IOException) {
            return null to AdError(AdError.NETWORK_ERROR, e.message ?: e.javaClass.simpleName)
        } finally {
            st.beacons.flush()
        }
        val result = AdResponseParser.parse(
            resp.code, resp.bodyString(), format, resp.dateMs(), System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        )
        val data = when (result) {
            is AdResult.NoFill -> {
                st.backoff.onNoFill(placementId)
                return null to AdError(AdError.NO_FILL, "no fill")
            }
            is AdResult.Failure -> {
                when (result.error.code) {
                    AdError.SERVER_ERROR -> st.backoff.onServerError(placementId)
                    AdError.REJECTED, AdError.INVALID_REQUEST -> st.backoff.onRejected(placementId)
                }
                return null to result.error
            }
            is AdResult.Fill -> result.ad
        }
        st.backoff.onFill(placementId)

        if ((format == AdFormat.NATIVE) != (data.kind == CreativeKind.NATIVE)) {
            return null to AdError(AdError.INVALID_RESPONSE, "kind ${data.kind.wire} cannot be shown as ${format.wire}")
        }
        val density = st.appContext.resources.displayMetrics.density
        return when (data.kind) {
            CreativeKind.IMAGE -> {
                val img = data.image!!
                val (tw, th) = imageTarget ?: (st.appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels })
                val bmp = ImageLoader.fetch(st, img.urlFor(density), tw, th) ?: img.fallback?.let { ImageLoader.fetch(st, it, tw, th) }
                if (bmp == null) null to AdError(AdError.NETWORK_ERROR, "creative image could not be downloaded")
                else LoadedAd(placementId, data, bitmap = bmp) to null
            }
            CreativeKind.HTML -> LoadedAd(placementId, data) to null
            CreativeKind.NATIVE -> {
                val dm = st.appContext.resources.displayMetrics
                val n = data.native!!
                val main = n.image?.let { ImageLoader.fetch(st, it.urlFor(density), dm.widthPixels, dm.widthPixels) }
                val icon = n.icon?.let { ImageLoader.fetch(st, it.urlFor(density), (96 * density).toInt(), (96 * density).toInt()) }
                LoadedAd(placementId, data, bitmap = main, iconBitmap = icon) to null
            }
            CreativeKind.VIDEO -> {
                if (!VideoSupport.isAvailable) return null to AdError(AdError.VIDEO_NOT_SUPPORTED, "add com.adfacten:ads-video to show video ads")
                val vast = try {
                    VastParser.parse(data.vast!!)
                } catch (e: VastException) {
                    return null to AdError(AdError.INVALID_RESPONSE, e.message ?: "invalid VAST")
                }
                val dm = st.appContext.resources.displayMetrics
                val media = VastParser.selectMediaFile(vast.mediaFiles, dm.widthPixels, dm.heightPixels, st.device.isCellular())
                    ?: return null to AdError(AdError.INVALID_RESPONSE, "VAST has no playable mp4")
                LoadedAd(placementId, data, vast = vast, media = media) to null
            }
        }
    }
}

/** Whether the optional `ads-video` artifact is on the classpath. */
internal object VideoSupport {
    const val PLAYER_CLASS = "com.adfacten.ads.video.ExoVideoPlayer"

    val isAvailable: Boolean by lazy {
        try {
            // ads-video depends on Media3, which needs API 23+.
            if (android.os.Build.VERSION.SDK_INT < 23) return@lazy false
            Class.forName(PLAYER_CLASS)
            Class.forName("androidx.media3.exoplayer.ExoPlayer")
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun create(context: android.content.Context): com.adfacten.ads.spi.VideoPlayer? = try {
        Class.forName(PLAYER_CLASS).getConstructor(android.content.Context::class.java).newInstance(context) as com.adfacten.ads.spi.VideoPlayer
    } catch (t: Throwable) {
        Logger.e("video player could not be created", t)
        null
    }
}
