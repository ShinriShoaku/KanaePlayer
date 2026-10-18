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

package ame.project.kanae.subtitle

/**
 * One subtitle cue — a single line (or block) of text with a time range.
 *
 * @param startMs  cue start time in milliseconds
 * @param endMs    cue end time in milliseconds
 * @param text     display text (HTML tags stripped)
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)
