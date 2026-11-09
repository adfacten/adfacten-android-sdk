package com.adfacten.ads.internal.track

import com.adfacten.ads.internal.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

internal interface BeaconStorage {
    fun load(): String?
    fun save(data: String)
}

internal interface BeaconSender {
    /** Returns the HTTP status, or -1 for a network error. */
    fun send(url: String): Int
}

/** Small JSON file, replaced atomically. The path is resolved lazily so no disk access happens on the caller's thread. */
internal class FileBeaconStorage(fileProvider: () -> File) : BeaconStorage {
    private val file by lazy(fileProvider)
    override fun load(): String? = if (file.exists()) file.readText() else null
    override fun save(data: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(data)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * Persistent, deduplicating tracking-beacon queue.
 *
 * - Each beacon has a key (`<imp id>|<event>`); a key is accepted once. Sent keys are remembered for 48 h, so
 *   a beacon never fires twice even across process restarts.
 * - Failures (network errors, 5xx, 408, 429) are retried with exponential backoff (10 s … 1 h) for up to 24 h
 *   after the event; other 4xx are dropped (permanent).
 * - Everything runs on one serial [executor]; [schedule] arms the next retry.
 */
internal class BeaconQueue(
    private val storage: BeaconStorage,
    private val sender: BeaconSender,
    private val clock: () -> Long,
    private val executor: Executor,
    private val schedule: (delayMs: Long, task: Runnable) -> Unit,
) {
    internal class Beacon(val key: String, val url: String, val createdAt: Long, var attempts: Int, var nextAt: Long)

    private val pending = ArrayList<Beacon>()
    private val seen = LinkedHashMap<String, Long>()
    private var loaded = false
    private var retryArmedFor = Long.MAX_VALUE

    fun enqueue(key: String, url: String) {
        executor.execute {
            guard {
                ensureLoaded()
                val now = clock()
                if (seen.containsKey(key)) {
                    Logger.d("beacon $key already fired, skipped")
                    return@guard
                }
                seen[key] = now
                pending.add(Beacon(key, url, now, 0, now))
                while (pending.size > MAX_PENDING) pending.removeAt(0)
                persist()
                flushNow()
            }
        }
    }

    fun flush() {
        executor.execute { guard { ensureLoaded(); flushNow() } }
    }

    /** For tests and diagnostics; call on the queue executor. */
    internal fun pendingSnapshot(): List<Beacon> = pending.toList()

    private fun flushNow() {
        val now = clock()
        var changed = false
        val it = pending.iterator()
        var offline = false
        while (it.hasNext()) {
            val b = it.next()
            if (now - b.createdAt > MAX_AGE_MS) {
                Logger.w("beacon ${b.key} dropped after 24 h without delivery")
                it.remove(); changed = true; continue
            }
            if (offline || b.nextAt > now) continue
            val code = try { sender.send(b.url) } catch (e: Exception) { -1 }
            when {
                code in 200..399 -> { it.remove(); changed = true }
                code in 400..499 && code != 408 && code != 429 -> {
                    Logger.w("beacon ${b.key} rejected with HTTP $code, dropped")
                    it.remove(); changed = true
                }
                else -> {
                    b.attempts++
                    b.nextAt = now + retryDelay(b.attempts)
                    changed = true
                    if (code == -1) offline = true // don't hammer the radio; retry all later
                }
            }
        }
        pruneSeen(now)
        if (changed) persist()
        armRetry(now)
    }

    private fun armRetry(now: Long) {
        val next = pending.minOfOrNull { it.nextAt } ?: return
        if (next >= retryArmedFor && retryArmedFor > now) return
        retryArmedFor = next
        schedule((next - now).coerceAtLeast(1_000L), Runnable {
            executor.execute { guard { retryArmedFor = Long.MAX_VALUE; flushNow() } }
        })
    }

    private fun pruneSeen(now: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > SEEN_TTL_MS || seen.size > MAX_SEEN) it.remove() else break
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = try { storage.load() } catch (e: Exception) { null } ?: return
        try {
            val o = JSONObject(raw)
            val p = o.optJSONArray("pending") ?: JSONArray()
            for (i in 0 until p.length()) {
                val b = p.getJSONObject(i)
                pending.add(Beacon(b.getString("k"), b.getString("u"), b.getLong("c"), b.optInt("a"), b.optLong("n")))
            }
            val s = o.optJSONObject("seen")
            if (s != null) {
                val entries = s.keys().asSequence().map { it to s.getLong(it) }.sortedBy { it.second }
                for ((k, t) in entries) seen[k] = t
            }
        } catch (e: Exception) {
            Logger.w("beacon queue file unreadable, starting empty", e)
            pending.clear(); seen.clear()
        }
    }

    private fun persist() {
        val p = JSONArray()
        for (b in pending) {
            p.put(JSONObject().put("k", b.key).put("u", b.url).put("c", b.createdAt).put("a", b.attempts).put("n", b.nextAt))
        }
        val s = JSONObject()
        for ((k, t) in seen) s.put(k, t)
        try {
            storage.save(JSONObject().put("pending", p).put("seen", s).toString())
        } catch (e: Exception) {
            Logger.w("beacon queue could not be saved", e)
        }
    }

    private inline fun guard(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Logger.e("beacon queue error", t) }
    }

    companion object {
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val SEEN_TTL_MS = 48 * 60 * 60 * 1000L
        const val MAX_PENDING = 1000
        const val MAX_SEEN = 5000

        fun retryDelay(attempts: Int): Long = (10_000L shl (attempts - 1).coerceIn(0, 10)).coerceAtMost(60 * 60 * 1000L)
    }
}
