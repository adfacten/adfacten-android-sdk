package com.adfacten.ads.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.adfacten.ads.AdfactenConfig
import com.adfacten.ads.BuildConfig
import com.adfacten.ads.internal.net.HttpClient
import com.adfacten.ads.internal.track.BeaconQueue
import com.adfacten.ads.internal.track.BeaconSender
import com.adfacten.ads.internal.track.FileBeaconStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide SDK state. Created by [com.adfacten.ads.Adfacten.initialize]; all disk/network work runs off the main thread. */
internal class SdkState(
    val appContext: Context,
    val appKey: String,
    @Volatile var config: AdfactenConfig,
) {
    @Volatile var userId: String? = config.userId

    /** Per-install UUID (sent as device.sdk_id). Read lazily on a background thread. */
    val sdkId: String by lazy {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SDK_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_SDK_ID, it).apply()
        }
    }

    val device = DeviceInfoCollector(appContext)

    val http: HttpClient by lazy {
        HttpClient(userAgent = { device.userAgent }, sdkHeader = "android/${BuildConfig.SDK_VERSION}")
    }

    val backoff = NoFillBackoff(clock = { SystemClock.elapsedRealtime() })

    val beacons: BeaconQueue = BeaconQueue(
        storage = FileBeaconStorage { File(appContext.noBackupFilesDir, "adfacten/beacons.json") },
        sender = object : BeaconSender {
            override fun send(url: String): Int = try {
                http.get(url, maxBytes = 64 * 1024).code
            } catch (e: Exception) {
                Logger.d("beacon network error: ${e.message}")
                -1
            }
        },
        clock = { System.currentTimeMillis() },
        executor = Sdk.io,
        schedule = { delay, task -> Sdk.io.schedule(task, delay, TimeUnit.MILLISECONDS) },
    )

    companion object {
        const val PREFS = "com.adfacten.ads"
        const val KEY_SDK_ID = "sdk_id"

    }
}

internal object Sdk {
    @Volatile var state: SdkState? = null

    val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** Serial: beacon queue + small disk I/O. */
    val io: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(threads("adfacten-io"))

    /** Ad requests, creative downloads. */
    val net: ScheduledExecutorService = Executors.newScheduledThreadPool(2, threads("adfacten-net"))

    /** Posts to the main thread. Host callbacks go through here so their exceptions are theirs, not swallowed. */
    fun post(r: () -> Unit) {
        main.post(r)
    }

    fun postDelayed(delayMs: Long, r: Runnable) {
        main.postDelayed(r, delayMs)
    }

    private fun threads(name: String): ThreadFactory {
        val n = AtomicInteger()
        return ThreadFactory { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "$name-${n.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
