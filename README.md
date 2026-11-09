# Adfacten Ads SDK for Android

Banner, interstitial, rewarded, native and app-open ads from the Adfacten network.

[Türkçe kılavuz → README.tr.md](README.tr.md)

- Core artifact `com.adfacten:ads`: no Google Play Services dependency, no third-party libraries (Kotlin stdlib only), minSdk 21.
- Optional `com.adfacten:ads-video`: VAST video for interstitial and rewarded ads (Media3 ExoPlayer, minSdk 23).
- Optional `com.adfacten:ads-gaid`: reads the Google advertising ID and App Set ID.
- Listeners are always called on the main thread. SDK errors never crash your app.

## 1. Setup

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://cdn.adfacten.com/maven/")
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.adfacten:ads:1.0.2")
    // Optional:
    implementation("com.adfacten:ads-video:1.0.2") // video interstitial / rewarded (minSdk 23)
    implementation("com.adfacten:ads-gaid:1.0.2")  // advertising ID + App Set ID
}
```

Without `ads-video` the SDK only requests image and HTML creatives. Without `ads-gaid` the SDK uses its own
per-install ID (or the advertising ID, if your app already ships `play-services-ads-identifier`).

## 2. Initialize

Call once, in `Application.onCreate`. It does no disk or network work on the calling thread.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Adfacten.initialize(this, "apk_your_app_key", AdfactenConfig(testMode = BuildConfig.DEBUG))
    }
}
```

```java
AdfactenConfig config = new AdfactenConfig.Builder()
        .setTestMode(BuildConfig.DEBUG)
        .build();
Adfacten.initialize(this, "apk_your_app_key", config);
```

