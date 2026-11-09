package com.adfacten.ads.internal

import kotlin.random.Random

/**
 * Per-placement request backoff: after a no-fill the placement is not requested again for at
 * least 30 s (doubling up to 15 min on repeated no-fills, +0–25 % jitter so devices do not sync up).
 * Server errors use the same ladder; a 4xx refusal (app not approved, bundle mismatch, …) waits 5 min.
 * Network errors (offline) are not penalised. A fill resets the placement.
 */
internal class NoFillBackoff(
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) {
    private class Entry(var strikes: Int, var until: Long)
    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun remainingMs(placement: String): Long {
        val e = entries[placement] ?: return 0
        return (e.until - clock()).coerceAtLeast(0)
    }

    @Synchronized
    fun onNoFill(placement: String) = strike(placement)

    @Synchronized
    fun onServerError(placement: String) = strike(placement)

    @Synchronized
    fun onRejected(placement: String) {
        val e = entries.getOrPut(placement) { Entry(0, 0) }
        e.until = clock() + jitter(REJECT_MS)
    }

    @Synchronized
    fun onFill(placement: String) {
        entries.remove(placement)
    }

    private fun strike(placement: String) {
        val e = entries.getOrPut(placement) { Entry(0, 0) }
        e.strikes = (e.strikes + 1).coerceAtMost(16)
        val base = (MIN_MS shl (e.strikes - 1).coerceAtMost(5)).coerceAtMost(MAX_MS)
        e.until = clock() + jitter(base)
    }

    private fun jitter(base: Long): Long = base + (base * 0.25 * random.nextDouble()).toLong()

    companion object {
        const val MIN_MS = 30_000L
        const val MAX_MS = 15 * 60_000L
        const val REJECT_MS = 5 * 60_000L
    }
}
