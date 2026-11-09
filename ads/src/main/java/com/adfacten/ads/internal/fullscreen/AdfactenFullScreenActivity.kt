package com.adfacten.ads.internal.fullscreen

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.adfacten.ads.AdError
import com.adfacten.ads.R
import com.adfacten.ads.internal.AdFormat
import com.adfacten.ads.internal.ClickOpener
import com.adfacten.ads.internal.CreativeKind
import com.adfacten.ads.internal.CreativeWebView
import com.adfacten.ads.internal.FitImageView
import com.adfacten.ads.internal.Logger
import com.adfacten.ads.internal.Sdk
import com.adfacten.ads.internal.VideoSupport
import com.adfacten.ads.internal.adLabel
import com.adfacten.ads.internal.dp
import com.adfacten.ads.internal.safe
import com.adfacten.ads.internal.track.AdEventTracker
import com.adfacten.ads.internal.track.VisibilityTracker
import com.adfacten.ads.internal.vast.VastParser
import com.adfacten.ads.spi.VideoPlayer

/**
 * Shows interstitial, rewarded and app-open ads (image, HTML or VAST video). Survives rotation without
 * recreation (configChanges), handles back, pauses with the app, and never throws into the host.
 */
internal class AdfactenFullScreenActivity : Activity() {
    private var token: String? = null
    private var session: FullScreenSession? = null
    private var tracker: AdEventTracker? = null
    private var visibility: VisibilityTracker? = null
    private var webView: CreativeWebView? = null
    private var player: VideoPlayer? = null

    private lateinit var root: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var closeButton: ImageView
    private lateinit var countdown: TextView
    private var muteButton: ImageView? = null
    private var spinner: ProgressBar? = null

    private var rendered = false
    private var completed = false
    private var closing = false
    private var resumed = false
    private var dialogOpen = false
    private var firstFrame = false
    private var videoFailed = false
    private var muted = false
    private var activeMs = 0L
    private var lastTickAt = 0L
    private val quartiles = BooleanArray(3)
    private var backCallback: Any? = null

    private val isVideo get() = session?.loaded?.data?.kind == CreativeKind.VIDEO
    private val isRewarded get() = session?.format == AdFormat.REWARDED

    private val ticker = object : Runnable {
        override fun run() {
            safe("fullscreen tick") { tick() }
            if (!isFinishing) Sdk.main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            token = intent?.getStringExtra(EXTRA_TOKEN)
            val s = FullScreenRegistry.get(token)
            if (s == null) { // process was recreated or bogus intent
                finish()
                return
            }
            s.claimed = true
            session = s
            if (s.loaded.isExpired) {
                fail(AdError(AdError.AD_EXPIRED, "ad expired before it was shown"))
                return
            }
            val st = Sdk.state ?: run { fail(AdError(AdError.NOT_INITIALIZED, "SDK not initialized")); return }
            tracker = AdEventTracker(s.loaded.data, st.beacons, st.sdkId) { System.currentTimeMillis() }
            buildUi(s)
            if (!isFinishing) {
                setupWindow()
                registerBack()
            }
        } catch (t: Throwable) {
            Logger.e("full-screen ad could not be displayed", t)
            fail(AdError(AdError.SHOW_FAILED, "internal error: ${t.javaClass.simpleName}"))
        }
    }