| Option | Default | Meaning |
|---|---|---|
| `testMode` | `false` | Test ads only, never billed. |
| `logLevel` | `WARN` | Logcat output, tag `Adfacten`. |
| `childDirected` | `false` | COPPA / Families. See [Consent](#8-consent-and-privacy). |
| `userId` | `null` | Your user ID, sent with rewarded requests and echoed in the reward callback. Can be changed later with `Adfacten.setUserId()`. |

The app key (`apk_…`) and placement IDs (`plc_…`) are in the Adfacten panel under *Apps*.

## 3. Banner

XML:

```xml
<com.adfacten.ads.AdfactenBannerView
    android:id="@+id/banner"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    app:adf_placementId="plc_your_banner"
    app:adf_adSize="banner" />
```

Sizes: `banner` (320x50), `large_banner` (320x100), `medium_rectangle` (300x250), `leaderboard` (728x90, tablets).

```kotlin
val banner = findViewById<AdfactenBannerView>(R.id.banner)
banner.listener = object : BannerAdListener {
    override fun onFailed(error: AdError) { /* the banner retries by itself */ }
}
banner.load()
```

```java
AdfactenBannerView banner = new AdfactenBannerView(context);
banner.setPlacementId("plc_your_banner");
banner.setAdSize(AdSize.MEDIUM_RECTANGLE);
container.addView(banner);
banner.load();
```

The impression is counted only when the banner is actually on screen. Refresh follows the placement setting
(30–120 s) and only counts time while the banner is at least 50% visible and its activity is resumed.
`autoRefresh = false` (or `app:adf_autoRefresh="false"`) turns refresh off even if the placement has an
interval; `true` keeps it on (60 s if the placement has none); left unset, the placement decides.

If a load fails (no fill, network or server error), the banner tries again by itself on the same timer, never
sooner than the placement's backoff (30 s, growing to 15 min after repeated no-fills) and only while it is
visible. So keep the banner in the layout instead of hiding it in `onFailed`; `onLoaded` fires when a retry
fills. A refusal (`REJECTED`, `INVALID_REQUEST`) stops retrying until you call `load()` again.
The banner cleans up when its activity is destroyed; call `destroy()` if you remove it earlier.

## 4. Interstitial

```kotlin
InterstitialAd.load(context, "plc_your_interstitial", object : AdLoadCallback<InterstitialAd> {
    override fun onLoaded(ad: InterstitialAd) {
        ad.listener = object : FullScreenAdListener {
            override fun onClosed() { /* continue */ }
        }
        ad.show(activity)
    }
    override fun onFailed(error: AdError) {}
})
```

```java
InterstitialAd.load(activity, "plc_your_interstitial", new AdLoadCallback<InterstitialAd>() {
    @Override public void onLoaded(@NonNull InterstitialAd ad) {
        ad.setListener(new FullScreenAdListener() {
            @Override public void onClosed() { }
        });
        ad.show(activity);
    }
    @Override public void onFailed(@NonNull AdError error) { }
});
```

A loaded ad is valid for about 55 minutes (`isExpired`) and can be shown once. Load the next one in `onClosed`.

## 5. Rewarded

```kotlin
RewardedAd.load(context, "plc_your_rewarded", object : AdLoadCallback<RewardedAd> {
    override fun onLoaded(ad: RewardedAd) {
        ad.show(activity) { reward ->
            // UX only: show "+10 coins". Credit the user from your server callback.
        }
    }
    override fun onFailed(error: AdError) {}
}, customData = "level=3")
```

```java
RewardedAd.load(activity, "plc_your_rewarded", new AdLoadCallback<RewardedAd>() {
    @Override public void onLoaded(@NonNull RewardedAd ad) {
        ad.show(activity, reward -> { /* UX only */ });
    }
    @Override public void onFailed(@NonNull AdError error) { }
});
```

Video plays to the end before the reward; closing early asks the user to confirm and forfeits the reward.

### Server-side verification

**Never grant value from `onUserEarnedReward`.** It runs on the device and can be faked. Adfacten calls the
reward callback URL you set in the panel after a valid completion:

```
GET https://your.server/reward?user_id=…&custom=…&reward_type=coins&reward_amount=10
    &transaction_id=<impression id>&placement=plc_…&ts=<unix>[&test=1]&sig=<hex>
```

`sig` is `HMAC-SHA256(reward secret, raw query string)`, where the raw query string is everything after `?`
up to (not including) `&sig=`, exactly as received. If your callback URL has its own parameters they come
first and are signed too. Respond with 2xx; Adfacten retries until it gets one. `transaction_id` is unique:
store it and ignore repeats. Skip rewards with `test=1` in production.

```python
import hmac, hashlib

def verify(raw_query: str, secret: str) -> bool:
    signed, _, sig = raw_query.rpartition("&sig=")
    expected = hmac.new(secret.encode(), signed.encode(), hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, sig)
```

```js
const crypto = require("crypto");
function verify(rawQuery, secret) {
  const i = rawQuery.lastIndexOf("&sig=");
  const expected = crypto.createHmac("sha256", secret).update(rawQuery.slice(0, i)).digest("hex");
  const sig = rawQuery.slice(i + 5);
  return sig.length === expected.length && crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected));
}
```

Use the raw query string, not re-encoded parameters.

## 6. Native

Lay out the assets in your own views inside a `NativeAdView` and map them:

```xml
<com.adfacten.ads.NativeAdView
    android:id="@+id/native_ad"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:adf_titleView="@id/title"
    app:adf_bodyView="@id/body"
    app:adf_brandView="@id/brand"
    app:adf_callToActionView="@id/cta"
    app:adf_imageView="@id/image"
    app:adf_iconView="@id/icon"
    app:adf_adLabelView="@id/ad_label">
    <!-- your layout with those ids -->
</com.adfacten.ads.NativeAdView>
```

```kotlin
NativeAd.load(context, "plc_your_native", object : AdLoadCallback<NativeAd> {
    override fun onLoaded(ad: NativeAd) {
        nativeAdView.setNativeAd(ad)
    }
    override fun onFailed(error: AdError) {}
})
// later, e.g. onDestroy:
nativeAd.destroy()
```

```java
NativeAd.load(context, "plc_your_native", new AdLoadCallback<NativeAd>() {
    @Override public void onLoaded(@NonNull NativeAd ad) { nativeAdView.setNativeAd(ad); }
    @Override public void onFailed(@NonNull AdError error) { }
});
```

The views can also be set in code (`nativeAdView.titleView = …`). `NativeAd` exposes `title`, `body`, `brand`,
`callToAction`, `adLabel`, `image`/`icon` (already downloaded bitmaps) and their URLs. The ad must stay
labeled: if you don't map `adLabelView`, the SDK adds a small "Ad" badge. Impressions, views and clicks are
tracked by `NativeAdView`.

Binding the same `NativeAd` again (for example when a `RecyclerView` rebinds a row) never records a second
impression. An ad is shown in one view at a time: binding it to another view removes it from the previous one.

## 7. App open

```kotlin
AppOpenAd.load(context, "plc_your_app_open", object : AdLoadCallback<AppOpenAd> {
    override fun onLoaded(ad: AppOpenAd) { appOpenAd = ad }
    override fun onFailed(error: AdError) {}
})

// When the app comes to the foreground:
appOpenAd?.takeIf { !it.isExpired }?.show(activity)
```

Load ahead of time and show it on a cold start or when returning to the app; don't show it over your own
splash longer than needed.

## 8. Consent and privacy

- **GDPR / TCF v2, GPP, US Privacy:** read automatically from the default `SharedPreferences`, where every
  IAB-compliant CMP stores them (`IABTCF_TCString`, `IABTCF_gdprApplies`, `IABTCF_PurposeConsents`,
  `IABGPP_HDR_GppString`, `IABGPP_GppSID`, `IABUSPrivacy_String`). Show your CMP before loading ads.
  When GDPR applies and Purpose 1 consent is missing, the advertising ID is not sent.
- **Limit ad tracking:** if the user opted out (or the ID is zeroed), the advertising ID is not sent.
- **Children:** set `childDirected = true` for child-directed apps or users. The SDK then never reads the
  advertising ID or App Set ID and sends `coppa`; Adfacten does not serve ads to such traffic.
- Clicks always open in the browser or the store app, never inside your app.

## 9. Test mode

Use `testMode = true` (or a debug build flag) while integrating. Test ads look and track like real ones but are
never billed. New apps receive test ads until they are approved and app-ads.txt is verified, and emulators
always get test ads.

## 10. ProGuard / R8

Nothing to add: the AARs ship their own consumer rules.

## 11. Google Play Data safety

What the SDK collects and sends to Adfacten (a third party for your app). All traffic uses HTTPS.

| Data type | Collected | Shared | Purpose | Notes |
|---|---|---|---|---|
| Device or other IDs | Yes | Yes | Advertising, fraud prevention | Advertising ID (only with `ads-gaid` or Play Services, respecting opt-out and consent), App Set ID, SDK install ID. |
| Approximate location | Yes | Yes | Advertising, fraud prevention | Derived from the IP address on the server. No GPS. |
| App interactions | Yes | Yes | Advertising, analytics | Ad impressions, views and clicks. |
| User IDs | Only if you set `userId` | Yes | App functionality (rewards) | Echoed to your reward callback. |

Collection is required for the ads feature (not optional for the user), data is encrypted in transit, and the
install ID is deleted when the user clears the app's data. The SDK does not collect contacts, precise location,
photos, messages, health or financial data.

## 12. Errors

`AdError.code`: `NO_FILL` (no ad now; the SDK waits at least 30 s before asking again for that placement),
`NETWORK_ERROR`, `REJECTED` (see `serverCode`: `unknown_app`, `app_not_approved`, `bundle_mismatch`,
`platform_mismatch`, `placement_mismatch`, `coppa`), `INVALID_REQUEST`, `AD_EXPIRED`, `ALREADY_SHOWN`,
`VIDEO_NOT_SUPPORTED`, `SHOW_FAILED`, `NOT_INITIALIZED`.

`AdError.name` is a stable string for the same error, shared with the iOS SDK. Prefer it in cross-platform
code and logs:

| `code` | `name` |
|---|---|
| `INTERNAL_ERROR` (0) | `internal_error` |
| `NOT_INITIALIZED` (1) | `not_initialized` |
| `INVALID_REQUEST` (2) | `invalid_request` |
| `NETWORK_ERROR` (3) | `network_error` |
| `NO_FILL` (4) | `no_fill` |
| `SERVER_ERROR` (5) | `server_error` |
| `INVALID_RESPONSE` (6) | `invalid_response` |
| `REJECTED` (7) | `rejected` |
| `AD_EXPIRED` (8) | `ad_expired` |
| `ALREADY_SHOWN` (9) | `already_shown` |
| `VIDEO_NOT_SUPPORTED` (10) | `video_not_supported` |
| `SHOW_FAILED` (11) | `show_failed` |
| `LOAD_IN_PROGRESS` (12) | `load_in_progress` |

The shared vocabulary also has `not_ready`, `child_directed` and `creative_failed`, which the iOS SDK can
report; Android does not produce them today (child-directed refusals arrive as `rejected` with
`serverCode = "coppa"`). `AdError.nameOf(code)` gives the name for a code.

## 13. Framework plugins

Plugins that wrap this SDK (Flutter, React Native, Unity) report themselves before initializing:

```kotlin
Adfacten.setWrapper("flutter", "1.0.1") // then Adfacten.initialize(...)
```

`name` is `flutter`, `react_native`, `unity` or `other` (anything else is sent as `other`); `version` is up to
20 characters of `0-9 A-Z a-z . + -` (otherwise sent as `unknown`). It is sent as `app.wrapper` in ad requests
and appended to the User-Agent as ` Wrapper/<name>-<version>`. Native apps never call this.

## Changelog

### 1.0.2
- Banner: after a failed load the banner retries on its refresh timer, respecting the per-placement backoff
  and only while visible; a refusal stops retrying until `load()`.
- Banner: `autoRefresh` set by the app always wins over the placement's refresh setting.
- Native: binding the same ad again (or to a new view) never records a second impression or `onShown`.
- `AdError.name`: stable error names shared with iOS.
- `Adfacten.setWrapper(name, version)` for framework plugins.
- Rewarded: `AdReward.amount` is the server's integer value, unchanged.

## Building from source

```
./gradlew :ads:test                     # unit tests
./gradlew :ads:assembleRelease          # AAR
./gradlew :sample:assembleDebug         # sample app
./gradlew publish                       # Maven layout in build/maven-repo
```

The sample is preconfigured with Adfacten's public sample app, which always receives test ads (never
billed), so it runs as is. To try your own app, set its app key and placements as Gradle properties
(`-Padfacten.appKey=… -Padfacten.banner=…`) or in `sample/dev-app.properties`.

## License

Apache License 2.0. See [LICENSE](LICENSE).
