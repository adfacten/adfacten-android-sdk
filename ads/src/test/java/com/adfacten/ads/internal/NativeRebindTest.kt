package com.adfacten.ads.internal

import com.adfacten.ads.NativeAd
import com.adfacten.ads.internal.track.AdEventTracker
import com.adfacten.ads.internal.track.BeaconQueue
import com.adfacten.ads.internal.track.BeaconSender
import com.adfacten.ads.internal.track.BeaconStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRebindTest {
    private val sent = ArrayList<String>()
    private val queue = BeaconQueue(
        storage = object : BeaconStorage {
            var data: String? = null
            override fun load() = data
            override fun save(data: String) { this.data = data }
        },
        sender = object : BeaconSender {
            override fun send(url: String): Int { sent.add(url); return 200 }
        },
        clock = { 1_000L },
        executor = { it.run() },
        schedule = { _, _ -> },
    )

    private fun nativeAd(): NativeAd {
        val result = AdResponseParser.parse(200, Fixtures.adJson(format = "native", kind = "native"), AdFormat.NATIVE, null, 1_794_150_000_000L, 0L)
        val data = (result as AdResult.Fill).ad
        return NativeAd(LoadedAd("plc_n", data), "Reklam")
    }

    /** What NativeAdView does on every bind. */
    private fun bind(ad: NativeAd): AdEventTracker {
        var created = 0
        val ev = ad.eventTracker { created++; AdEventTracker(ad.loaded.data, queue, "sdk-1") { 1_000L } }
        assertTrue(created <= 1)
        return ev
    }

    @Test fun rebindingRecordsOneImpression() {
        val ad = nativeAd()
        val first = bind(ad)
        assertTrue(first.render()) // onShown fires once
        first.view()

        val again = bind(ad) // same view again, or a RecyclerView rebind into another view
        assertSame(first, again)
        assertFalse(again.render()) // no second onShown
        again.view()
        assertTrue(again.rendered) // clicks stay enabled after a rebind

        assertEquals(1, sent.count { it.contains("e=render") })
        assertEquals(1, sent.count { it.contains("e=view") })
    }

    @Test fun eachAdHasItsOwnTracker() {
        val a = bind(nativeAd())
        val b = bind(nativeAd())
        assertFalse(a === b)
    }
}
