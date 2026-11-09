package com.adfacten.ads.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.adfacten.ads.R

internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** The small "Reklam / Ad" disclosure badge shown on every creative. */
internal fun adLabel(context: Context, serverLabel: String?): TextView = TextView(context).apply {
    text = serverLabel ?: context.getString(R.string.adf_label)
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
    setPadding(context.dp(5), context.dp(1), context.dp(5), context.dp(1))
    background = GradientDrawable().apply {
        cornerRadius = context.dp(3).toFloat()
        setColor(0x99000000.toInt())
    }
    gravity = Gravity.CENTER
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
}

/** Image view that scales its bitmap to fit the available space keeping the aspect ratio (no empty click area). */
internal class FitImageView(context: Context) : ImageView(context) {
    init {
        scaleType = ScaleType.FIT_CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = drawable
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        if (d == null || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0 || maxW == 0 || maxH == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val scale = minOf(maxW.toFloat() / d.intrinsicWidth, maxH.toFloat() / d.intrinsicHeight)
        setMeasuredDimension((d.intrinsicWidth * scale).toInt(), (d.intrinsicHeight * scale).toInt())
    }
}

/** Follows one Activity's resume/pause/destroy without depending on androidx.lifecycle. */
internal class ActivityWatcher(
    private val activity: Activity,
    private val onResumed: (Boolean) -> Unit,
    private val onDestroyed: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        activity.application.registerActivityLifecycleCallbacks(this)
    }

    fun unregister() {
        if (!registered) return
        registered = false
        activity.application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(a: Activity) { if (a === activity) safe("watcher") { onResumed(true) } }
    override fun onActivityPaused(a: Activity) { if (a === activity) safe("watcher") { onResumed(false) } }
    override fun onActivityDestroyed(a: Activity) {
        if (a === activity) safe("watcher") { unregister(); onDestroyed() }
    }
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
}
