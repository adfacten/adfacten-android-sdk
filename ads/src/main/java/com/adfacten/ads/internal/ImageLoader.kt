package com.adfacten.ads.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Blocking creative download + down-sampled decode. Background threads only. */
internal object ImageLoader {
    private const val MAX_BYTES = 5 * 1024 * 1024

    fun fetch(st: SdkState, url: String, targetW: Int, targetH: Int): Bitmap? = try {
        val resp = st.http.get(url, MAX_BYTES)
        if (resp.code !in 200..299) {
            Logger.w("image HTTP ${resp.code}: $url")
            null
        } else {
            decode(resp.body, targetW, targetH)
        }
    } catch (e: Exception) {
        Logger.w("image download failed: $url (${e.message})")
        null
    } catch (oom: OutOfMemoryError) {
        Logger.e("image too large: $url")
        null
    }

    fun decode(bytes: ByteArray, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val tw = targetW.coerceAtLeast(1)
        val th = targetH.coerceAtLeast(1)
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
