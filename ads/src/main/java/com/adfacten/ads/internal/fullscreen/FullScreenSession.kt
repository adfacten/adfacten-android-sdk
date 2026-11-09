package com.adfacten.ads.internal.fullscreen

import com.adfacten.ads.AdError
import com.adfacten.ads.FullScreenAdListener
import com.adfacten.ads.OnUserEarnedRewardListener
import com.adfacten.ads.internal.AdFormat
import com.adfacten.ads.internal.LoadedAd
import com.adfacten.ads.internal.Sdk
import java.util.UUID

/** Hand-off between a FullScreenAd object and the activity that shows it. Host callbacks are posted to main. */
internal class FullScreenSession(
    val loaded: LoadedAd,
    val format: AdFormat,
    private val listener: () -> FullScreenAdListener?,
    private val rewardListener: OnUserEarnedRewardListener?,
) {
    val createdAt: Long = android.os.SystemClock.elapsedRealtime()
    @Volatile var claimed: Boolean = false
    private var shownSent = false
    private var closedSent = false
    private var rewardSent = false

    fun shown() { if (!shownSent) { shownSent = true; post { it.onShown() } } }
    fun clicked() = post { it.onClicked() }
    fun failed(error: AdError) {
        if (closedSent) return
        closedSent = true
        post { it.onFailedToShow(error) }
    }
    fun closed() {
        if (closedSent) return
        closedSent = true
        post { it.onClosed() }
    }
    fun rewarded() {
        if (rewardSent) return
        rewardSent = true
        val r = loaded.data.reward ?: com.adfacten.ads.AdReward("", 0)
        val l = rewardListener ?: return
        Sdk.post { l.onUserEarnedReward(r) }
    }

    private fun post(block: (FullScreenAdListener) -> Unit) {
        val l = listener() ?: return
        Sdk.post { block(l) }
    }
}

internal object FullScreenRegistry {
    private val sessions = HashMap<String, FullScreenSession>()

    @Synchronized fun put(s: FullScreenSession): String = UUID.randomUUID().toString().also { sessions[it] = s }
    @Synchronized fun get(token: String?): FullScreenSession? = token?.let { sessions[it] }
    @Synchronized fun remove(token: String?) { if (token != null) sessions.remove(token) }
    /** True while another full-screen ad is on screen (or about to be). Unclaimed sessions expire after 5 s. */
    @Synchronized fun isShowing(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        sessions.entries.removeAll { !it.value.claimed && now - it.value.createdAt > 5_000 }
        return sessions.isNotEmpty()
    }
}
