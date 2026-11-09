package com.adfacten.ads.internal.track

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import com.adfacten.ads.internal.Sdk
import com.adfacten.ads.internal.ViewRule
import com.adfacten.ads.internal.safe

/**
 * Watches a view on the main thread (every 100 ms while attached + on scroll/layout changes):
 * - [onRender] once, the first time any part of the view is really on screen;
 * - [onView] once, when ≥ minRatio of its area stays visible for minMs (the view rule).
 * [isActive] can veto counting (e.g. a paused video). Call [stop] when the ad is gone.
 */
internal class VisibilityTracker(
    private val view: View,
    rule: ViewRule,
    private val onRender: () -> Unit,
    private val onView: () -> Unit,
    private val isActive: () -> Boolean = { true },
) {
    private val timer = ViewTimer(rule.minRatio, rule.minMs)
    private val rect = Rect()
    private var rendered = false
    private var stopped = false
    private var scheduled = false

    private val tick = Runnable {
        scheduled = false
        check()
        schedule()
    }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { check() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            safe("tracker attach") {
                v.viewTreeObserver.addOnScrollChangedListener(scrollListener)
                schedule()
            }
        }

        override fun onViewDetachedFromWindow(v: View) {
            safe("tracker detach") {
                v.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
                timer.reset()
                Sdk.main.removeCallbacks(tick)
                scheduled = false
            }
        }
    }

    fun start() {
        view.addOnAttachStateChangeListener(attachListener)
        if (view.isAttachedToWindow) attachListener.onViewAttachedToWindow(view)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        Sdk.main.removeCallbacks(tick)
        view.removeOnAttachStateChangeListener(attachListener)
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
    }

    /** Current visible ratio of the view, 0 when hidden, detached, in a stopped window or (nearly) transparent. */
    fun visibleRatio(): Float {
        if (!view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE) return 0f
        if (effectiveAlpha(view) < 0.1f) return 0f
        if (!view.getGlobalVisibleRect(rect)) return 0f
        return ViewabilityMath.ratio(rect.width(), rect.height(), view.width, view.height)
    }

    private fun check() = safe("visibility check") {
        if (stopped) return@safe
        val ratio = if (isActive()) visibleRatio() else 0f
        if (!rendered && ratio > 0f) {
            rendered = true
            onRender()
        }
        if (rendered && timer.update(ratio, SystemClock.elapsedRealtime())) {
            onView()
            stop()
        }
    }

    private fun schedule() {
        if (stopped || scheduled || !view.isAttachedToWindow) return
        scheduled = true
        Sdk.main.postDelayed(tick, 100)
    }

    private fun effectiveAlpha(v: View): Float {
        var a = 1f
        var cur: View? = v
        while (cur != null) {
            a *= cur.alpha
            cur = cur.parent as? View
        }
        return a
    }
}
