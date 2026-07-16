package ame.project.kanae.player

import androidx.annotation.Keep

@Keep
object FastPlaybackState {
    @Volatile var positionMs: Long = 0
    @Volatile var durationMs: Long = 0
    
    // Cache for heavy objects
    @Volatile var currentSongJson: String? = null
    @Volatile var isPlaying: Boolean = false
    @Volatile var isPaused: Boolean = false
}
