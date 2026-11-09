package com.adfacten.ads.spi

import android.view.View

/**
 * Internal plug-in point implemented by the optional `com.adfacten:ads-video` artifact (Media3 ExoPlayer).
 * Not meant for app code; it may change between SDK versions together with ads-video.
 */
public interface VideoPlayer {
    public val view: View

    public fun setListener(listener: Listener?)
    public fun prepare(url: String)
    public fun play()
    public fun pause()
    public fun setMuted(muted: Boolean)
    public fun isPlaying(): Boolean
    public fun currentPositionMs(): Long
    public fun durationMs(): Long
    public fun release()

    public interface Listener {
        public fun onFirstFrame()
        public fun onCompleted()
        public fun onError(message: String)
    }
}