    private fun setupWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun buildUi(s: FullScreenSession) {
        val data = s.loaded.data
        root = FrameLayout(this).apply { setBackgroundColor(if (data.kind == CreativeKind.VIDEO) Color.BLACK else 0xE6000000.toInt()) }
        val creative: View = when (data.kind) {
            CreativeKind.IMAGE -> FitImageView(this).apply {
                setImageBitmap(s.loaded.bitmap)
                contentDescription = getString(R.string.adf_label)
                setOnClickListener { onAdClick() }
            }
            CreativeKind.HTML -> CreativeWebView(this) { onAdClick() }.also {
                webView = it
                it.loadCreative(data.html!!)
            }
            CreativeKind.VIDEO -> {
                val p = VideoSupport.create(this)
                if (p == null) {
                    fail(AdError(AdError.VIDEO_NOT_SUPPORTED, "video player unavailable"))
                    return
                }
                player = p
                setupPlayer(p, s)
                p.view.apply { setOnClickListener { onAdClick() } }
            }
            CreativeKind.NATIVE -> {
                fail(AdError(AdError.SHOW_FAILED, "native ads cannot be shown full screen"))
                return
            }
        }
        root.addView(creative, FrameLayout.LayoutParams(
            if (data.kind == CreativeKind.IMAGE) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            if (data.kind == CreativeKind.IMAGE) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        ).apply { if (data.kind == CreativeKind.IMAGE) setMargins(dp(16), dp(56), dp(16), dp(56)) })

        if (data.kind == CreativeKind.VIDEO) {
            spinner = ProgressBar(this).also { root.addView(it, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)) }
        }

        overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.addView(adLabel(this, data.label), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(12), dp(18), 0, 0) })

        val topRight = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        countdown = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = circle()
            visibility = View.GONE
        }
        closeButton = ImageView(this).apply {
            setImageResource(R.drawable.adf_ic_close)
            scaleType = ImageView.ScaleType.CENTER
            background = circle()
            contentDescription = getString(R.string.adf_close)
            visibility = View.GONE
            setOnClickListener { safe("close click") { requestClose() } }
        }
        topRight.addView(countdown, LinearLayout.LayoutParams(dp(36), dp(36)))
        topRight.addView(closeButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        overlay.addView(topRight, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, dp(12), dp(12), 0) })

        if (data.kind == CreativeKind.VIDEO) {
            muteButton = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER
                background = circle()
                setOnClickListener { safe("mute") { setMuted(!muted) } }
            }
            overlay.addView(muteButton, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(12), 0, 0, dp(16))
            })
            if (data.clickUrl != null || s.loaded.vast?.clickThrough != null) {
                val cta = TextView(this).apply {
                    text = getString(R.string.adf_learn_more)
                    setTextColor(Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.WHITE) }
                    setOnClickListener { onAdClick() }
                }
                overlay.addView(cta, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
                ).apply { setMargins(0, 0, dp(12), dp(16)) })
            }
            setMuted(!isRewarded) // interstitial video starts muted, rewarded with sound
        }

        overlay.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            var l = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            var t = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            var r = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            var b = insets.systemWindowInsetBottom
            if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.let {
                l = maxOf(l, it.safeInsetLeft); t = maxOf(t, it.safeInsetTop); r = maxOf(r, it.safeInsetRight); b = maxOf(b, it.safeInsetBottom)
            }
            v.setPadding(l, t, r, b)
            insets
        }
        setContentView(root)

        visibility = VisibilityTracker(
            view = creative,
            rule = data.viewRule,
            onRender = { onRendered() },
            onView = { tracker?.view() },
            isActive = { resumed && (!isVideo || (firstFrame && player?.isPlaying() == true)) },
        ).also { it.start() }
    }

    private fun setupPlayer(p: VideoPlayer, s: FullScreenSession) {
        p.setListener(object : VideoPlayer.Listener {
            override fun onFirstFrame() = safe("first frame") {
                firstFrame = true
                spinner?.visibility = View.GONE
            }

            override fun onCompleted() = safe("video complete") { onComplete() }

            override fun onError(message: String) = safe("video error") {
                Logger.w("video playback failed: $message")
                videoFailed = true
                s.loaded.vast?.let { v -> tracker?.external("error", v.errors.map { VastParser.expandMacros(it, System.currentTimeMillis(), 405) }) }
                if (!rendered) {
                    fail(AdError(AdError.SHOW_FAILED, "video playback failed: $message"))
                } else {
                    spinner?.visibility = View.GONE
                    showClose()
                }
            }
        })
        p.prepare(s.loaded.media!!.url)
    }

    private fun onRendered() {
        if (rendered) return
        rendered = true
        val s = session ?: return
        tracker?.render()
        s.loaded.vast?.let { v ->
            val now = System.currentTimeMillis()
            tracker?.external("impression", v.impressions.map { VastParser.expandMacros(it, now) })
            tracker?.external("creativeView", v.urls("creativeView").map { VastParser.expandMacros(it, now) })
            tracker?.external("start", v.urls("start").map { VastParser.expandMacros(it, now, positionMs = 0) })
        }
        s.shown()
        lastTickAt = SystemClock.elapsedRealtime()
        if (closeAfterMs() <= 0L && !isRewarded) showClose()
    }

    /** Called every 250 ms: countdown, quartiles, close/reward timing. */
    private fun tick() {
        val s = session ?: return
        val now = SystemClock.elapsedRealtime()
        if (rendered && resumed && !dialogOpen && lastTickAt > 0) activeMs += now - lastTickAt
        lastTickAt = now
        if (!rendered || closing) return

        val p = player
        if (p != null) {
            val dur = p.durationMs().takeIf { it > 0 } ?: s.loaded.vast?.durationMs ?: 0L
            val pos = p.currentPositionMs()
            if (dur > 0 && firstFrame && !completed) {
                val ratio = pos.toDouble() / dur
                val events = arrayOf("firstQuartile", "midpoint", "thirdQuartile")
                for (i in 0..2) if (!quartiles[i] && ratio >= (i + 1) * 0.25) {
                    quartiles[i] = true
                    s.loaded.vast?.let { v -> tracker?.external(events[i], v.urls(events[i]).map { VastParser.expandMacros(it, System.currentTimeMillis(), positionMs = pos) }) }
                }
            }
            val closeAt = closeAfterMs()
            if (!completed && !videoFailed) {
                val remainingVideo = ((dur - pos).coerceAtLeast(0) + 999) / 1000
                val remainingClose = ((closeAt - pos).coerceAtLeast(0) + 999) / 1000
                if (isRewarded) {
                    setCountdown(if (dur > 0) remainingVideo else null)
                    if (pos >= closeAt) showClose()
                } else if (pos >= closeAt) {
                    setCountdown(null); showClose()
                } else {
                    setCountdown(remainingClose)
                }
            }
        } else {
            val target = if (isRewarded) rewardMs() else closeAfterMs()
            if (activeMs >= target) {
                if (isRewarded) onComplete()
                setCountdown(null)
                showClose()
            } else {
                setCountdown((target - activeMs + 999) / 1000)
                if (isRewarded && activeMs >= closeAfterMs()) showClose()
            }
        }
    }

    private fun closeAfterMs(): Long {
        val s = session ?: return 0
        val server = s.loaded.data.closeAfterS * 1000L
        if (server > 0) return server
        return s.loaded.vast?.skipOffsetMs ?: 0L
    }

    /** Non-video rewarded ads: the user must look at the creative this long (close_after_s, default 5 s). */
    private fun rewardMs(): Long = (session?.loaded?.data?.closeAfterS ?: 0).let { if (it > 0) it * 1000L else 5_000L }

    private fun onComplete() {
        if (completed) return
        completed = true
        val s = session ?: return
        tracker?.complete()
        s.loaded.vast?.let { v -> tracker?.external("complete", v.urls("complete").map { VastParser.expandMacros(it, System.currentTimeMillis()) }) }
        if (isRewarded) s.rewarded()
        setCountdown(null)
        showClose()
    }

    private fun setCountdown(seconds: Long?) {
        if (seconds == null) {
            countdown.visibility = View.GONE
        } else {
            countdown.text = String.format(java.util.Locale.getDefault(), "%d", seconds)
            countdown.visibility = View.VISIBLE
        }
    }

    private fun showClose() {
        if (closeButton.visibility != View.VISIBLE) closeButton.visibility = View.VISIBLE
    }

    private fun setMuted(value: Boolean) {
        muted = value
        player?.setMuted(value)
        muteButton?.setImageResource(if (value) R.drawable.adf_ic_volume_off else R.drawable.adf_ic_volume_on)
        muteButton?.contentDescription = getString(if (value) R.string.adf_unmute else R.string.adf_mute)
        if (rendered) session?.loaded?.vast?.let { v ->
            val ev = if (value) "mute" else "unmute"
            tracker?.external("$ev@${SystemClock.elapsedRealtime()}", v.urls(ev))
        }
    }

    private fun onAdClick() = safe("ad click") {
        val s = session ?: return@safe
        if (!rendered || closing) return@safe
        val url = s.loaded.data.clickUrl ?: s.loaded.vast?.clickThrough
        if (ClickOpener.open(this, url)) {
            s.loaded.vast?.let { v -> tracker?.external("click", v.clickTracking.map { VastParser.expandMacros(it, System.currentTimeMillis()) }) }
            s.clicked()
        }
    }

    /** Close button / back: rewarded ads ask before forfeiting the reward. */
    private fun requestClose() {
        if (closing) return
        if (isRewarded && !completed && rendered && !videoFailed) {
            confirmForfeit()
            return
        }
        if (closeButton.visibility == View.VISIBLE || completed) close()
    }

    private fun confirmForfeit() {
        if (dialogOpen) return
        dialogOpen = true
        player?.pause()
        AlertDialog.Builder(this, if (Build.VERSION.SDK_INT >= 22) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(R.string.adf_reward_confirm_title)
            .setMessage(R.string.adf_reward_confirm_message)
            .setPositiveButton(R.string.adf_reward_confirm_resume) { _, _ -> }
            .setNegativeButton(R.string.adf_reward_confirm_close) { _, _ -> safe("forfeit") { close() } }
            .setOnDismissListener {
                safe("dialog dismiss") {
                    dialogOpen = false
                    if (!closing && resumed && !completed) player?.play()
                    hideSystemBars()
                }
            }
            .show()
    }

    private fun close() {
        if (closing) return
        closing = true
        val s = session
        if (rendered) {
            tracker?.close()
            s?.loaded?.vast?.let { v ->
                val now = System.currentTimeMillis()
                if (!completed && v.skipOffsetMs != null) tracker?.external("skip", v.urls("skip").map { VastParser.expandMacros(it, now) })
                tracker?.external("closeLinear", v.urls("closeLinear").map { VastParser.expandMacros(it, now) })
                tracker?.external("close", v.urls("close").map { VastParser.expandMacros(it, now) })
            }
            s?.closed()
        } else {
            s?.failed(AdError(AdError.SHOW_FAILED, "closed before the ad was displayed"))
        }
        finish()
    }

    private fun fail(error: AdError) {
        closing = true
        session?.failed(error)
        finish()
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { safe("back") { requestClose() } }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        safe("back") { requestClose() } // never super: the ad decides when it may close
    }

    override fun onResume() {
        super.onResume()
        safe("fullscreen onResume") {
            resumed = true
            lastTickAt = SystemClock.elapsedRealtime()
            hideSystemBars()
            if (!completed && !dialogOpen) player?.play()
            Sdk.main.removeCallbacks(ticker)
            Sdk.main.post(ticker)
        }
    }

    override fun onPause() {
        safe("fullscreen onPause") {
            resumed = false
            player?.pause()
            Sdk.main.removeCallbacks(ticker)
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) safe("focus") { hideSystemBars() }
    }

    override fun onDestroy() {
        safe("fullscreen onDestroy") {
            Sdk.main.removeCallbacks(ticker)
            visibility?.stop()
            player?.release()
            player = null
            webView?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
            webView = null
            if (Build.VERSION.SDK_INT >= 33) (backCallback as? OnBackInvokedCallback)?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
            if (!closing && session != null) {
                // Finished by the system (e.g. task removed): still report the close.
                if (rendered) tracker?.close()
                session?.closed()
            }
            FullScreenRegistry.remove(token)
        }
        super.onDestroy()
    }

    private fun circle() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x99000000.toInt())
    }

    companion object {
        const val EXTRA_TOKEN = "com.adfacten.ads.TOKEN"
    }
}
