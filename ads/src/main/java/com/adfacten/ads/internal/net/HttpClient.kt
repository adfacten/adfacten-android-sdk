package com.adfacten.ads.internal.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

internal class HttpResponse(val code: Int, val body: ByteArray, private val headers: Map<String, List<String>>) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }?.value?.firstOrNull()

    fun bodyString(): String = String(body, Charsets.UTF_8)

    /** The `Date` header in epoch ms, used to correct device clock skew for `expires_at`. */
    fun dateMs(): Long? = header("Date")?.let { parseHttpDate(it) }
}

/**
 * Minimal dependency-free HTTP client on HttpURLConnection: timeouts, gzip responses, response size cap.
 * Must only be called from SDK background threads (StrictMode-clean on the main thread).
 */
internal class HttpClient(
    private val userAgent: () -> String,
    private val sdkHeader: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) {
    @Throws(IOException::class)
    fun postJson(url: String, json: String): HttpResponse = execute(url, "POST", json.toByteArray(Charsets.UTF_8))

    @Throws(IOException::class)
    fun get(url: String, maxBytes: Int = MAX_BODY): HttpResponse = execute(url, "GET", null, maxBytes)

    @Throws(IOException::class)
    private fun execute(url: String, method: String, body: ByteArray?, maxBytes: Int = MAX_BODY): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = method
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent())
            conn.setRequestProperty("X-Adfacten-SDK", sdkHeader)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code == 204 || code == 304) null else if (code >= 400) conn.errorStream else conn.inputStream
            val raw = stream?.use { s ->
                if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                    GZIPInputStream(s).use { readCapped(it, maxBytes) }
                } else {
                    readCapped(s, maxBytes)
                }
            } ?: ByteArray(0)
            return HttpResponse(code, raw, conn.headerFields ?: emptyMap())
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MAX_BODY = 2 * 1024 * 1024

        fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("response larger than $maxBytes bytes")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}

internal fun parseHttpDate(s: String): Long? = try {
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(s)?.time
} catch (_: Exception) {
    null
}
