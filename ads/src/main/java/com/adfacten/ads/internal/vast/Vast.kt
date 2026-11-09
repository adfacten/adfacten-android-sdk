package com.adfacten.ads.internal.vast

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal class VastMediaFile(val url: String, val type: String, val width: Int, val height: Int, val bitrateKbps: Int, val delivery: String)

internal class VastAd(
    val adId: String,
    val durationMs: Long,
    /** Skip offset in ms, or null if the creative is not skippable. */
    val skipOffsetMs: Long?,
    val mediaFiles: List<VastMediaFile>,
    val impressions: List<String>,
    val errors: List<String>,
    /** start, firstQuartile, midpoint, thirdQuartile, complete, mute, unmute, pause, resume, skip, close, closeLinear… */
    val events: Map<String, List<String>>,
    val clickThrough: String?,
    val clickTracking: List<String>,
) {
    fun urls(event: String): List<String> = events[event].orEmpty()
}

internal class VastException(message: String) : Exception(message)

/** VAST 2–4 InLine parser (Wrapper is not followed: the ad server must resolve wrappers). */
internal object VastParser {
    @Throws(VastException::class)
    fun parse(xml: String): VastAd {
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            f.isExpandEntityReferences = false
            for (feature in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            )) {
                try { f.setFeature(feature.first, feature.second) } catch (_: Exception) {}
            }
            f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw VastException("VAST is not valid XML: ${e.message}")
        }
        val root = doc.documentElement ?: throw VastException("empty VAST")
        if (root.tagName != "VAST") throw VastException("root element is not VAST")
        val ad = root.child("Ad") ?: throw VastException("VAST has no Ad (empty response)")
        if (ad.child("Wrapper") != null && ad.child("InLine") == null) throw VastException("VAST Wrapper is not supported")
        val inline = ad.child("InLine") ?: throw VastException("VAST Ad has no InLine")

        val impressions = inline.children("Impression").mapNotNull { it.url() }
        val errors = inline.children("Error").mapNotNull { it.url() }
        val linear = inline.child("Creatives")?.children("Creative")?.firstNotNullOfOrNull { it.child("Linear") }
            ?: throw VastException("VAST has no Linear creative")

        val duration = parseTime(linear.child("Duration")?.text()) ?: 0L
        val skip = linear.getAttribute("skipoffset").takeIf { it.isNotEmpty() }?.let { parseOffset(it, duration) }

        val media = linear.child("MediaFiles")?.children("MediaFile").orEmpty().mapNotNull { m ->
            val url = m.url() ?: return@mapNotNull null
            VastMediaFile(
                url = url,
                type = m.getAttribute("type").lowercase(),
                width = m.getAttribute("width").toIntOrNull() ?: 0,
                height = m.getAttribute("height").toIntOrNull() ?: 0,
                bitrateKbps = (m.getAttribute("bitrate").ifEmpty { m.getAttribute("maxBitrate") }).toIntOrNull() ?: 0,
                delivery = m.getAttribute("delivery").lowercase(),
            )
        }
        if (media.isEmpty()) throw VastException("VAST has no MediaFile")

        val events = LinkedHashMap<String, MutableList<String>>()
        linear.child("TrackingEvents")?.children("Tracking").orEmpty().forEach { t ->
            val ev = t.getAttribute("event")
            val u = t.url()
            if (ev.isNotEmpty() && u != null) events.getOrPut(ev) { ArrayList() }.add(u)
        }
        val clicks = linear.child("VideoClicks")
        return VastAd(
            adId = ad.getAttribute("id"),
            durationMs = duration,
            skipOffsetMs = skip,
            mediaFiles = media,
            impressions = impressions,
            errors = errors,
            events = events,
            clickThrough = clicks?.child("ClickThrough")?.url(),
            clickTracking = clicks?.children("ClickTracking").orEmpty().mapNotNull { it.url() },
        )
    }

    /** "HH:MM:SS" or "HH:MM:SS.mmm" → ms. */
    fun parseTime(s: String?): Long? {
        val p = s?.trim()?.split(':') ?: return null
        if (p.size != 3) return null
        val h = p[0].toLongOrNull() ?: return null
        val m = p[1].toLongOrNull() ?: return null
        val sec = p[2].toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (sec * 1000).toLong()
    }

    /** skipoffset: a time or a percentage of the duration. */
    fun parseOffset(s: String, durationMs: Long): Long? =
        if (s.trim().endsWith("%")) s.trim().dropLast(1).toDoubleOrNull()?.let { (durationMs * it / 100).toLong() }
        else parseTime(s)

    /**
     * Picks the progressive MP4 closest to the screen, preferring a bitrate within the connection budget
     * (cellular ≤ 1500 kbps, wifi ≤ 4000 kbps). Returns null when no playable file exists.
     */
    fun selectMediaFile(files: List<VastMediaFile>, screenW: Int, screenH: Int, cellular: Boolean): VastMediaFile? {
        val playable = files.filter { (it.type == "video/mp4" || it.url.substringBefore('?').endsWith(".mp4", true)) && it.delivery != "streaming" }
        if (playable.isEmpty()) return null
        val budget = if (cellular) 1500 else 4000
        val screenArea = (maxOf(screenW, screenH).toLong() * minOf(screenW, screenH)).coerceAtLeast(1)
        return playable.minByOrNull { f ->
            val area = (maxOf(f.width, f.height).toLong() * minOf(f.width, f.height))
            val sizeScore = if (area <= 0) 2.0 else Math.abs(Math.log(area.toDouble() / screenArea))
            val bitratePenalty = when {
                f.bitrateKbps <= 0 -> 0.5
                f.bitrateKbps > budget -> 1.0 + (f.bitrateKbps - budget) / budget.toDouble()
                else -> 0.0
            }
            sizeScore + bitratePenalty
        }
    }

    /** Replaces the common VAST macros before firing a URL. */
    fun expandMacros(url: String, nowMs: Long, errorCode: Int? = null, positionMs: Long? = null): String {
        var u = url
            .replace("[CACHEBUSTING]", ((nowMs % 90_000_000) + 10_000_000).toString())
            .replace("[TIMESTAMP]", java.net.URLEncoder.encode(isoTime(nowMs), "UTF-8"))
        if (errorCode != null) u = u.replace("[ERRORCODE]", errorCode.toString())
        if (positionMs != null) u = u.replace("[CONTENTPLAYHEAD]", formatTime(positionMs)).replace("[ADPLAYHEAD]", formatTime(positionMs))
        return u
    }

    private fun isoTime(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US).format(java.util.Date(ms))

    private fun formatTime(ms: Long): String =
        String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun Element.child(name: String): Element? {
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.tagName == name) return n
            n = n.nextSibling
        }
        return null
    }

    private fun Element.children(name: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.tagName == name) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.text(): String = textContent?.trim().orEmpty()

    private fun Element.url(): String? = text().takeIf { it.startsWith("http://") || it.startsWith("https://") }
}
