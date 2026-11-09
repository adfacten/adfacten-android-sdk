package com.adfacten.ads.internal

import com.adfacten.ads.AdError
import com.adfacten.ads.AdReward
import org.json.JSONObject

/** Parses `/m/v1/ad` responses. Pure: no Android dependencies besides org.json. */
internal object AdResponseParser {
    /** Upper bound for the ad lifetime on the device, even if the server sends a later `expires_at`. */
    const val MAX_TTL_MS = 55L * 60 * 1000
    /** Used when `expires_at` is missing. */
    const val DEFAULT_TTL_MS = 30L * 60 * 1000

    /**
     * @param serverDateMs the response `Date` header, used to cancel out device clock skew
     * @param nowWallMs device wall clock; @param nowElapsedMs device monotonic clock
     */
    fun parse(
        status: Int,
        body: String,
        requested: AdFormat,
        serverDateMs: Long?,
        nowWallMs: Long,
        nowElapsedMs: Long,
    ): AdResult {
        if (status == 204) return AdResult.NoFill
        if (status in 400..499) return AdResult.Failure(parseError(status, body))
        if (status !in 200..299) return AdResult.Failure(AdError(AdError.SERVER_ERROR, "HTTP $status"))
        if (status != 200 || body.isBlank()) return AdResult.NoFill

        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return invalid("body is not JSON")
        }
        val ad = root.optJSONObject("ad") ?: return AdResult.NoFill

        val id = ad.optString("id")
        if (id.isEmpty()) return invalid("missing ad.id")
        // Unknown formats (e.g. "chat", reserved for a later version) are refused, not guessed.
        val wireFormat = ad.optString("format")
        val format = if (wireFormat.isEmpty()) requested else AdFormat.fromWire(wireFormat)
            ?: return invalid("unsupported format $wireFormat")
        if (format != requested) return invalid("format ${format.wire} does not match the request (${requested.wire})")
        val kind = CreativeKind.fromWire(ad.optString("kind")) ?: return invalid("unknown kind ${ad.optString("kind")}")

        val image = ad.optJSONObject("image")?.let(::parseImage)
        val html = ad.optString("html").ifEmpty { null }
        val vast = ad.optString("vast").ifEmpty { null }
        val native = ad.optJSONObject("native")?.let { n ->
            NativeAssets(
                title = n.optString("title"),
                body = n.optString("body"),
                brand = n.optString("brand"),
                cta = n.optString("cta"),
                image = n.optJSONObject("image")?.let(::parseImage),
                icon = n.optJSONObject("icon")?.let(::parseImage),
            )
        }
        when (kind) {
            CreativeKind.IMAGE -> if (image == null) return invalid("kind=image without image.url")
            CreativeKind.HTML -> if (html == null) return invalid("kind=html without html")
            CreativeKind.VIDEO -> if (vast == null) return invalid("kind=video without vast")
            CreativeKind.NATIVE -> if (native == null || native.title.isEmpty()) return invalid("kind=native without native.title")
        }
        if (kind == CreativeKind.VIDEO && !(format == AdFormat.INTERSTITIAL || format == AdFormat.REWARDED)) {
            return invalid("video is only supported for interstitial and rewarded")
        }

        val t = ad.optJSONObject("trackers")
        val trackers = Trackers(
            render = t?.let { httpUrl(it, "render") },
            view = t?.let { httpUrl(it, "view") },
            complete = t?.let { httpUrl(it, "complete") },
            close = t?.let { httpUrl(it, "close") },
        )
        val vr = ad.optJSONObject("view_rule")
        val defaultMs = if (kind == CreativeKind.VIDEO) 2000L else 1000L
        val viewRule = ViewRule(
            minRatio = (vr?.optDouble("min_ratio", 0.5) ?: 0.5).toFloat().coerceIn(0.01f, 1f),
            minMs = (vr?.optLong("min_ms", defaultMs) ?: defaultMs).coerceIn(0L, 60_000L),
        )
        val reward = ad.optJSONObject("reward")?.let { r ->
            val type = r.optString("type")
            if (type.isEmpty()) null else AdReward(type, r.optInt("amount", 0))
        }

        val expiresAtS = ad.optLong("expires_at", 0L)
        val ttl = if (expiresAtS > 0) {
            val serverNow = serverDateMs ?: nowWallMs
            (expiresAtS * 1000 - serverNow).coerceIn(0L, MAX_TTL_MS)
        } else {
            DEFAULT_TTL_MS
        }
        if (ttl <= 0L) return invalid("ad already expired")

        return AdResult.Fill(
            AdData(
                id = id,
                format = format,
                kind = kind,
                w = ad.optInt("w", 0),
                h = ad.optInt("h", 0),
                image = image,
                html = html,
                vast = vast,
                native = native,
                clickUrl = httpUrl(ad, "click_url"),
                trackers = trackers,
                viewRule = viewRule,
                closeAfterS = ad.optInt("close_after_s", 0).coerceIn(0, 60),
                reward = reward,
                label = ad.optString("label").ifEmpty { null },
                test = ad.optBoolean("test", false),
                refreshS = if (ad.has("refresh_s")) ad.optInt("refresh_s", 60) else null,
                expiresAtElapsedMs = nowElapsedMs + ttl,
            ),
        )
    }

    fun parseError(status: Int, body: String): AdError {
        var code = ""
        var message = "HTTP $status"
        try {
            val o = JSONObject(body)
            code = o.optString("code")
            message = o.optString("message").ifEmpty { message }
        } catch (_: Exception) {
        }
        val sdkCode = when {
            code == "invalid_request" || (code.isEmpty() && status == 400) -> AdError.INVALID_REQUEST
            status == 429 -> AdError.NO_FILL
            else -> AdError.REJECTED
        }
        return AdError(sdkCode, message, code.ifEmpty { null })
    }

    private fun parseImage(o: JSONObject): ImageAsset? {
        val url = o.optString("url")
        if (!isHttpUrl(url)) return null
        return ImageAsset(
            url = url,
            url2x = o.optString("url_2x").takeIf(::isHttpUrl),
            fallback = o.optString("fallback").takeIf(::isHttpUrl),
            w = o.optInt("w", 0),
            h = o.optInt("h", 0),
        )
    }

    private fun httpUrl(o: JSONObject, key: String): String? = o.optString(key).takeIf(::isHttpUrl)

    private fun invalid(msg: String) = AdResult.Failure(AdError(AdError.INVALID_RESPONSE, msg))
}

internal fun isHttpUrl(s: String?): Boolean =
    s != null && (s.startsWith("https://", ignoreCase = true) || s.startsWith("http://", ignoreCase = true))
