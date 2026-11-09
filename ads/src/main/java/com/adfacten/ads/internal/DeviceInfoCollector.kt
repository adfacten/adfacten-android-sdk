package com.adfacten.ads.internal

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.adfacten.ads.BuildConfig
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Collects device/app/consent signals. Every method may touch IPC or disk: background threads only. */
internal class DeviceInfoCollector(private val context: Context) {

    val app: AppSnapshot by lazy {
        val version = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
        AppSnapshot(context.packageName, version)
    }

    private val baseUserAgent: String by lazy {
        AdRequestBuilder.userAgent(BuildConfig.SDK_VERSION, Build.VERSION.RELEASE ?: "", Build.MODEL ?: "", app.bundle, app.version)
    }

    /** Includes the wrapper suffix when [Sdk.wrapper] is set (its characters are already header safe). */
    val userAgent: String get() = baseUserAgent + (Sdk.wrapper?.userAgentSuffix ?: "")

    @Volatile private var cachedGaid: Pair<Long, AdvertisingIdInfo?>? = null
    @Volatile private var cachedAppSet: Pair<Long, String?>? = null

    fun snapshot(sdkId: String, childDirected: Boolean, consent: ConsentSnapshot): DeviceSnapshot {
        val dm = context.resources.displayMetrics
        val identity = AdRequestBuilder.resolveIdentity(
            gaid = if (childDirected) null else gaid(),
            appSetId = if (childDirected) null else appSetId(),
            sdkId = sdkId,
            childDirected = childDirected,
            consent = consent,
        )
        val conn = connection()
        return DeviceSnapshot(
            osv = Build.VERSION.RELEASE ?: "",
            make = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            identity = identity,
            lang = Locale.getDefault().language,
            w = dm.widthPixels,
            h = dm.heightPixels,
            pxratio = dm.density,
            connection = conn,
            carrier = carrier(),
            tzOffsetMin = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000,
        )
    }

    fun isCellular(): Boolean = connection() == "cellular"

    /** IAB TCF v2 / GPP / US Privacy keys from the default SharedPreferences (where CMPs write them). */
    fun consent(): ConsentSnapshot = try {
        // Same file as PreferenceManager.getDefaultSharedPreferences (deprecated API), where CMPs write.
        val p = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
        val all = p.all
        fun str(k: String): String = all[k]?.toString().orEmpty()
        val gdpr = when (val v = all["IABTCF_gdprApplies"]) {
            is Int -> v
            is Long -> v.toInt()
            is String -> v.toIntOrNull()
            is Boolean -> if (v) 1 else 0
            else -> null
        }
        val purposes = str("IABTCF_PurposeConsents")
        ConsentSnapshot(
            gdprApplies = gdpr,
            tcString = str("IABTCF_TCString"),
            gpp = str("IABGPP_HDR_GppString"),
            gppSid = str("IABGPP_GppSID"),
            usPrivacy = str("IABUSPrivacy_String"),
            purpose1 = if (purposes.isEmpty()) null else purposes[0] == '1',
        )
    } catch (e: Exception) {
        Logger.w("consent could not be read", e)
        ConsentSnapshot.EMPTY
    }

    private fun gaid(): AdvertisingIdInfo? {
        val now = SystemClock.elapsedRealtime()
        cachedGaid?.let { if (now - it.first < ID_TTL_MS) return it.second }
        val info = AdvertisingIds.gaid(context)
        cachedGaid = now to info
        return info
    }

    private fun appSetId(): String? {
        val now = SystemClock.elapsedRealtime()
        cachedAppSet?.let { if (now - it.first < ID_TTL_MS) return it.second }
        val id = AdvertisingIds.appSetId(context)
        cachedAppSet = now to id
        return id
    }

    private fun connection(): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "unknown"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            when (cm.activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                else -> "unknown"
            }
        }
    } catch (e: Exception) {
        "unknown"
    }

    private fun carrier(): String = try {
        (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkOperatorName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    companion object {
        private val ID_TTL_MS = TimeUnit.MINUTES.toMillis(10)
    }
}

/**
 * Google advertising id and App Set ID through reflection, so Play Services is never a hard dependency.
 * Add `com.adfacten:ads-gaid` (or play-services-ads-identifier / -appset yourself) to enable them.
 */
internal object AdvertisingIds {
    fun gaid(context: Context): AdvertisingIdInfo? = try {
        val client = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
        val info = client.getMethod("getAdvertisingIdInfo", Context::class.java).invoke(null, context)
        if (info == null) null else {
            val infoClass = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info")
            val id = infoClass.getMethod("getId").invoke(info) as String?
            val lat = infoClass.getMethod("isLimitAdTrackingEnabled").invoke(info) as Boolean
            AdvertisingIdInfo(id, lat)
        }
    } catch (e: ClassNotFoundException) {
        null
    } catch (t: Throwable) {
        Logger.d("advertising id unavailable: ${t.javaClass.simpleName}")
        null
    }

    fun appSetId(context: Context): String? = try {
        val appSet = Class.forName("com.google.android.gms.appset.AppSet")
        val client = appSet.getMethod("getClient", Context::class.java).invoke(null, context)
        val task = Class.forName("com.google.android.gms.appset.AppSetIdClient").getMethod("getAppSetIdInfo").invoke(client)
        val tasks = Class.forName("com.google.android.gms.tasks.Tasks")
        val taskClass = Class.forName("com.google.android.gms.tasks.Task")
        val info = tasks.getMethod("await", taskClass, Long::class.javaPrimitiveType, TimeUnit::class.java)
            .invoke(null, task, 2L, TimeUnit.SECONDS)
        Class.forName("com.google.android.gms.appset.AppSetIdInfo").getMethod("getId").invoke(info) as String?
    } catch (e: ClassNotFoundException) {
        null
    } catch (t: Throwable) {
        Logger.d("app set id unavailable: ${t.javaClass.simpleName}")
        null
    }
}
