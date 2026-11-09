package com.adfacten.sample

import android.app.Application
import android.os.StrictMode
import com.adfacten.ads.Adfacten
import com.adfacten.ads.AdfactenConfig
import com.adfacten.ads.LogLevel

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The SDK must never do disk or network work on the main thread: make violations visible.
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build())

        Adfacten.initialize(
            this,
            BuildConfig.APP_KEY,
            AdfactenConfig.Builder()
                .setTestMode(true)
                .setLogLevel(LogLevel.DEBUG)
                .setUserId("sample-user-1")
                .apply { if (BuildConfig.ENDPOINT.isNotEmpty()) setEndpoint(BuildConfig.ENDPOINT) }
                .build(),
        )
    }
}
