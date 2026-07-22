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

package ame.project.kanae

import android.util.Log

object LayoutMapper {
    private const val TAG = "LayoutMapper"

    // Key mapping for Overlays
    private val layoutMap = mapOf(
        // Chat
        "chat_boxed" to R.layout.item_chat_bubble_boxed,
        "chat_bubble" to R.layout.item_chat_bubble,
        "chat_bubble_horizontal" to R.layout.item_chat_bubble_horizontal,
        
        // Player
        "player_standard" to R.layout.overlay_layout,
        "player_modern" to R.layout.overlay_layout_modern,
        "player_glass" to R.layout.overlay_layout_glass,
        "player_retro" to R.layout.overlay_layout_retro,
        "player_immersive" to R.layout.overlay_layout_immersive,
        "player_compact" to R.layout.overlay_layout_compact,
        
        // Queue
        "queue_standard" to R.layout.overlay_queue_layout,
        "queue_modern" to R.layout.overlay_queue_modern,
        "queue_glass" to R.layout.overlay_queue_glass,
        "queue_neon" to R.layout.overlay_queue_neon,
        "queue_card" to R.layout.overlay_queue_card,
        "queue_minimal" to R.layout.overlay_queue_minimal,
        "queue_sketch" to R.layout.overlay_queue_sketch,
        
        // Queue Items
        "item_queue" to R.layout.item_queue,
        "item_queue_modern" to R.layout.item_queue_modern,
        "item_queue_glass" to R.layout.item_queue_glass,
        "item_queue_neon" to R.layout.item_queue_neon,
        "item_queue_card" to R.layout.item_queue_card,
        "item_queue_minimal" to R.layout.item_queue_minimal,
        "item_queue_sketch" to R.layout.item_queue_sketch,

        // Lyrics
        "lyrics_standard" to R.layout.overlay_lyrics_layout,
        "lyrics_glass" to R.layout.overlay_lyrics_glass,
        "lyrics_neon" to R.layout.overlay_lyrics_neon,
        "lyrics_sketch" to R.layout.overlay_lyrics_sketch,
        "lyrics_minimal" to R.layout.overlay_lyrics_minimal,

        // Notifications
        "notif_standard" to R.layout.overlay_tiktok_notification,

        // Join
        "join_card" to R.layout.overlay_tiktok_join,
        "join_list" to R.layout.overlay_tiktok_join_list,
        "join_pill" to R.layout.overlay_tiktok_join_pill,

        // Like
        "like_card" to R.layout.overlay_tiktok_like,
        "like_compact" to R.layout.overlay_tiktok_like_compact,
        "like_neon" to R.layout.overlay_tiktok_like_neon,

        // Follow
        "follow_standard" to R.layout.overlay_tiktok_follow,
        "follow_glass" to R.layout.overlay_tiktok_follow_glass,
        "follow_neon" to R.layout.overlay_tiktok_follow_neon,
        "follow_compact" to R.layout.overlay_tiktok_follow_compact
    )

    private val drawableMap = mapOf(
        "bg_chat_bubble" to R.drawable.bg_chat_bubble,
        "bg_chat_bubble_pill" to R.drawable.bg_chat_bubble_pill,
        "bg_chat_bubble_bordered" to R.drawable.bg_chat_bubble_bordered,
        "bg_chat_bubble_gradient" to R.drawable.bg_chat_bubble_gradient,
        "bg_chat_bubble_glass" to R.drawable.bg_chat_bubble_glass,
        "bg_chat_bubble_neon" to R.drawable.bg_chat_bubble_neon,
        "overlay_bg" to R.drawable.overlay_bg
    )

    fun getLayoutId(key: String?): Int {
        if (key == null) return 0
        val id = layoutMap[key] ?: 0
        if (id == 0) Log.w(TAG, "Layout key not found: $key")
        return id
    }

    fun getDrawableId(key: String?): Int {
        if (key == null) return 0
        val id = drawableMap[key] ?: 0
        if (id == 0 && key != "0" && key != "transparent") Log.w(TAG, "Drawable key not found: $key")
        return id
    }

    fun getLayoutKey(id: Int): String? {
        return layoutMap.entries.find { it.value == id }?.key
    }

    fun getDrawableKey(id: Int): String? {
        if (id == 0 || id == android.R.color.transparent) return "0"
        return drawableMap.entries.find { it.value == id }?.key
    }
}
