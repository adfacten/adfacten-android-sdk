package com.adfacten.ads.internal.track

import com.adfacten.ads.internal.AdData

/**
 * Fires an ad's Adfacten trackers once each, plus third-party (VAST) URLs, through the [BeaconQueue].
 * Adfacten trackers get `sid=<sdk_id>&ts=<event ms>` appended.
 */
internal class AdEventTracker(
    private val ad: AdData,
    private val queue: BeaconQueue,
    private val sdkId: String,
    private val clock: () -> Long,
) {
    var rendered = false; private set
    var viewed = false; private set
    var completed = false; private set
    var closed = false; private set

    fun render(): Boolean {
        if (rendered) return false
        rendered = true
        fire("render", ad.trackers.render)
        return true
    }

    fun view() {
        if (viewed) return
        viewed = true
        fire("view", ad.trackers.view)
    }

    fun complete() {
        if (completed) return
        completed = true
        fire("complete", ad.trackers.complete)
    }

    fun close() {
        if (closed) return
        closed = true
        fire("close", ad.trackers.close)
    }

    /**
     * VAST event URLs, deduped by event + URL. Third-party URLs are sent as is; Adfacten `/i` URLs get the
     * same `sid`/`ts` as the trackers so the server can bind them to this install.
     */
    fun external(event: String, urls: List<String>) {
        for ((i, u) in urls.withIndex()) {
            val url = if (isOwnBeacon(u)) decorate(u, sdkId, clock()) else u
            queue.enqueue("${ad.id}|x:$event:$i:${u.hashCode()}", url)
        }
    }

    private val ownHost: String? = ad.trackers.render?.let { hostOf(it) }

    private fun isOwnBeacon(url: String): Boolean {
        if (ownHost == null || url.contains("sid=")) return false
        val host = hostOf(url) ?: return false
        return host == ownHost && url.substringAfter(host).startsWith("/i?")
    }

    private fun fire(event: String, url: String?) {
        if (url == null) return
        queue.enqueue("${ad.id}|$event", decorate(url, sdkId, clock()))
    }

    companion object {
        private fun hostOf(url: String): String? = url.substringAfter("://", "").substringBefore('/').ifEmpty { null }

        fun decorate(url: String, sdkId: String, ts: Long): String {
            val sep = if (url.contains('?')) '&' else '?'
            return "$url${sep}sid=${java.net.URLEncoder.encode(sdkId, "UTF-8")}&ts=$ts"
        }
    }
}
