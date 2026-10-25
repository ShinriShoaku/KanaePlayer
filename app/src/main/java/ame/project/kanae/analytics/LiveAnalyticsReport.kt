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

package ame.project.kanae.analytics

import androidx.annotation.Keep
import java.util.UUID

@Keep
data class RequesterStat(
    val username: String,
    var requestCount: Int = 0,
    var favoriteSong: String = "",
    val songCounts: MutableMap<String, Int> = mutableMapOf()
)

@Keep
data class GifterStat(
    val username: String,
    var giftCount: Int = 0,
    var estimatedCoins: Int = 0,
    var lastGiftName: String = ""
)

@Keep
data class RequestLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val requestedBy: String,
    val songTitle: String,
    var status: String = "QUEUED" // COMPLETED, SKIPPED, REJECTED, QUEUED
)

@Keep
data class ChatLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val username: String,
    val message: String
)

@Keep
data class LiveAnalyticsReport(
    val id: String = UUID.randomUUID().toString(),
    var streamerUsername: String = "",
    var startTimeMs: Long = System.currentTimeMillis(),
    var endTimeMs: Long = 0L,
    var durationMs: Long = 0L,

    // Overview
    var totalRequestsCount: Int = 0,
    var songsCompleted: Int = 0,
    var songsSkipped: Int = 0,
    var requestsRejected: Int = 0,
    var totalMusicDurationSec: Long = 0L,

    // Audience & Music Insights
    var requestersMap: MutableMap<String, RequesterStat> = mutableMapOf(),
    var songRequestsMap: MutableMap<String, Int> = mutableMapOf(),

    // TikTok Live Interaction & Monetization Metrics
    var giftersMap: MutableMap<String, GifterStat> = mutableMapOf(),
    var totalGiftsCount: Int = 0,
    var totalCoinsEstimated: Int = 0,
    var likesCount: Int = 0,
    var followersCount: Int = 0,
    var sharesCount: Int = 0,
    var joinsCount: Int = 0,

    // System & Bot Performance
    var npeSuccessCount: Int = 0,
    var ytdlpFallbackCount: Int = 0,
    var poTokenRefreshCount: Int = 0,
    var poTokenErrorCount: Int = 0,
    var autoReconnectCount: Int = 0,

    // Logs for export
    var requestLogs: MutableList<RequestLogEntry> = mutableListOf(),
    var chatLogs: MutableList<ChatLogEntry> = mutableListOf()
)
