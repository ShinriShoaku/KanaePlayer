/*
 * KanaePlayer -
 * Copyright (C) 2026 KanaePlayer Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed WITHOUT ANY WARRANTY; see the
 * GNU General Public License for more details: <https://www.gnu.org/licenses/>.
 */

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
