package com.adfacten.sample;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import com.adfacten.ads.AdError;
import com.adfacten.ads.AdLoadCallback;
import com.adfacten.ads.AdSize;
import com.adfacten.ads.Adfacten;
import com.adfacten.ads.AdfactenBannerView;
import com.adfacten.ads.AdfactenConfig;
import com.adfacten.ads.FullScreenAdListener;
import com.adfacten.ads.InterstitialAd;
import com.adfacten.ads.LogLevel;
import com.adfacten.ads.RewardedAd;

/** Compile-time check that the API is comfortable from Java (mirrors the README's Java snippets). */
final class JavaUsage {
    private JavaUsage() {}

    static void init(Context context) {
        AdfactenConfig config = new AdfactenConfig.Builder()
                .setTestMode(true)
                .setLogLevel(LogLevel.INFO)
                .build();
        Adfacten.initialize(context, "apk_xxx", config);
    }

    static AdfactenBannerView banner(Context context) {
        AdfactenBannerView banner = new AdfactenBannerView(context);
        banner.setPlacementId("plc_banner");
        banner.setAdSize(AdSize.MEDIUM_RECTANGLE);
        banner.load();
        return banner;
    }

    static void interstitial(final Activity activity) {
        InterstitialAd.load(activity, "plc_interstitial", new AdLoadCallback<InterstitialAd>() {
            @Override public void onLoaded(@NonNull InterstitialAd ad) {
                ad.setListener(new FullScreenAdListener() {
                    @Override public void onClosed() { /* resume the game */ }
                });
                ad.show(activity);
            }
            @Override public void onFailed(@NonNull AdError error) { }
        });
    }

    static void rewarded(final Activity activity) {
        RewardedAd.load(activity, "plc_rewarded", new AdLoadCallback<RewardedAd>() {
            @Override public void onLoaded(@NonNull RewardedAd ad) {
                ad.show(activity, reward -> { /* UX only; grant from the server callback */ });
            }
            @Override public void onFailed(@NonNull AdError error) { }
        });
    }
}
