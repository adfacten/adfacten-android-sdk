package com.adfacten.ads

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.adfacten.ads.internal.ActivityWatcher
import com.adfacten.ads.internal.AdFormat
import com.adfacten.ads.internal.AdLoader
import com.adfacten.ads.internal.ClickOpener
import com.adfacten.ads.internal.CreativeKind
import com.adfacten.ads.internal.CreativeWebView
import com.adfacten.ads.internal.LoadedAd
import com.adfacten.ads.internal.Logger
import com.adfacten.ads.internal.Sdk
import com.adfacten.ads.internal.adLabel
import com.adfacten.ads.internal.dp
import com.adfacten.ads.internal.findActivity
import com.adfacten.ads.internal.safe
import com.adfacten.ads.internal.track.AdEventTracker
import com.adfacten.ads.internal.track.VisibilityTracker

/**
 * A banner ad view.
 *
 * XML:
 * ```
 * <com.adfacten.ads.AdfactenBannerView
 *     android:layout_width="wrap_content" android:layout_height="wrap_content"
 *     app:adf_placementId="plc_…" app:adf_adSize="banner" />
 * ```
 * then `banner.load()`. Code: `AdfactenBannerView(context).apply { placementId = "plc_…"; adSize = AdSize.BANNER }`.
 *
 * The impression is recorded only when the creative is really on screen. Auto-refresh (placement setting,
 * 30–120 s) counts only time while the banner is ≥ 50 % visible and its activity is resumed; it stops
 * automatically when the activity is destroyed. Call [destroy] if you remove the view yourself earlier.
 */
