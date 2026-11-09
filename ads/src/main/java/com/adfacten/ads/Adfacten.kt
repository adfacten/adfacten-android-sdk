package com.adfacten.ads

import android.content.Context
import com.adfacten.ads.internal.Logger
import com.adfacten.ads.internal.Sdk
import com.adfacten.ads.internal.SdkState
import com.adfacten.ads.internal.safe

/** Entry point of the Adfacten Ads SDK. */
public object Adfacten {
    /** Version of this SDK, e.g. "1.0.0". */
    @JvmStatic
    public val sdkVersion: String get() = BuildConfig.SDK_VERSION

    /**
     * Initializes the SDK. Call once, early (Application.onCreate is ideal). Cheap: no disk or network work
     * happens on the calling thread. Safe to call again (the config is replaced).
     *
     * @param appKey the public app key (`apk_…`) from the Adfacten panel.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context, appKey: String, config: AdfactenConfig = AdfactenConfig()) {
        safe("initialize") {
            Logger.level = config.logLevel
            if (!appKey.startsWith("apk_")) Logger.w("app key \"$appKey\" does not look like an Adfacten app key (apk_…)")
            val existing = Sdk.state
            if (existing != null && existing.appKey == appKey) {
                existing.config = config
                existing.userId = config.userId ?: existing.userId
                return@safe
            }
            if (existing != null) Logger.w("Adfacten.initialize called again with a different app key; using the new key")
            val st = SdkState(context.applicationContext ?: context, appKey, config)
            Sdk.state = st
            Sdk.io.execute {
                safe("initialize (background)") {
                    st.sdkId // create/read the install id off the main thread
                    st.device.app
                    st.beacons.flush() // deliver beacons left over from earlier sessions
                }
            }
            Logger.i("Adfacten SDK $sdkVersion initialized (test=${config.testMode}, childDirected=${config.childDirected})")
        }
    }

    @JvmStatic
    public val isInitialized: Boolean get() = Sdk.state != null

    /** Sets (or clears) your user id for rewarded ads; it is echoed in the server-side reward callback. */
    @JvmStatic
    public fun setUserId(userId: String?) {
        safe("setUserId") { Sdk.state?.userId = userId?.take(256) }
    }
}
