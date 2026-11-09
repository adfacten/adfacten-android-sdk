package com.adfacten.ads.internal.track

/** Pure viewability math (unit-tested); the Android side feeds it from [VisibilityTracker]. */
internal object ViewabilityMath {
    /** Visible area / total area, 0..1. */
    fun ratio(visibleW: Int, visibleH: Int, totalW: Int, totalH: Int): Float {
        if (totalW <= 0 || totalH <= 0 || visibleW <= 0 || visibleH <= 0) return 0f
        val r = (visibleW.toLong() * visibleH).toFloat() / (totalW.toLong() * totalH).toFloat()
        return r.coerceIn(0f, 1f)
    }
}

/**
 * The view rule: the ratio must stay ≥ [minRatio] continuously for [minMs]. A dip below resets the timer.
 * [update] returns true exactly once, when the rule is met.
 */
internal class ViewTimer(private val minRatio: Float, private val minMs: Long) {
    private var since = -1L
    var satisfied = false; private set

    fun update(ratio: Float, nowMs: Long): Boolean {
        if (satisfied) return false
        if (ratio + 1e-4f < minRatio) {
            since = -1L
            return false
        }
        if (since < 0) since = nowMs
        if (nowMs - since >= minMs) {
            satisfied = true
            return true
        }
        return false
    }

    fun reset() { since = -1L }
}
