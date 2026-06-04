package ame.project.kanae.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*

/**
 * AudioPlayer
 *
 * Thin ExoPlayer wrapper that mirrors the mpv IPC commands used in main.py.
 * All methods are thread-safe and delegate to the main thread.
 */
class AudioPlayer(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object { private const val TAG = "AudioPlayer" }

    // ── State ─────────────────────────────────────────────────────────────────

    var onComplete: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null

    val isPlaying: Boolean get() = player?.isPlaying == true
    val isPaused: Boolean get() = player?.playWhenReady == false && player?.playbackState == Player.STATE_READY
    val currentPositionMs: Long get() = player?.currentPosition ?: 0L
    val durationMs: Long get() = player?.duration?.takeIf { it > 0 } ?: 0L

    // ── Internal ExoPlayer ────────────────────────────────────────────────────

    private var player: ExoPlayer? = null
    private var progressJob: Job? = null

    /** Must be called on the main thread (e.g. from Service.onCreate). */
    fun init() {
        player = ExoPlayer.Builder(context).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_ENDED -> {
                            Log.d(TAG, "Playback ended")
                            stopProgressUpdates()
                            onComplete?.invoke()
                        }
                        Player.STATE_READY -> {
                            if (p.playWhenReady) startProgressUpdates()
                        }
                        else -> {}
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(TAG, "ExoPlayer error: ${error.message}")
                    onError?.invoke(error.message ?: "Playback error")
                }
            })
        }
    }

    /** Plays a direct HTTP stream URL. */
    fun play(streamUrl: String) {
        val p = player ?: return
        Log.d(TAG, "play() url=${streamUrl.take(60)}…")
        p.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
        p.prepare()
        p.playWhenReady = true
        startProgressUpdates()
    }

    fun pause() {
        player?.playWhenReady = false
        stopProgressUpdates()
        Log.d(TAG, "pause()")
    }

    fun resume() {
        player?.playWhenReady = true
        startProgressUpdates()
        Log.d(TAG, "resume()")
    }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        stopProgressUpdates()
        Log.d(TAG, "stop()")
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    /** Release resources – call from Service.onDestroy. */
    fun release() {
        stopProgressUpdates()
        player?.release()
        player = null
    }

    // ── Progress ticker ───────────────────────────────────────────────────────

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                onProgress?.invoke(currentPositionMs, durationMs)
                delay(500)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }
}
