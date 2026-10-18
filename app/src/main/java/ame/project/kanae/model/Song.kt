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

package ame.project.kanae.model

import java.util.UUID

data class Song(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val youtubeUrl: String,
    val thumbnail: String? = null,
    val duration: Int = 0,           // seconds
    val channel: String? = null,
    val requestedBy: String? = null, // TikTok username
    val addedAt: Long = System.currentTimeMillis()
) {
    /** Formatted duration string e.g. "3:45" */
    val durationFormatted: String
        get() {
            val m = duration / 60
            val s = duration % 60
            return "%d:%02d".format(m, s)
        }
}
