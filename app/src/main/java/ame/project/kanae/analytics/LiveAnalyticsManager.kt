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

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class LiveAnalyticsManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val reportsFile = File(appContext.filesDir, "live_reports.json")

    private var activeReport: LiveAnalyticsReport? = null
    private val pastReports = CopyOnWriteArrayList<LiveAnalyticsReport>()

    companion object {
        private const val TAG = "LiveAnalyticsManager"
        private const val MAX_SAVED_REPORTS = 50

        @Volatile
        private var INSTANCE: LiveAnalyticsManager? = null

        fun getInstance(context: Context): LiveAnalyticsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LiveAnalyticsManager(context).also { INSTANCE = it }
            }
        }
    }

    init {
        loadReports()
    }

    @Synchronized
    private fun loadReports() {
        try {
            if (reportsFile.exists()) {
                val json = reportsFile.readText()
                val type = object : TypeToken<List<LiveAnalyticsReport>>() {}.type
                val list: List<LiveAnalyticsReport>? = gson.fromJson(json, type)
                if (!list.isNullOrEmpty()) {
                    pastReports.clear()
                    pastReports.addAll(list)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading live reports", e)
        }
    }

    @Synchronized
    private fun saveReports() {
        try {
            val allList = mutableListOf<LiveAnalyticsReport>()
            activeReport?.let { allList.add(it) }
            allList.addAll(pastReports)
            
            // Limit to MAX_SAVED_REPORTS
            val trimmed = if (allList.size > MAX_SAVED_REPORTS) allList.take(MAX_SAVED_REPORTS) else allList
            reportsFile.writeText(gson.toJson(trimmed))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving live reports", e)
        }
    }

    @Synchronized
    fun startSession(streamerUsername: String) {
        if (activeReport != null) {
            endSession()
        }
        val cleanName = streamerUsername.trim().removePrefix("@")
        val report = LiveAnalyticsReport(
            streamerUsername = if (cleanName.isBlank()) "Streamer" else cleanName,
            startTimeMs = System.currentTimeMillis()
        )
        activeReport = report
        saveReports()
        Log.d(TAG, "Started live analytics session for @${report.streamerUsername}")
    }

    @Synchronized
    fun endSession() {
        val current = activeReport ?: return
        current.endTimeMs = System.currentTimeMillis()
        current.durationMs = current.endTimeMs - current.startTimeMs
        
        // Push to past reports (at top)
        pastReports.add(0, current)
        activeReport = null
        saveReports()
        Log.d(TAG, "Ended live analytics session: ${current.durationMs / 1000}s")
    }

    fun isSessionActive(): Boolean = activeReport != null

    fun getActiveReport(): LiveAnalyticsReport? = activeReport

    fun getCurrentOrLastReport(): LiveAnalyticsReport? {
        return activeReport ?: pastReports.firstOrNull()
    }

    fun getPastReports(): List<LiveAnalyticsReport> = pastReports.toList()

    fun getAllReports(): List<LiveAnalyticsReport> {
        val list = mutableListOf<LiveAnalyticsReport>()
        activeReport?.let { list.add(it) }
        list.addAll(pastReports)
        return list
    }

    fun getReportById(id: String): LiveAnalyticsReport? {
        if (activeReport?.id == id) return activeReport
        return pastReports.firstOrNull { it.id == id }
    }

    @Synchronized
    fun deleteReport(id: String) {
        if (activeReport?.id == id) {
            activeReport = null
        } else {
            pastReports.removeAll { it.id == id }
        }
        saveReports()
    }

    @Synchronized
    fun clearAllReports() {
        activeReport = null
        pastReports.clear()
        if (reportsFile.exists()) {
            reportsFile.delete()
        }
    }

    // ── Tracking Events ────────────────────────────────────────────────────────

    @Synchronized
    fun trackRequestAdded(requestedBy: String, songTitle: String) {
        val report = activeReport ?: return
        report.totalRequestsCount++

        val cleanUser = if (requestedBy.isBlank()) "Anonymous" else requestedBy.trim()
        val cleanTitle = if (songTitle.isBlank()) "Unknown Song" else songTitle.trim()

        // Requesters Leaderboard
        val rStat = report.requestersMap.getOrPut(cleanUser) { RequesterStat(username = cleanUser) }
        rStat.requestCount++
        rStat.songCounts[cleanTitle] = (rStat.songCounts[cleanTitle] ?: 0) + 1
        rStat.favoriteSong = rStat.songCounts.maxByOrNull { it.value }?.key ?: cleanTitle

        // Song Requests Leaderboard
        report.songRequestsMap[cleanTitle] = (report.songRequestsMap[cleanTitle] ?: 0) + 1

        // Request Log
        report.requestLogs.add(0, RequestLogEntry(
            requestedBy = cleanUser,
            songTitle = cleanTitle,
            status = "QUEUED"
        ))
        saveReports()
    }

    @Synchronized
    fun trackSongCompleted(songTitle: String, durationSec: Long) {
        val report = activeReport ?: return
        report.songsCompleted++
        if (durationSec > 0) {
            report.totalMusicDurationSec += durationSec
        }

        val cleanTitle = songTitle.trim()
        val entry = report.requestLogs.firstOrNull { it.songTitle.equals(cleanTitle, ignoreCase = true) && it.status == "QUEUED" }
        if (entry != null) {
            entry.status = "COMPLETED"
        }
        saveReports()
    }

    @Synchronized
    fun trackSongSkipped(songTitle: String) {
        val report = activeReport ?: return
        report.songsSkipped++

        val cleanTitle = songTitle.trim()
        val entry = report.requestLogs.firstOrNull { it.songTitle.equals(cleanTitle, ignoreCase = true) && it.status == "QUEUED" }
        if (entry != null) {
            entry.status = "SKIPPED"
        }
        saveReports()
    }

    @Synchronized
    fun trackRequestRejected(requestedBy: String, queryOrTitle: String) {
        val report = activeReport ?: return
        report.requestsRejected++

        val cleanUser = if (requestedBy.isBlank()) "Anonymous" else requestedBy.trim()
        val cleanTitle = queryOrTitle.trim()

        report.requestLogs.add(0, RequestLogEntry(
            requestedBy = cleanUser,
            songTitle = cleanTitle,
            status = "REJECTED"
        ))
        saveReports()
    }

    @Synchronized
    fun trackLike(username: String, count: Int) {
        val report = activeReport ?: return
        report.likesCount += if (count > 0) count else 1
        saveReports()
    }

    @Synchronized
    fun trackFollow(username: String) {
        val report = activeReport ?: return
        report.followersCount++
        saveReports()
    }

    @Synchronized
    fun trackShare(username: String) {
        val report = activeReport ?: return
        report.sharesCount++
        saveReports()
    }

    @Synchronized
    fun trackJoin(username: String) {
        val report = activeReport ?: return
        report.joinsCount++
        saveReports()
    }

    @Synchronized
    fun trackGift(username: String, giftName: String, count: Int, giftId: Int = 0) {
        val report = activeReport ?: return
        val validCount = if (count > 0) count else 1
        report.totalGiftsCount += validCount

        // Estimate coins per gift heuristic
        val coinPerGift = when (giftName.lowercase()) {
            "rose", "tiktok" -> 1
            "finger heart", "heart" -> 5
            "doughnut", "donut" -> 30
            "paper crane" -> 99
            "cap", "sunglasses" -> 99
            "perfume" -> 20
            "panda" -> 5
            "love bang" -> 25
            "flower", "flowers" -> 7
            else -> 10
        }
        val estimatedCoins = coinPerGift * validCount
        report.totalCoinsEstimated += estimatedCoins

        val cleanUser = if (username.isBlank()) "Anonymous" else username.trim()
        val gStat = report.giftersMap.getOrPut(cleanUser) { GifterStat(username = cleanUser) }
        gStat.giftCount += validCount
        gStat.estimatedCoins += estimatedCoins
        gStat.lastGiftName = giftName

        saveReports()
    }

    @Synchronized
    fun trackExtraction(engine: String) {
        val report = activeReport ?: return
        if (engine.equals("NPE", ignoreCase = true)) {
            report.npeSuccessCount++
        } else if (engine.equals("YTDLP", ignoreCase = true)) {
            report.ytdlpFallbackCount++
        }
        saveReports()
    }

    @Synchronized
    fun trackPoToken(success: Boolean) {
        val report = activeReport ?: return
        if (success) {
            report.poTokenRefreshCount++
        } else {
            report.poTokenErrorCount++
        }
        saveReports()
    }

    @Synchronized
    fun trackAutoReconnect() {
        val report = activeReport ?: return
        report.autoReconnectCount++
        saveReports()
    }

    @Synchronized
    fun trackChat(username: String, message: String) {
        val report = activeReport ?: return
        if (message.isBlank()) return
        report.chatLogs.add(0, ChatLogEntry(username = username, message = message))
        // Limit max chat logs to 200 to save memory
        if (report.chatLogs.size > 200) {
            report.chatLogs.removeAt(report.chatLogs.size - 1)
        }
        saveReports()
    }
}
