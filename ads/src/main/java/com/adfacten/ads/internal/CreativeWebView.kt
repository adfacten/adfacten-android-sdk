package com.adfacten.ads.internal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.MotionEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Sandboxed WebView for HTML5 creatives: JavaScript on (HTML5 needs it) but no JS bridge, no file/content
 * access, no geolocation, no popups, no autoplay with sound. Navigation is never done inside the WebView:
 * a user-initiated navigation counts as the ad click and is opened externally via [onClick];
 * navigations without a user gesture (auto-redirects) are blocked.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class CreativeWebView(context: Context, private val onClick: () -> Unit) : WebView(context) {
    private var lastTouchAt = 0L

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        with(settings) {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            saveFormData = false
            domStorageEnabled = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val gesture = if (Build.VERSION.SDK_INT >= 24) request.hasGesture() else recentTouch()
                return intercept(request.isForMainFrame, gesture || recentTouch())
            }

            @Deprecated("pre-24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = intercept(true, recentTouch())

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Block local schemes defensively (file://, content://).
                val scheme = request.url?.scheme?.lowercase()
                return if (scheme == "file" || scheme == "content") WebResourceResponse("text/plain", "utf-8", null) else null
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Logger.w("creative WebView renderer gone; removing it")
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                return true // never let a creative crash the host app
            }
        }
    }

    private fun intercept(mainFrame: Boolean, gesture: Boolean): Boolean {
        if (!mainFrame) return false // iframes inside the creative load normally
        if (gesture) onClick() else Logger.w("creative tried to navigate without a user gesture; blocked")
        return true
    }

    private fun recentTouch(): Boolean = android.os.SystemClock.elapsedRealtime() - lastTouchAt < 1500

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) lastTouchAt = android.os.SystemClock.elapsedRealtime()
        return super.onTouchEvent(event)
    }

    fun loadCreative(html: String) {
        loadDataWithBaseURL("https://cdn.adfacten.com/", html, "text/html", "utf-8", null)
    }
}
