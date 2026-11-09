# Adfacten Ads SDK — Android

Adfacten ağından banner, geçiş (interstitial), ödüllü (rewarded), yerel (native) ve açılış (app open) reklamları.

[English guide → README.md](README.md)

- Ana paket `com.adfacten:ads`: Google Play Hizmetleri bağımlılığı yok, üçüncü taraf kütüphane yok (yalnızca Kotlin stdlib), minSdk 21.
- İsteğe bağlı `com.adfacten:ads-video`: geçiş ve ödüllü reklamlarda VAST video (Media3 ExoPlayer, minSdk 23).
- İsteğe bağlı `com.adfacten:ads-gaid`: Google reklam kimliğini ve App Set ID'yi okur.
- Dinleyiciler her zaman ana iş parçacığında çağrılır. SDK hataları uygulamanızı çökertmez.

## 1. Kurulum

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
    implementation("com.adfacten:ads:1.0.0")
    // İsteğe bağlı:
    implementation("com.adfacten:ads-video:1.0.0") // video geçiş / ödüllü (minSdk 23)
    implementation("com.adfacten:ads-gaid:1.0.0")  // reklam kimliği + App Set ID
}
```

`ads-video` yoksa SDK yalnızca görsel ve HTML reklam ister. `ads-gaid` yoksa SDK kendi kurulum kimliğini kullanır
(uygulamanızda `play-services-ads-identifier` zaten varsa reklam kimliğini de okur).

## 2. Başlatma

`Application.onCreate` içinde bir kez çağırın. Çağıran iş parçacığında disk ya da ağ işi yapmaz.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Adfacten.initialize(this, "apk_uygulama_anahtariniz", AdfactenConfig(testMode = BuildConfig.DEBUG))
    }
}
```

```java
AdfactenConfig config = new AdfactenConfig.Builder()
        .setTestMode(BuildConfig.DEBUG)
        .build();
Adfacten.initialize(this, "apk_uygulama_anahtariniz", config);
```

