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

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.GsonBuilder
import ame.project.kanae.databinding.ActivityLiveReportBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveReportBinding
    private lateinit var analyticsManager: LiveAnalyticsManager
    private val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private var availableReports: List<LiveAnalyticsReport> = emptyList()
    private var selectedReport: LiveAnalyticsReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analyticsManager = LiveAnalyticsManager.getInstance(this)

        setupListeners()
        loadReportsList()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnDeleteSession.setOnClickListener {
            val report = selectedReport ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle("Hapus Sesi Live")
                .setMessage("Apakah Anda yakin ingin menghapus data laporan live ini?")
                .setPositiveButton("Hapus") { _, _ ->
                    analyticsManager.deleteReport(report.id)
                    Toast.makeText(this, "Sesi live berhasil dihapus", Toast.LENGTH_SHORT).show()
                    loadReportsList()
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        binding.btnGeneratePoster.setOnClickListener {
            val report = selectedReport ?: return@setOnClickListener
            generateAndSharePoster(report)
        }

        binding.btnExportData.setOnClickListener {
            val report = selectedReport ?: return@setOnClickListener
            showExportDialog(report)
        }

        binding.spinnerSessions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in availableReports.indices) {
                    selectedReport = availableReports[position]
                    displayReportData(availableReports[position])
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadReportsList() {
        availableReports = analyticsManager.getAllReports()
        if (availableReports.isEmpty()) {
            binding.tvStreamerHandle.text = "@NoData"
            binding.tvSessionTime.text = "Belum ada sesi live yang tercatat."
            binding.tvSessionDuration.text = "Durasi: -"
            return
        }

        val sessionLabels = availableReports.mapIndexed { idx, rep ->
            val isActive = rep.endTimeMs == 0L
            val dateStr = dateFormat.format(Date(rep.startTimeMs))
            val prefix = if (isActive) "🔴 [SESI AKTIF] " else "[${idx + 1}] "
            "$prefix@${rep.streamerUsername} ($dateStr)"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sessionLabels)
        binding.spinnerSessions.adapter = adapter

        val targetId = intent.getStringExtra("session_id")
        val targetIndex = if (targetId != null) {
            availableReports.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
        } else 0

        binding.spinnerSessions.setSelection(targetIndex)
        selectedReport = availableReports[targetIndex]
        displayReportData(availableReports[targetIndex])
    }

    private fun displayReportData(report: LiveAnalyticsReport) {
        renderCharts(report)
        val isActive = report.endTimeMs == 0L
        val startTimeStr = dateFormat.format(Date(report.startTimeMs))
        val endTimeStr = if (isActive) "Sekarang (Sedang Live)" else dateFormat.format(Date(report.endTimeMs))
        val durationMs = if (isActive) System.currentTimeMillis() - report.startTimeMs else report.durationMs

        binding.tvStreamerHandle.text = "@${report.streamerUsername}${if (isActive) " 🔴 LIVE" else ""}"
        binding.tvSessionTime.text = "$startTimeStr - $endTimeStr"
        binding.tvSessionDuration.text = "Durasi Streaming: ${formatDuration(durationMs)}"

        // 1. Overview
        binding.tvTotalRequests.text = "${report.totalRequestsCount}"
        binding.tvCompletedSongs.text = "${report.songsCompleted}"
        binding.tvSkippedRejected.text = "${report.songsSkipped} / ${report.requestsRejected}"

        val musicMin = report.totalMusicDurationSec / 60
        val musicSec = report.totalMusicDurationSec % 60
        binding.tvTotalMusicDuration.text = "Total Durasi Musik Mengudara: ${musicMin}m ${musicSec}s"

        // 2. Audience & Music Insights
        val topRequesters = report.requestersMap.values
            .sortedByDescending { it.requestCount }
            .take(5)

        if (topRequesters.isEmpty()) {
            binding.tvTopRequesters.text = "Belum ada data request penonton."
        } else {
            val sb = StringBuilder()
            topRequesters.forEachIndexed { i, req ->
                val fav = if (req.favoriteSong.isNotBlank()) " | Fav: ${req.favoriteSong}" else ""
                sb.append("${i + 1}. ${req.username} - ${req.requestCount} request$fav\n")
            }
            binding.tvTopRequesters.text = sb.toString().trim()
        }

        val topSongs = report.songRequestsMap.entries
            .sortedByDescending { it.value }
            .take(5)

        if (topSongs.isEmpty()) {
            binding.tvTopSongs.text = "Belum ada data lagu."
        } else {
            val sb = StringBuilder()
            topSongs.forEachIndexed { i, song ->
                sb.append("${i + 1}. ${song.key} (${song.value}x di-request)\n")
            }
            binding.tvTopSongs.text = sb.toString().trim()
        }

        val uniqueCount = report.requestersMap.size
        val avgDurationSec = if (report.songsCompleted > 0) report.totalMusicDurationSec / report.songsCompleted else 0L
        binding.tvAudienceStats.text = "Unik Requester: $uniqueCount penonton | Rata-rata Durasi Lagu: ${avgDurationSec / 60}m ${avgDurationSec % 60}s"

        // 3. TikTok Interaction
        val topGifters = report.giftersMap.values
            .sortedByDescending { it.estimatedCoins }
            .take(5)

        if (topGifters.isEmpty()) {
            binding.tvTopGifters.text = "Belum ada gift yang masuk."
        } else {
            val sb = StringBuilder()
            topGifters.forEachIndexed { i, g ->
                sb.append("${i + 1}. ${g.username} - ${g.giftCount} gift (~${g.estimatedCoins} coins)\n")
            }
            binding.tvTopGifters.text = sb.toString().trim()
        }

        binding.tvGiftsCoinsTotal.text = "Total Gift: ${report.totalGiftsCount} | Est. Coins Terkumpul: ${report.totalCoinsEstimated} 🪙"
        binding.tvInteractionCounts.text = "Likes: ${report.likesCount} ❤️ | Followers: ${report.followersCount} 👤 | Shares: ${report.sharesCount} ↗️ | Joins: ${report.joinsCount} 👋"

        // 4. System Performance
        val totalExtracts = report.npeSuccessCount + report.ytdlpFallbackCount
        val npePct = if (totalExtracts > 0) (report.npeSuccessCount * 100) / totalExtracts else 100
        val ytdlpPct = if (totalExtracts > 0) (report.ytdlpFallbackCount * 100) / totalExtracts else 0

        binding.tvEngineFallback.text = "Engine Fallback Rate: NewPipe ($npePct%) vs yt-dlp ($ytdlpPct%)"
        binding.tvPoTokenStatus.text = "PoToken / Bypass Status: Refresh: ${report.poTokenRefreshCount} | Errors: ${report.poTokenErrorCount}"
        binding.tvAutoReconnectStat.text = "Auto Reconnect Stat: ${report.autoReconnectCount} kali terputus & auto-reconnect"

    }

    // ── Chart (tema navy/teal, lihat ReportCharts.kt) ──────────────────────────

    private fun renderCharts(report: LiveAnalyticsReport) {
        // Overview
        ReportCharts.timeline(
            binding.chartTimelineActivity, this,
            ReportCharts.timelineSnapshots(report), timeFormat, animate = true
        )

        val queued = (report.totalRequestsCount - report.songsCompleted - report.songsSkipped - report.requestsRejected)
            .coerceAtLeast(0)
        ReportCharts.donut(
            binding.chartSongStatus,
            listOf(
                ReportCharts.Slice("Diputar (${report.songsCompleted})", report.songsCompleted.toFloat(), ReportTheme.TEAL),
                ReportCharts.Slice("Di-skip (${report.songsSkipped})", report.songsSkipped.toFloat(), ReportTheme.AMBER),
                ReportCharts.Slice("Ditolak (${report.requestsRejected})", report.requestsRejected.toFloat(), ReportTheme.CORAL),
                ReportCharts.Slice("Antrian ($queued)", queued.toFloat(), ReportTheme.PURPLE)
            ),
            "${report.totalRequestsCount}", "Request", "Belum ada request musik", animate = true
        )

        // Audience
        val topReq = report.requestersMap.values.sortedByDescending { it.requestCount }.take(5)
        ReportCharts.horizontalPills(
            binding.chartTopRequesters,
            topReq.map { it.username to it.requestCount.toFloat() },
            "Request", "Belum ada data requester", animate = true
        )

        val topSongs = report.songRequestsMap.entries.sortedByDescending { it.value }.take(5)
        ReportCharts.verticalBars(
            binding.chartTopSongs,
            topSongs.map { (if (it.key.length > 12) it.key.take(11) + "…" else it.key) to it.value.toFloat() },
            "Total Request", "Belum ada data lagu", animate = true
        )

        // TikTok
        val topGifters = report.giftersMap.values.sortedByDescending { it.estimatedCoins }.take(5)
        ReportCharts.horizontalPills(
            binding.chartTopGifters,
            topGifters.map { it.username to it.estimatedCoins.toFloat() },
            "Est. Coins", "Belum ada data gift", animate = true
        )

        val totalInter = report.likesCount + report.totalGiftsCount + report.followersCount +
                report.sharesCount + report.joinsCount
        ReportCharts.donut(
            binding.chartInteractions,
            listOf(
                ReportCharts.Slice("Likes (${report.likesCount})", report.likesCount.toFloat(), ReportTheme.CORAL),
                ReportCharts.Slice("Gifts (${report.totalGiftsCount})", report.totalGiftsCount.toFloat(), ReportTheme.AMBER),
                ReportCharts.Slice("Followers (${report.followersCount})", report.followersCount.toFloat(), ReportTheme.GREEN),
                ReportCharts.Slice("Shares (${report.sharesCount})", report.sharesCount.toFloat(), ReportTheme.TEAL),
                ReportCharts.Slice("Joins (${report.joinsCount})", report.joinsCount.toFloat(), ReportTheme.PURPLE)
            ),
            "$totalInter", "Interaksi", "Belum ada interaksi penonton", animate = true
        )

        // System
        ReportCharts.donut(
            binding.chartExtractorEngine,
            listOf(
                ReportCharts.Slice("NewPipe (${report.npeSuccessCount})", report.npeSuccessCount.toFloat(), ReportTheme.GREEN),
                ReportCharts.Slice("yt-dlp (${report.ytdlpFallbackCount})", report.ytdlpFallbackCount.toFloat(), ReportTheme.AMBER)
            ),
            "${report.npeSuccessCount + report.ytdlpFallbackCount}", "Ekstraksi",
            "Belum ada statistik pencarian lagu", animate = true
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = durationMs / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    // 📸 Poster infografis (tinggi dinamis, lihat LivePosterBuilder.kt)
    private fun generateAndSharePoster(report: LiveAnalyticsReport) {
        var bitmap: Bitmap? = null
        try {
            val durationMs = if (report.endTimeMs == 0L) System.currentTimeMillis() - report.startTimeMs else report.durationMs
            bitmap = LivePosterBuilder(
                context = this,
                report = report,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                durationText = formatDuration(durationMs)
            ).build()

            val file = File(cacheDir, "live_summary_poster.png")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            shareExportedFile(file, "image/png", "Bagikan Infografis Live")
        } catch (e: Throwable) {
            Toast.makeText(this, "Gagal membuat poster: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            bitmap?.recycle()
        }
    }

    // 📁 Export Data (JSON / CSV / TXT)
    private fun showExportDialog(report: LiveAnalyticsReport) {
        val options = arrayOf("Format JSON (.json)", "Format CSV (.csv)", "Format Ringkasan TXT (.txt)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Pilih Format Ekspor Data")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportJson(report)
                    1 -> exportCsv(report)
                    2 -> exportTxt(report)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun exportJson(report: LiveAnalyticsReport) {
        try {
            val jsonText = gson.toJson(report)
            val file = File(cacheDir, "live_report_${report.streamerUsername}_${report.startTimeMs}.json")
            file.writeText(jsonText)
            shareExportedFile(file, "application/json", "Ekspor Laporan JSON")
        } catch (e: Exception) {
            Toast.makeText(this, "Ekspor JSON gagal: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportCsv(report: LiveAnalyticsReport) {
        try {
            val sb = StringBuilder()
            sb.append("Timestamp,User,Song Title,Status\n")
            report.requestLogs.forEach { log ->
                val dateStr = dateFormat.format(Date(log.timestamp))
                val userClean = log.requestedBy.replace(",", " ")
                val titleClean = log.songTitle.replace(",", " ")
                sb.append("\"$dateStr\",\"$userClean\",\"$titleClean\",${log.status}\n")
            }
            val file = File(cacheDir, "live_requests_${report.streamerUsername}_${report.startTimeMs}.csv")
            file.writeText(sb.toString())
            shareExportedFile(file, "text/csv", "Ekspor Request CSV")
        } catch (e: Exception) {
            Toast.makeText(this, "Ekspor CSV gagal: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportTxt(report: LiveAnalyticsReport) {
        try {
            val sb = StringBuilder()
            val dateStr = dateFormat.format(Date(report.startTimeMs))
            sb.append("=========================================\n")
            sb.append("   KANAE PLAYER - LIVE ANALYTICS RECAP   \n")
            sb.append("=========================================\n")
            sb.append("Streamer: @${report.streamerUsername}\n")
            sb.append("Waktu Sesi: $dateStr\n")
            sb.append("Durasi Streaming: ${formatDuration(report.durationMs)}\n\n")

            sb.append("--- OVERVIEW MUSIC REQUESTS ---\n")
            sb.append("Total Request: ${report.totalRequestsCount}\n")
            sb.append("Diputar Selesai: ${report.songsCompleted}\n")
            sb.append("Di-Skip: ${report.songsSkipped}\n")
            sb.append("Ditolak (#cm / Limit): ${report.requestsRejected}\n\n")

            sb.append("--- INTERAKSI TIKTOK LIVE ---\n")
            sb.append("Total Gift: ${report.totalGiftsCount} (~${report.totalCoinsEstimated} coins)\n")
            sb.append("Likes: ${report.likesCount} | Followers Baru: ${report.followersCount}\n")
            sb.append("Shares: ${report.sharesCount} | Joins: ${report.joinsCount}\n\n")

            sb.append("--- TOP REQUESTERS LEADERBOARD ---\n")
            report.requestersMap.values.sortedByDescending { it.requestCount }.take(10).forEachIndexed { i, req ->
                sb.append("${i + 1}. ${req.username} - ${req.requestCount} requests\n")
            }

            val file = File(cacheDir, "rekap_live_${report.streamerUsername}_${report.startTimeMs}.txt")
            file.writeText(sb.toString())
            shareExportedFile(file, "text/plain", "Ekspor Rekap TXT")
        } catch (e: Exception) {
            Toast.makeText(this, "Ekspor TXT gagal: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Share via FileProvider. Chooser sistem (uid 1000 / Samsung) ikut membaca URI untuk preview,
     * jadi izin baca harus diberikan lewat ClipData + flag di intent, bukan hanya addFlags().
     */
    private fun shareExportedFile(file: File, mimeType: String, chooserTitle: String) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Cadangan: beri izin eksplisit ke semua aplikasi target yang bisa terlihat
        try {
            packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY).forEach { ri ->
                grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) { }

        startActivity(chooser)
    }
}