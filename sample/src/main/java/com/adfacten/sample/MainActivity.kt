package com.adfacten.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import com.adfacten.ads.AdError
import com.adfacten.ads.AdLoadCallback
import com.adfacten.ads.AdfactenBannerView
import com.adfacten.ads.AppOpenAd
import com.adfacten.ads.BannerAdListener
import com.adfacten.ads.FullScreenAdListener
import com.adfacten.ads.InterstitialAd
import com.adfacten.ads.NativeAd
import com.adfacten.ads.NativeAdListener
import com.adfacten.ads.NativeAdView
import com.adfacten.ads.RewardedAd

/**
 * Shows every format. `adb shell am start -n com.adfacten.sample/.MainActivity --es autorun banner,native,interstitial`
 * runs the listed formats automatically (used for emulator tests).
 */
class MainActivity : Activity() {
    private lateinit var banner: AdfactenBannerView
    private lateinit var nativeView: NativeAdView
    private lateinit var logView: TextView
    private var nativeAd: NativeAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        banner = findViewById(R.id.banner)
        nativeView = findViewById(R.id.native_ad)
        logView = findViewById(R.id.log)

        banner.placementId = BuildConfig.PLC_BANNER
        banner.listener = object : BannerAdListener {
            override fun onLoaded() = log("banner: loaded")
            override fun onFailed(error: AdError) = log("banner: failed $error")
            override fun onShown() = log("banner: shown")
            override fun onClicked() = log("banner: clicked")
        }

        findViewById<View>(R.id.btn_banner).setOnClickListener { banner.load() }
        findViewById<View>(R.id.btn_interstitial).setOnClickListener { interstitial() }
        findViewById<View>(R.id.btn_rewarded).setOnClickListener { rewarded() }
        findViewById<View>(R.id.btn_native).setOnClickListener { native() }
        findViewById<View>(R.id.btn_app_open).setOnClickListener { appOpen() }

        if (savedInstanceState == null) intent.getStringExtra("autorun")?.split(',')?.forEachIndexed { i, f ->
            logView.postDelayed({
                when (f.trim()) {
                    "banner" -> banner.load()
                    "native" -> native()
                    "interstitial" -> interstitial()
                    "rewarded" -> rewarded()
                    "app_open" -> appOpen()
                }
            }, 300L + i * 2500L)
        }
    }

    private fun fullScreenListener(name: String) = object : FullScreenAdListener {
        override fun onShown() = log("$name: shown")
        override fun onClicked() = log("$name: clicked")
        override fun onClosed() = log("$name: closed")
        override fun onFailedToShow(error: AdError) = log("$name: failed to show $error")
    }

    private fun interstitial() {
        log("interstitial: loading")
        InterstitialAd.load(this, BuildConfig.PLC_INTERSTITIAL, object : AdLoadCallback<InterstitialAd> {
            override fun onLoaded(ad: InterstitialAd) {
                log("interstitial: loaded")
                ad.listener = fullScreenListener("interstitial")
                ad.show(this@MainActivity)
            }
            override fun onFailed(error: AdError) = log("interstitial: failed $error")
        })
    }

    private fun rewarded() {
        log("rewarded: loading")
        RewardedAd.load(this, BuildConfig.PLC_REWARDED, object : AdLoadCallback<RewardedAd> {
            override fun onLoaded(ad: RewardedAd) {
                log("rewarded: loaded, reward=${ad.reward}")
                ad.listener = fullScreenListener("rewarded")
                // UX only: the real reward is granted by your server from the signed reward callback.
                ad.show(this@MainActivity) { reward -> log("rewarded: earned ${reward.amount} ${reward.type}") }
            }
            override fun onFailed(error: AdError) = log("rewarded: failed $error")
        }, customData = "level=3")
    }

    private fun native() {
        log("native: loading")
        NativeAd.load(this, BuildConfig.PLC_NATIVE, object : AdLoadCallback<NativeAd> {
            override fun onLoaded(ad: NativeAd) {
                log("native: loaded \"${ad.title}\"")
                nativeAd?.destroy()
                nativeAd = ad
                ad.listener = object : NativeAdListener {
                    override fun onShown() = log("native: shown")
                    override fun onClicked() = log("native: clicked")
                }
                nativeView.visibility = View.VISIBLE
                nativeView.setNativeAd(ad)
            }
            override fun onFailed(error: AdError) = log("native: failed $error")
        })
    }

    private fun appOpen() {
        log("app_open: loading")
        AppOpenAd.load(this, BuildConfig.PLC_APP_OPEN, object : AdLoadCallback<AppOpenAd> {
            override fun onLoaded(ad: AppOpenAd) {
                log("app_open: loaded")
                ad.listener = fullScreenListener("app_open")
                ad.show(this@MainActivity)
            }
            override fun onFailed(error: AdError) = log("app_open: failed $error")
        })
    }

    override fun onDestroy() {
        nativeAd?.destroy()
        super.onDestroy()
    }

    private fun log(msg: String) {
        Log.i("AdfactenSample", msg)
        logView.append("$msg\n")
    }
}
