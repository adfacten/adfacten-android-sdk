package com.adfacten.ads.internal

import org.json.JSONArray
import org.json.JSONObject

internal class DeviceSnapshot(
    val osv: String,
    val make: String,
    val model: String,
    val identity: Identity,
    val lang: String,
    val w: Int,
    val h: Int,
    val pxratio: Float,
    val connection: String,
    val carrier: String,
    val tzOffsetMin: Int,
)

internal class Identity(val ifa: String, val ifaType: String, val lmt: Boolean, val sdkId: String)

internal class AdvertisingIdInfo(val id: String?, val limitAdTracking: Boolean)

internal class ConsentSnapshot(
    /** IABTCF_gdprApplies: 1, 0 or null (no CMP). */
    val gdprApplies: Int?,
    val tcString: String,
    val gpp: String,
    val gppSid: String,
    val usPrivacy: String,
    /** IABTCF_PurposeConsents[0] (Purpose 1: store/access information on a device); null if unknown. */
    val purpose1: Boolean?,
) {
    companion object {
        val EMPTY = ConsentSnapshot(null, "", "", "", "", null)
    }
}

internal class AppSnapshot(val bundle: String, val version: String)

internal class AdRequestParams(
    val appKey: String,
    val placement: String,
    val format: AdFormat,
    val sizes: List<String>,
    val test: Boolean,
    val coppa: Boolean,
    val userId: String?,
    val custom: String?,
    val videoSupported: Boolean,
)

internal object AdRequestBuilder {
    private const val ZERO_ID = "00000000-0000-0000-0000-000000000000"

    /**
     * Picks the device key: the advertising id when present, non-zero, not limited, the app is not
     * child-directed and (under GDPR) Purpose 1 consent is not denied; otherwise App Set ID; otherwise the SDK's
     * per-install UUID.
     */
    fun resolveIdentity(
        gaid: AdvertisingIdInfo?,
        appSetId: String?,
        sdkId: String,
        childDirected: Boolean,
        consent: ConsentSnapshot,
    ): Identity {
        val gaidId = gaid?.id?.takeIf { it.isNotBlank() && it != ZERO_ID }
        val lmt = childDirected || gaid?.limitAdTracking == true || (gaid != null && gaidId == null)
        val consentDenied = consent.gdprApplies == 1 && consent.purpose1 != true
        if (!childDirected && !lmt && !consentDenied && gaidId != null) return Identity(gaidId, "gaid", false, sdkId)
        if (!childDirected && !appSetId.isNullOrBlank()) return Identity(appSetId, "appset", lmt, sdkId)
        return Identity(sdkId, "sdk", lmt, sdkId)
    }

    fun build(p: AdRequestParams, d: DeviceSnapshot, app: AppSnapshot, consent: ConsentSnapshot, sdkVersion: String): JSONObject {
        val device = JSONObject()
            .put("os", "android")
            .put("osv", d.osv)
            .put("make", d.make)
            .put("model", d.model)
            .put("ifa", d.identity.ifa)
            .put("ifa_type", d.identity.ifaType)
            .put("lmt", d.identity.lmt)
            .put("sdk_id", d.identity.sdkId)
            .put("lang", d.lang)
            .put("w", d.w)
            .put("h", d.h)
            .put("pxratio", d.pxratio.toDouble())
            .put("connection", d.connection)
            .put("carrier", d.carrier)
            .put("tz_offset_min", d.tzOffsetMin)
        val regs = JSONObject()
            .put("coppa", p.coppa)
            .put("gdpr", if (consent.gdprApplies == 1) 1 else 0)
            .put("tcf", consent.tcString)
            .put("gpp", consent.gpp)
            .put("us_privacy", consent.usPrivacy)
        if (consent.gppSid.isNotEmpty()) regs.put("gpp_sid", consent.gppSid)

        val kinds = JSONArray().put("image").put("html")
        if (p.format == AdFormat.NATIVE) kinds.put("native")
        if (p.videoSupported && (p.format == AdFormat.INTERSTITIAL || p.format == AdFormat.REWARDED)) kinds.put("video")

        val root = JSONObject()
            .put("v", 1)
            .put("app_key", p.appKey)
            .put("placement", p.placement)
            .put("format", p.format.wire)
            .put("sizes", JSONArray(p.sizes))
            .put("kinds", kinds)
            .put("device", device)
            .put("app", JSONObject().put("bundle", app.bundle).put("version", app.version).put("sdk_version", sdkVersion))
            .put("regs", regs)
            .put("test", p.test)
        if (p.format == AdFormat.REWARDED && (!p.userId.isNullOrEmpty() || !p.custom.isNullOrEmpty())) {
            val user = JSONObject()
            p.userId?.takeIf { it.isNotEmpty() }?.let { user.put("id", it.take(256)) }
            p.custom?.takeIf { it.isNotEmpty() }?.let { user.put("custom", it.take(256)) }
            root.put("user", user)
        }
        return root
    }

    /** `AdfactenSDK/<sdk> (android <osv>; <model>) <bundle>/<app_version>`, ASCII only (HTTP header safe). */
    fun userAgent(sdkVersion: String, osv: String, model: String, bundle: String, appVersion: String): String =
        ascii("AdfactenSDK/$sdkVersion (android $osv; $model) $bundle/$appVersion")

    private fun ascii(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in ' '..'~') c else '_')
    }
}
