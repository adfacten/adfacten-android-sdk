package com.adfacten.ads.internal

import com.adfacten.ads.internal.track.AdEventTracker
import com.adfacten.ads.internal.track.BeaconQueue
import com.adfacten.ads.internal.track.BeaconSender
import com.adfacten.ads.internal.track.BeaconStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconQueueTest {
    private var now = 1_000_000L
    private val scheduled = ArrayList<Pair<Long, Runnable>>()
    private val storage = object : BeaconStorage {
        var data: String? = null
        override fun load() = data
        override fun save(data: String) { this.data = data }
    }
    private val sent = ArrayList<String>()
    private var nextCodes = ArrayDeque<Int>()
    private val sender = object : BeaconSender {
        override fun send(url: String): Int { sent.add(url); return nextCodes.removeFirstOrNull() ?: 200 }
    }

    private fun queue() = BeaconQueue(storage, sender, { now }, { it.run() }, { d, r -> scheduled.add(d to r) })

    private fun runScheduled() {
        val tasks = scheduled.toList(); scheduled.clear(); tasks.forEach { it.second.run() }
    }

    @Test fun sendsOnceAndDedupes() {
        val q = queue()
        q.enqueue("imp|render", "https://t/i?e=render")
        q.enqueue("imp|render", "https://t/i?e=render")
        assertEquals(listOf("https://t/i?e=render"), sent)
        assertTrue(q.pendingSnapshot().isEmpty())
        // Dedupe survives a restart (seen keys are persisted).
        val q2 = queue()
        q2.enqueue("imp|render", "https://t/i?e=render")
        assertEquals(1, sent.size)
    }

    @Test fun retriesWithBackoffAndPersistsAcrossRestart() {
        nextCodes.addAll(listOf(-1, 503))
        val q = queue()
        q.enqueue("imp|view", "https://t/i?e=view")
        assertEquals(1, sent.size)
        assertEquals(1, q.pendingSnapshot().size)
        assertEquals(10_000L, scheduled.single().first)

        // Process dies; a new queue loads the pending beacon from disk.
        scheduled.clear()
        val q2 = queue()
        now += 10_000
        q2.flush()
        assertEquals(2, sent.size) // 503 → retry again in 20 s
        assertEquals(1, q2.pendingSnapshot().size)
        assertEquals(20_000L, scheduled.last().first)

        now += 20_000
        runScheduled()
        assertEquals(3, sent.size)
        assertTrue(q2.pendingSnapshot().isEmpty())
    }

    @Test fun offlineStopsTheBatchToSaveBattery() {
        nextCodes.addAll(listOf(-1, -1, -1))
        val q = queue()
        q.enqueue("a|render", "https://t/a")
        q.enqueue("b|render", "https://t/b")
        assertEquals(2, sent.size)
        now += 10_000
        q.flush() // a fails again (offline) → b is not tried in this round
        assertEquals(listOf("https://t/a", "https://t/b", "https://t/a"), sent)
        assertEquals(2, q.pendingSnapshot().size)
    }

    @Test fun drops4xxButRetries408And429() {
        nextCodes.addAll(listOf(404, 429))
        val q = queue()
        q.enqueue("a|render", "https://t/a")
        q.enqueue("b|render", "https://t/b")
        assertEquals(listOf("b|render"), q.pendingSnapshot().map { it.key })
    }

    @Test fun givesUpAfter24Hours() {
        repeat(100) { nextCodes.add(500) }
        val q = queue()
        q.enqueue("a|complete", "https://t/a")
        var guard = 0
        while (q.pendingSnapshot().isNotEmpty() && guard++ < 100) {
            now += BeaconQueue.retryDelay(q.pendingSnapshot().first().attempts)
            q.flush()
        }
        assertTrue(q.pendingSnapshot().isEmpty())
        assertTrue("retried ${sent.size} times", sent.size in 10..40)
        assertTrue(now - 1_000_000L > BeaconQueue.MAX_AGE_MS)
    }

    @Test fun retryDelayLadder() {
        assertEquals(10_000L, BeaconQueue.retryDelay(1))
        assertEquals(20_000L, BeaconQueue.retryDelay(2))
        assertEquals(3_600_000L, BeaconQueue.retryDelay(12))
    }

    @Test fun corruptFileStartsEmpty() {
        storage.data = "{not json"
        val q = queue()
        q.enqueue("a|render", "https://t/a")
        assertEquals(1, sent.size)
    }

    @Test fun trackerAppendsSidAndTsAndFiresEachEventOnce() {
        val ad = (AdResponseParser.parse(200, Fixtures.adJson(), AdFormat.BANNER, null, 1_794_150_000_000L, 0) as AdResult.Fill).ad
        val t = AdEventTracker(ad, queue(), "sdk 1", { 1234L })
        assertTrue(t.render()); assertTrue(!t.render())
        t.view(); t.view(); t.complete(); t.close()
        assertEquals(
            listOf(
                "https://trk.adfacten.com/i?imp=imp_tok_1&e=render&sid=sdk+1&ts=1234",
                "https://trk.adfacten.com/i?imp=imp_tok_1&e=view&sid=sdk+1&ts=1234",
                "https://trk.adfacten.com/i?imp=imp_tok_1&e=complete&sid=sdk+1&ts=1234",
                "https://trk.adfacten.com/i?imp=imp_tok_1&e=close&sid=sdk+1&ts=1234",
            ),
            sent,
        )
        assertEquals("https://x/i?sid=s&ts=1", AdEventTracker.decorate("https://x/i", "s", 1))
        sent.clear()
        t.external("impression", listOf("https://trk.adfacten.com/i?imp=imp_tok_1&e=render", "https://other.example/imp"))
        assertEquals(listOf("https://trk.adfacten.com/i?imp=imp_tok_1&e=render&sid=sdk+1&ts=1234", "https://other.example/imp"), sent)
    }
}
