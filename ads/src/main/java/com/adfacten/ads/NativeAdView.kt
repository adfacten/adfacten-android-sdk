package com.adfacten.ads

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.adfacten.ads.internal.Logger
import com.adfacten.ads.internal.Sdk
import com.adfacten.ads.internal.adLabel
import com.adfacten.ads.internal.ClickOpener
import com.adfacten.ads.internal.dp
import com.adfacten.ads.internal.safe
import com.adfacten.ads.internal.track.AdEventTracker
import com.adfacten.ads.internal.track.VisibilityTracker

/**
 * Container for a [NativeAd]. Put your own views inside it, tell it which view shows which asset (in XML with
 * `app:adf_titleView="@id/…"` etc. or with the properties), then call [setNativeAd].
 * The whole container is tracked for the impression (on screen) and the view (≥ 50 % for ≥ 1 s);
 * taps on the asset views and the container open the ad.
 */
public class NativeAdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    public var titleView: TextView? = null
    public var bodyView: TextView? = null
    public var brandView: TextView? = null
    /** Usually a Button; if it is a TextView, its text is set to the call to action. */
    public var callToActionView: View? = null
    public var imageView: ImageView? = null
    public var iconView: ImageView? = null
    /** Where to show the "Reklam" disclosure. If null, the SDK adds a small badge in the top corner. */
    public var adLabelView: TextView? = null

    private val idRefs = IntArray(7)
    private var ad: NativeAd? = null
    private var tracker: VisibilityTracker? = null
    private var autoLabel: TextView? = null

    init {
        if (attrs != null) safe("NativeAdView attrs") {
            val a = context.obtainStyledAttributes(attrs, R.styleable.AdfNativeAdView, defStyleAttr, 0)
            try {
                idRefs[0] = a.getResourceId(R.styleable.AdfNativeAdView_adf_titleView, 0)
                idRefs[1] = a.getResourceId(R.styleable.AdfNativeAdView_adf_bodyView, 0)
                idRefs[2] = a.getResourceId(R.styleable.AdfNativeAdView_adf_brandView, 0)
                idRefs[3] = a.getResourceId(R.styleable.AdfNativeAdView_adf_callToActionView, 0)
                idRefs[4] = a.getResourceId(R.styleable.AdfNativeAdView_adf_imageView, 0)
                idRefs[5] = a.getResourceId(R.styleable.AdfNativeAdView_adf_iconView, 0)
                idRefs[6] = a.getResourceId(R.styleable.AdfNativeAdView_adf_adLabelView, 0)
            } finally {
                a.recycle()
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        safe("NativeAdView inflate") {
            fun <T : View> find(i: Int): T? = if (idRefs[i] != 0) findViewById(idRefs[i]) else null
            titleView = titleView ?: find(0)
            bodyView = bodyView ?: find(1)
            brandView = brandView ?: find(2)
            callToActionView = callToActionView ?: find(3)
            imageView = imageView ?: find(4)
            iconView = iconView ?: find(5)
            adLabelView = adLabelView ?: find(6)
        }
    }

    /**
     * Binds [nativeAd] (or unbinds with null). An ad can be bound to one view at a time: binding it here detaches it
     * from its previous view. Binding the same ad again never records a second impression. Expired ads are not bound.
     */
    public fun setNativeAd(nativeAd: NativeAd?) {
        safe("setNativeAd") { bind(nativeAd) }
    }

    public fun getNativeAd(): NativeAd? = ad

    private fun bind(nativeAd: NativeAd?) {
        unbind()
        if (nativeAd == null) return
        if (nativeAd.isExpired) {
            Logger.e("NativeAd expired (older than ~55 min); load a new one")
            return
        }
        if (nativeAd.boundView != null && nativeAd.boundView !== this) nativeAd.boundView?.setNativeAd(null)
        val st = Sdk.state ?: return
        ad = nativeAd
        nativeAd.boundView = this

        titleView?.text = nativeAd.title
        bodyView?.let { setTextOrHide(it, nativeAd.body) }
        brandView?.let { setTextOrHide(it, nativeAd.brand) }
        (callToActionView as? TextView)?.let { setTextOrHide(it, nativeAd.callToAction) }
        imageView?.setImageBitmap(nativeAd.image)
        iconView?.let { v -> v.setImageBitmap(nativeAd.icon); v.visibility = if (nativeAd.icon == null) View.GONE else View.VISIBLE }
        val labelView = adLabelView
        if (labelView != null) {
            labelView.text = nativeAd.adLabel
            labelView.visibility = View.VISIBLE
        } else {
            autoLabel = adLabel(context, nativeAd.adLabel).also {
                addView(it, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    setMargins(0, context.dp(4), context.dp(4), 0)
                })
            }
        }

        val ev = nativeAd.eventTracker { AdEventTracker(nativeAd.loaded.data, st.beacons, st.sdkId) { System.currentTimeMillis() } }
        val click = OnClickListener { onClick(nativeAd, ev) }
        setOnClickListener(click)
        listOfNotNull(titleView, bodyView, brandView, callToActionView, imageView, iconView).forEach { it.setOnClickListener(click) }

        tracker = VisibilityTracker(
            view = this,
            rule = nativeAd.loaded.data.viewRule,
            onRender = {
                if (ev.render()) {
                    val l = nativeAd.listener
                    Sdk.post { l?.onShown() }
                }
            },
            onView = { ev.view() },
        ).also { it.start() }
    }

    private fun onClick(nativeAd: NativeAd, ev: AdEventTracker) = safe("native click") {
        if (!ev.rendered) return@safe
        if (ClickOpener.open(context, nativeAd.loaded.data.clickUrl)) {
            val l = nativeAd.listener
            Sdk.post { l?.onClicked() }
        }
    }

    private fun unbind() {
        tracker?.stop()
        tracker = null
        autoLabel?.let { removeView(it) }
        autoLabel = null
        setOnClickListener(null)
        isClickable = false
        listOfNotNull(titleView, bodyView, brandView, callToActionView, imageView, iconView).forEach {
            it.setOnClickListener(null)
            it.isClickable = it is android.widget.Button
        }
        ad?.boundView = null
        ad = null
    }

    private fun setTextOrHide(v: TextView, text: String) {
        v.text = text
        v.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }
}
