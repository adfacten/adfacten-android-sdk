package com.adfacten.ads.internal

import android.util.Log
import com.adfacten.ads.LogLevel

internal object Logger {
    private const val TAG = "Adfacten"
    @Volatile var level: LogLevel = LogLevel.WARN

    fun d(msg: String) { if (level >= LogLevel.DEBUG) Log.d(TAG, msg) }
    fun i(msg: String) { if (level >= LogLevel.INFO) Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { if (level >= LogLevel.WARN) Log.w(TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) { if (level >= LogLevel.ERROR) Log.e(TAG, msg, t) }
}

/**
 * Runs SDK code so that nothing ever propagates into the host app. Host callbacks are always posted
 * separately (see [Sdk.post]) so the app's own exceptions are not swallowed.
 */
internal inline fun <T> safe(where: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        Logger.e("internal error in $where", t)
        fallback
    }

internal inline fun safe(where: String, block: () -> Unit) {
    safe(where, Unit, block)
}
