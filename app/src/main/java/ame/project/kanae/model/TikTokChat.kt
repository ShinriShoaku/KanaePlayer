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

data class TikTokChat(
    val uniqueId: String,       // TikTok username
    val nickname: String,
    val comment: String,
    val timestamp: Long = System.currentTimeMillis(),
    // parsed command fields
    val commandType: CommandType = CommandType.NONE,
    val commandArg: String? = null,
    val emotes: List<TikTokEmote> = emptyList()
) {
    enum class CommandType {
        NONE,
        REQUEST,
        SKIP,
        STOP,
        QUEUE,
        /** #cm <position>  →  commandArg = "1", "2", etc. (1-indexed) */
        CLEAR_MUSIC,
        /** #seekbar on/off to enable/disable all commands */
        COMMAND_TOGGLE
    }
}

data class TikTokEmote(
    val placeInComment: Int,
    val imageUrl: String
)
