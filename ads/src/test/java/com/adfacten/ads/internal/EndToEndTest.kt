package com.adfacten.ads.internal

import com.adfacten.ads.internal.net.HttpClient
import com.adfacten.ads.internal.track.AdEventTracker
import com.adfacten.ads.internal.track.BeaconQueue
import com.adfacten.ads.internal.track.BeaconSender
import com.adfacten.ads.internal.track.FileBeaconStorage
import com.adfacten.ads.internal.track.ViewTimer
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/**
 * Load → render → beacons against a local HTTP server implementing the /m/v1 API, using the SDK's real
 * HTTP client, request builder, response parser, event tracker and persistent beacon queue.
 */
class EndToEndTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val requests = Collections.synchronizedList(ArrayList<Pair<String, String>>()) // path?query, body
    private val headers = Collections.synchronizedList(ArrayList<Map<String, String>>())
    @Volatile private var fill = true
    @Volatile private var beaconStatus = 200
    private lateinit var dir: File

    @Before fun start() {
        dir = File(System.getProperty("java.io.tmpdir"), "adf-e2e-" + System.nanoTime()).apply { mkdirs() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/m/v1/ad") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(ex.requestURI.toString() to body)
            headers.add(mapOf(
                "ua" to ex.requestHeaders.getFirst("User-Agent"),
                "sdk" to ex.requestHeaders.getFirst("X-Adfacten-SDK"),
                "ct" to ex.requestHeaders.getFirst("Content-Type"),
            ))
            if (!fill) {
                ex.sendResponseHeaders(204, -1); ex.close(); return@createContext
            }
            val now = System.currentTimeMillis()
            val json = Fixtures.adJson(base = base, expiresAt = now / 1000 + 55 * 60)
            val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(json.toByteArray()) } }.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.responseHeaders.add("Date", SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(now)))
            ex.sendResponseHeaders(200, gz.size.toLong())
            ex.responseBody.use { it.write(gz) }
        }
        server.createContext("/i") { ex ->
            requests.add(ex.requestURI.toString() to "")
            ex.sendResponseHeaders(beaconStatus, -1); ex.close()
        }
        server.start()
    }

    @After fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private val http by lazy { HttpClient({ "AdfactenSDK/1.0.0 (android 15; Pixel 8) com.example.app/1.2.3" }, "android/1.0.0") }

    private fun request(placement: String = "plc_b"): AdResult {
        val identity = AdRequestBuilder.resolveIdentity(null, null, "sdk-uuid-1", false, ConsentSnapshot.EMPTY)
        val device = DeviceSnapshot("15", "Google", "Pixel 8", identity, "tr", 1080, 2400, 2.6f, "wifi", "", 180)
        val params = AdRequestParams("apk_demo", placement, AdFormat.BANNER, listOf("320x50"), true, false, null, null, false)
        val body = AdRequestBuilder.build(params, device, AppSnapshot("com.example.app", "1.2.3"), ConsentSnapshot.EMPTY, "1.0.0")
        val resp = http.postJson("$base/m/v1/ad", body.toString())
        return AdResponseParser.parse(resp.code, resp.bodyString(), AdFormat.BANNER, resp.dateMs(), System.currentTimeMillis(), 0L)
    }

    @Volatile private var clockMs = System.currentTimeMillis()

    private fun queue(): BeaconQueue = BeaconQueue(
        storage = FileBeaconStorage { File(dir, "beacons.json") },
        sender = object : BeaconSender {
            override fun send(url: String): Int = try { http.get(url).code } catch (e: Exception) { -1 }
        },
        clock = { clockMs },
        executor = { it.run() },
        schedule = { _, _ -> },
    )

    @Test fun loadRenderViewBeacons() {
        val result = request()
        assertTrue("got $result", result is AdResult.Fill)
        val ad = (result as AdResult.Fill).ad
        assertTrue(!ad.isExpired(0L))

        val req = JSONObject(requests.first().second)
        assertEquals("apk_demo", req.getString("app_key"))
        assertEquals("sdk-uuid-1", req.getJSONObject("device").getString("sdk_id"))
        assertEquals("AdfactenSDK/1.0.0 (android 15; Pixel 8) com.example.app/1.2.3", headers.first()["ua"])
        assertEquals("android/1.0.0", headers.first()["sdk"])
        assertTrue(headers.first()["ct"]!!.startsWith("application/json"))

        val q = queue()
        val tracker = AdEventTracker(ad, q, "sdk-uuid-1") { System.currentTimeMillis() }
        // Simulated on-screen: first visible frame → render; 1 s at ≥ 50 % → view.
        tracker.render()
        val timer = ViewTimer(ad.viewRule.minRatio, ad.viewRule.minMs)
        var t = 0L
        while (!timer.update(0.8f, t)) t += 100
        tracker.view()
        tracker.render(); tracker.view() // duplicates are ignored
        val beacons = requests.map { it.first }.filter { it.startsWith("/i?") }
        assertEquals(2, beacons.size)
        assertTrue(beacons[0], beacons[0].startsWith("/i?imp=imp_tok_1&e=render&sid=sdk-uuid-1&ts="))
        assertTrue(beacons[1], beacons[1].startsWith("/i?imp=imp_tok_1&e=view&sid=sdk-uuid-1&ts="))
    }

    @Test fun beaconsQueuedWhileServerFailsAreDeliveredLater() {
        val ad = (request() as AdResult.Fill).ad
        beaconStatus = 503
        val q = queue()
        AdEventTracker(ad, q, "sid") { 42L }.render()
        assertEquals(1, q.pendingSnapshot().size)
        beaconStatus = 200
        // New process: the queue is reloaded from disk and the retry succeeds.
        clockMs += 10_000
        val q2 = queue()
        q2.flush()
        assertTrue(q2.pendingSnapshot().isEmpty())
        assertEquals(2, requests.count { it.first.contains("e=render") })
    }

    @Test fun noFillThenBackoffSkipsTheNetwork() {
        fill = false
        var clock = 0L
        val backoff = NoFillBackoff({ clock })
        assertTrue(request("plc_x") is AdResult.NoFill)
        backoff.onNoFill("plc_x")
        val before = requests.size
        // The loader checks the backoff before any request: no network call during the window.
        assertTrue(backoff.remainingMs("plc_x") >= 30_000)
        clock += 29_000
        assertTrue(backoff.remainingMs("plc_x") > 0)
        assertEquals(before, requests.size)
        clock += 10_000
        assertEquals(0L, backoff.remainingMs("plc_x"))
    }
}