| Ayar | Varsayılan | Anlamı |
|---|---|---|
| `testMode` | `false` | Yalnızca test reklamı; hiç ücretlendirilmez. |
| `logLevel` | `WARN` | Logcat çıktısı, etiket `Adfacten`. |
| `childDirected` | `false` | COPPA / Aileler politikası. Bkz. [Onay ve gizlilik](#8-onay-ve-gizlilik). |
| `userId` | `null` | Kendi kullanıcı kimliğiniz; ödüllü isteklerde gönderilir, ödül bildiriminde size geri gelir. Sonradan `Adfacten.setUserId()` ile değiştirilebilir. |

Uygulama anahtarı (`apk_…`) ve yerleşim kimlikleri (`plc_…`) Adfacten panelinde *Uygulamalar* bölümündedir.

## 3. Banner

XML:

```xml
<com.adfacten.ads.AdfactenBannerView
    android:id="@+id/banner"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    app:adf_placementId="plc_banner_kimliginiz"
    app:adf_adSize="banner" />
```

Boyutlar: `banner` (320x50), `large_banner` (320x100), `medium_rectangle` (300x250), `leaderboard` (728x90, tablet).

```kotlin
val banner = findViewById<AdfactenBannerView>(R.id.banner)
banner.listener = object : BannerAdListener {
    override fun onFailed(error: AdError) { /* alanı gizleyin */ }
}
banner.load()
```

```java
AdfactenBannerView banner = new AdfactenBannerView(context);
banner.setPlacementId("plc_banner_kimliginiz");
banner.setAdSize(AdSize.MEDIUM_RECTANGLE);
container.addView(banner);
banner.load();
```

Gösterim yalnızca banner gerçekten ekrandayken sayılır. Yenileme yerleşim ayarına göre (30–120 sn) yapılır ve
yalnızca banner en az %50 göründüğü ve ekranı açık olduğu süre sayılır. Banner, bağlı olduğu ekran
kapanınca kendini temizler; daha önce kaldırırsanız `destroy()` çağırın.

## 4. Geçiş reklamı (interstitial)

```kotlin
InterstitialAd.load(context, "plc_gecis_kimliginiz", object : AdLoadCallback<InterstitialAd> {
    override fun onLoaded(ad: InterstitialAd) {
        ad.listener = object : FullScreenAdListener {
            override fun onClosed() { /* devam */ }
        }
        ad.show(activity)
    }
    override fun onFailed(error: AdError) {}
})
```

```java
InterstitialAd.load(activity, "plc_gecis_kimliginiz", new AdLoadCallback<InterstitialAd>() {
    @Override public void onLoaded(@NonNull InterstitialAd ad) {
        ad.setListener(new FullScreenAdListener() {
            @Override public void onClosed() { }
        });
        ad.show(activity);
    }
    @Override public void onFailed(@NonNull AdError error) { }
});
```

Yüklenen reklam yaklaşık 55 dakika geçerlidir (`isExpired`) ve bir kez gösterilebilir. Sonrakini `onClosed` içinde yükleyin.

## 5. Ödüllü reklam (rewarded)

```kotlin
RewardedAd.load(context, "plc_odullu_kimliginiz", object : AdLoadCallback<RewardedAd> {
    override fun onLoaded(ad: RewardedAd) {
        ad.show(activity) { reward ->
            // Yalnızca arayüz için: "+10 jeton" gösterin. Ödülü sunucu bildirimiyle verin.
        }
    }
    override fun onFailed(error: AdError) {}
}, customData = "level=3")
```

```java
RewardedAd.load(activity, "plc_odullu_kimliginiz", new AdLoadCallback<RewardedAd>() {
    @Override public void onLoaded(@NonNull RewardedAd ad) {
        ad.show(activity, reward -> { /* yalnızca arayüz */ });
    }
    @Override public void onFailed(@NonNull AdError error) { }
});
```

Video sonuna kadar oynatılır; erken kapatmak isteyen kullanıcıya onay sorulur ve ödül verilmez.

### Sunucu tarafı doğrulama

**`onUserEarnedReward` ile değer vermeyin.** Cihazda çalışır, taklit edilebilir. Adfacten, geçerli bir
izlemeden sonra panelde girdiğiniz ödül adresini çağırır:

```
GET https://sunucunuz/reward?user_id=…&custom=…&reward_type=coins&reward_amount=10
    &transaction_id=<gösterim kimliği>&placement=plc_…&ts=<unix>[&test=1]&sig=<hex>
```

`sig` = `HMAC-SHA256(ödül gizli anahtarı, ham sorgu dizesi)`. Ham sorgu dizesi, `?` sonrasından `&sig=`
öncesine kadar olan kısımdır, geldiği gibi. Adresinizin kendi parametreleri varsa onlar başta gelir ve imzaya
dahildir. 2xx ile yanıt verin; Adfacten 2xx alana kadar yeniden dener. `transaction_id` benzersizdir:
kaydedin, tekrar gelirse yok sayın. Canlıda `test=1` olanlara ödül vermeyin.

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

Yeniden kodlanmış parametreleri değil, ham sorgu dizesini kullanın.

## 6. Yerel reklam (native)

Reklam öğelerini `NativeAdView` içindeki kendi görünümlerinize yerleştirin ve eşleyin:

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
    <!-- bu kimliklerle kendi düzeniniz -->
</com.adfacten.ads.NativeAdView>
```

```kotlin
NativeAd.load(context, "plc_yerel_kimliginiz", object : AdLoadCallback<NativeAd> {
    override fun onLoaded(ad: NativeAd) {
        nativeAdView.setNativeAd(ad)
    }
    override fun onFailed(error: AdError) {}
})
// sonra, örn. onDestroy:
nativeAd.destroy()
```

```java
NativeAd.load(context, "plc_yerel_kimliginiz", new AdLoadCallback<NativeAd>() {
    @Override public void onLoaded(@NonNull NativeAd ad) { nativeAdView.setNativeAd(ad); }
    @Override public void onFailed(@NonNull AdError error) { }
});
```

Görünümler kodla da verilebilir (`nativeAdView.titleView = …`). `NativeAd` şunları sunar: `title`, `body`,
`brand`, `callToAction`, `adLabel`, `image`/`icon` (indirilmiş görseller) ve adresleri. Reklam etiketi
görünmelidir: `adLabelView` eşlemezseniz SDK küçük bir "Reklam" etiketi ekler. Gösterim, görüntülenme ve
tıklamaları `NativeAdView` izler.

## 7. Açılış reklamı (app open)

```kotlin
AppOpenAd.load(context, "plc_acilis_kimliginiz", object : AdLoadCallback<AppOpenAd> {
    override fun onLoaded(ad: AppOpenAd) { appOpenAd = ad }
    override fun onFailed(error: AdError) {}
})

// Uygulama ön plana geldiğinde:
appOpenAd?.takeIf { !it.isExpired }?.show(activity)
```

Önceden yükleyin; soğuk açılışta ya da uygulamaya dönüşte gösterin.

## 8. Onay ve gizlilik

- **GDPR / TCF v2, GPP, US Privacy:** IAB uyumlu her onay aracının (CMP) yazdığı varsayılan `SharedPreferences`
  değerlerinden otomatik okunur (`IABTCF_TCString`, `IABTCF_gdprApplies`, `IABTCF_PurposeConsents`,
  `IABGPP_HDR_GppString`, `IABGPP_GppSID`, `IABUSPrivacy_String`). Reklam yüklemeden önce onay ekranınızı gösterin.
  GDPR geçerliyse ve 1. amaç onayı yoksa reklam kimliği gönderilmez.
- **Reklam takibini sınırlama:** kullanıcı kapattıysa (ya da kimlik sıfırsa) reklam kimliği gönderilmez.
- **Çocuklar:** çocuklara yönelik uygulama ya da kullanıcılar için `childDirected = true` verin. SDK bu durumda
  reklam kimliğini ve App Set ID'yi hiç okumaz ve `coppa` gönderir; Adfacten bu trafiğe reklam sunmaz.
- Tıklamalar her zaman tarayıcıda ya da mağaza uygulamasında açılır, uygulamanızın içinde açılmaz.

## 9. Test modu

Entegrasyon sırasında `testMode = true` kullanın. Test reklamları gerçekleri gibi görünür ve izlenir ama
ücretlendirilmez. Yeni uygulamalar onaylanana ve app-ads.txt doğrulanana kadar test reklamı alır;
emülatörler her zaman test reklamı alır.

## 10. ProGuard / R8

Ek bir şey gerekmez: AAR'lar kendi kurallarıyla gelir.

## 11. Google Play Veri güvenliği formu

SDK'nın topladığı ve Adfacten'e (uygulamanız için üçüncü taraf) gönderdiği veriler. Tüm trafik HTTPS'tir.

| Veri türü | Toplanır | Paylaşılır | Amaç | Not |
|---|---|---|---|---|
| Cihaz veya diğer kimlikler | Evet | Evet | Reklam, sahtekârlık önleme | Reklam kimliği (yalnızca `ads-gaid` ya da Play Hizmetleri ile; kullanıcı tercihi ve onaya uyularak), App Set ID, SDK kurulum kimliği. |
| Yaklaşık konum | Evet | Evet | Reklam, sahtekârlık önleme | Sunucuda IP adresinden çıkarılır. GPS kullanılmaz. |
| Uygulama etkileşimleri | Evet | Evet | Reklam, analiz | Reklam gösterimi, görüntülenme ve tıklama. |
| Kullanıcı kimlikleri | Yalnızca `userId` verirseniz | Evet | Uygulama işlevi (ödül) | Ödül bildiriminize geri gönderilir. |

Toplama reklam özelliği için gereklidir (kullanıcıya isteğe bağlı değildir), veriler aktarımda şifrelenir ve
kurulum kimliği kullanıcı uygulama verilerini silince silinir. SDK kişiler, kesin konum, fotoğraf, mesaj,
sağlık ya da finans verisi toplamaz.

## 12. Hatalar

`AdError.code`: `NO_FILL` (şu an reklam yok; SDK o yerleşim için en az 30 sn bekler), `NETWORK_ERROR`,
`REJECTED` (`serverCode`: `unknown_app`, `app_not_approved`, `bundle_mismatch`, `platform_mismatch`,
`placement_mismatch`, `coppa`), `INVALID_REQUEST`, `AD_EXPIRED`, `ALREADY_SHOWN`, `VIDEO_NOT_SUPPORTED`,
`SHOW_FAILED`, `NOT_INITIALIZED`.

## Lisans

Apache License 2.0. Ayrıntılar için [LICENSE](LICENSE).