public class AdfactenBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    public var placementId: String? = null
    public var adSize: AdSize = AdSize.BANNER
        set(value) { field = value; requestLayout() }
    public var listener: BannerAdListener? = null
    /** Set to false to disable refreshing even if the placement has a refresh interval. */
    public var autoRefresh: Boolean = true

    private class Showing(val ad: LoadedAd, val events: AdEventTracker, val tracker: VisibilityTracker, val content: View)

    private var showing: Showing? = null
    private var loading = false
    private var destroyed = false
    private var paused = false
    private var hostResumed = true
    private var visibleSeconds = 0
    private var watcher: ActivityWatcher? = null

    private val ticker = object : Runnable {
        override fun run() {
            safe("banner tick") { tick() }
            if (!destroyed && isAttachedToWindow) Sdk.main.postDelayed(this, 1000)
        }
    }

    init {
        if (attrs != null) safe("banner attrs") {
            val a = context.obtainStyledAttributes(attrs, R.styleable.AdfactenBannerView, defStyleAttr, 0)
            try {
                placementId = a.getString(R.styleable.AdfactenBannerView_adf_placementId)
                adSize = AdSize.fromAttr(a.getInt(R.styleable.AdfactenBannerView_adf_adSize, 0))
                autoRefresh = a.getBoolean(R.styleable.AdfactenBannerView_adf_autoRefresh, true)
            } finally {
                a.recycle()
            }
        }
    }

    /** Requests a banner. Results arrive through [listener] on the main thread. */
    public fun load() {
        safe("banner load") { loadInternal(isRefresh = false) }
    }

    /** Stops refreshing (e.g. when you hide the banner yourself). Activity pause is handled automatically. */
    public fun pause() {
        paused = true
    }

    public fun resume() {
        paused = false
    }

    /** Releases the creative and stops all timers. The view cannot be reused afterwards. */
    public fun destroy() {
        safe("banner destroy") {
            destroyed = true
            Sdk.main.removeCallbacks(ticker)
            watcher?.unregister()
            watcher = null
            clearCreative()
        }
    }

    private fun loadInternal(isRefresh: Boolean) {
        if (destroyed) {
            Logger.w("load() on a destroyed banner")
            return
        }
        val plc = placementId
        if (plc.isNullOrBlank()) {
            val l = listener
            Sdk.post { l?.onFailed(AdError(AdError.INVALID_REQUEST, "placementId is not set")) }
            return
        }
        if (loading) return
        loading = true
        visibleSeconds = 0
        val target = context.dp(adSize.width) to context.dp(adSize.height)
        AdLoader.load(plc, AdFormat.BANNER, listOf(adSize.wire), null, target) { ad, err ->
            loading = false
            if (destroyed) return@load
            val l = listener
            if (ad != null) {
                safe("banner render") { show(ad) }
                l?.onLoaded()
            } else if (!isRefresh || showing == null) {
                l?.onFailed(err ?: AdError(AdError.INTERNAL_ERROR, "unknown"))
            } else {
                Logger.i("banner refresh: $err; keeping the current ad")
            }
        }
    }

    private fun show(ad: LoadedAd) {
        val st = Sdk.state ?: return
        val events = AdEventTracker(ad.data, st.beacons, st.sdkId) { System.currentTimeMillis() }
        val onClick = {
            safe("banner click") {
                if (events.rendered && ClickOpener.open(context, ad.data.clickUrl)) {
                    val l = listener
                    Sdk.post { l?.onClicked() }
                }
            }
        }
        val content = FrameLayout(context)
        val creative: View = when (ad.data.kind) {
            CreativeKind.IMAGE -> ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(ad.bitmap)
                contentDescription = context.getString(R.string.adf_label)
                setOnClickListener { onClick() }
            }
            CreativeKind.HTML -> CreativeWebView(context) { onClick() }.apply { loadCreative(ad.data.html!!) }
            else -> return
        }
        content.addView(creative, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(adLabel(context, ad.data.label), LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ))
        val tracker = VisibilityTracker(
            view = content,
            rule = ad.data.viewRule,
            onRender = {
                if (events.render()) {
                    val l = listener
                    Sdk.post { l?.onShown() }
                }
            },
            onView = { events.view() },
        )
        clearCreative()
        addView(content, LayoutParams(context.dp(adSize.width), context.dp(adSize.height), Gravity.CENTER))
        showing = Showing(ad, events, tracker, content)
        tracker.start()
        startTicker()
    }

    private fun clearCreative() {
        val s = showing ?: return
        showing = null
        s.tracker.stop()
        removeView(s.content)
        val c = s.content as? ViewGroup
        c?.let { for (i in 0 until it.childCount) (it.getChildAt(i) as? CreativeWebView)?.destroy() }
    }

    private fun tick() {
        val s = showing ?: return
        if (destroyed || loading) return
        if (!s.events.rendered && s.ad.isExpired) {
            // Never shown on screen and now stale: replace it instead of billing an expired impression.
            loadInternal(isRefresh = true)
            return
        }
        val refresh = refreshSeconds(s.ad) ?: return
        if (!autoRefresh || paused || !hostResumed) return
        if (s.tracker.visibleRatio() >= 0.5f) visibleSeconds++
        if (visibleSeconds >= refresh) loadInternal(isRefresh = true)
    }

    /** Placement refresh interval from the response, clamped to 30–120 s; 0 means off. Default 60 s. */
    private fun refreshSeconds(ad: LoadedAd): Int? {
        val r = ad.data.refreshS ?: DEFAULT_REFRESH_S
        if (r <= 0) return null
        return r.coerceIn(30, 120)
    }

    private fun startTicker() {
        Sdk.main.removeCallbacks(ticker)
        if (isAttachedToWindow && !destroyed) Sdk.main.postDelayed(ticker, 1000)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        safe("banner attach") {
            if (watcher == null) {
                context.findActivity()?.let { act ->
                    watcher = ActivityWatcher(act, onResumed = { hostResumed = it }, onDestroyed = { destroy() }).also { it.register() }
                }
            }
            startTicker()
        }
    }

    override fun onDetachedFromWindow() {
        safe("banner detach") { Sdk.main.removeCallbacks(ticker) }
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(context.dp(adSize.width), widthMeasureSpec)
        val h = resolveSize(context.dp(adSize.height), heightMeasureSpec)
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    private companion object {
        const val DEFAULT_REFRESH_S = 60
    }
}
