package com.adfacten.ads.internal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock

/**
 * Opens a click in the system browser / store app (never an in-app WebView). The tracker URL 302s to the
 * landing page or the store. Double taps within 1.5 s are ignored so one tap is one click.
 */
internal object ClickOpener {
    private var lastClickAt = 0L

    fun open(context: Context, url: String?): Boolean {
        if (url == null || !isHttpUrl(url)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickAt < 1500) return false
        lastClickAt = now
        val sid = Sdk.state?.let { st -> try { st.sdkId } catch (e: Exception) { null } }
        val target = if (sid != null && url.contains("/a/click")) {
            val sep = if (url.contains('?')) '&' else '?'
            "$url${sep}sid=${java.net.URLEncoder.encode(sid, "UTF-8")}"
        } else {
            url
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Logger.w("no app can open the click URL")
            false
        } catch (e: SecurityException) {
            Logger.w("click URL could not be opened", e)
            false
        }
    }
}
