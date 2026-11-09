package com.adfacten.ads.video

import android.content.Context
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.adfacten.ads.spi.VideoPlayer

/** Media3 ExoPlayer implementation of the SDK's video plug-in (progressive MP4 from VAST). */
internal class ExoVideoPlayer(context: Context) : VideoPlayer {
    private val surface = SurfaceView(context)
    private val frame = AspectFrame(context).apply {
        addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }
    private val container = FrameLayout(context).apply {
        addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }
    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        setVideoSurfaceView(surface)
        repeatMode = Player.REPEAT_MODE_OFF
    }
    private var listener: VideoPlayer.Listener? = null
    private var completed = false

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                listener?.onFirstFrame()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !completed) {
                    completed = true
                    listener?.onCompleted()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error.errorCodeName)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    frame.setAspect(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
            }
        })
    }

    override val view: View get() = container

    override fun setListener(listener: VideoPlayer.Listener?) {
        this.listener = listener
    }

    override fun prepare(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    override fun play() {
        if (!completed) player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    override fun isPlaying(): Boolean = player.isPlaying

    override fun currentPositionMs(): Long = player.currentPosition

    override fun durationMs(): Long = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

    override fun release() {
        listener = null
        player.release()
    }

    /** Letterboxes the surface to the video's aspect ratio. */
    private class AspectFrame(context: Context) : FrameLayout(context) {
        private var aspect = 16f / 9f

        fun setAspect(a: Float) {
            if (a > 0 && a != aspect) {
                aspect = a
                requestLayout()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec)
            val maxH = MeasureSpec.getSize(heightMeasureSpec)
            var w = maxW
            var h = (w / aspect).toInt()
            if (h > maxH) {
                h = maxH
                w = (h * aspect).toInt()
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }
}
