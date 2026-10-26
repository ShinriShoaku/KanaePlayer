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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.Chart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Membuat poster rekap live dengan tata letak 2 kolom (melebar).
 * Bagian atas berisi header, KPI, dan timeline full-width,
 * dilanjutkan dengan 2 kolom kartu grafik (kiri dan kanan).
 */
class LivePosterBuilder(
    private val context: Context,
    private val report: LiveAnalyticsReport,
    private val dateFormat: SimpleDateFormat,
    private val timeFormat: SimpleDateFormat,
    private val durationText: String
) {
    private val width = 1920
    private val margin = 48f
    private val pad = 40f
    private val gap = 32f
    private val chartScale = 1.25f

    private val fullCardW = width - margin * 2f
    private val colCardW = (fullCardW - gap) / 2f

    private interface Block {
        fun height(): Float
        fun draw(c: Canvas, x: Float, y: Float)
    }

    // ── Paint & teks ───────────────────────────────────────────────────────────

    private fun textPaint(color: Int, size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layoutOf(text: CharSequence, paint: TextPaint, w: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()

    private inner class TextBlock(text: CharSequence, paint: TextPaint, w: Int, private val after: Float = 0f) : Block {
        private val layout = layoutOf(text, paint, w)
        override fun height() = layout.height + after
        override fun draw(c: Canvas, x: Float, y: Float) {
            c.save(); c.translate(x, y); layout.draw(c); c.restore()
        }
    }

    private inner class SpaceBlock(private val h: Float) : Block {
        override fun height() = h
        override fun draw(c: Canvas, x: Float, y: Float) {}
    }

    private inner class ChartBlock(private val bmp: Bitmap) : Block {
        override fun height() = bmp.height.toFloat()
        override fun draw(c: Canvas, x: Float, y: Float) { c.drawBitmap(bmp, x, y, null) }
    }

    /** Baris tile statistik (angka besar + label). */
    private inner class StatRowBlock(private val items: List<Pair<String, String>>, private val w: Float) : Block {
        private val gapX = 20f
        private val tileH = 140f
        private val valuePaint = textPaint(ReportTheme.TEXT, 44f, true)
        private val labelPaint = textPaint(ReportTheme.DIM, 22f)
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ReportTheme.TILE }

        override fun height() = tileH
        override fun draw(c: Canvas, x: Float, y: Float) {
            val tw = (w - gapX * (items.size - 1)) / items.size
            items.forEachIndexed { i, (value, label) ->
                val left = x + i * (tw + gapX)
                c.drawRoundRect(RectF(left, y, left + tw, y + tileH), 20f, 20f, bg)
                valuePaint.textSize = 44f
                while (valuePaint.measureText(value) > tw - 24f && valuePaint.textSize > 20f) valuePaint.textSize -= 2f
                c.drawText(value, left + 16f, y + 64f, valuePaint)
                labelPaint.textSize = 22f
                while (labelPaint.measureText(label) > tw - 24f && labelPaint.textSize > 14f) labelPaint.textSize -= 1f
                c.drawText(label, left + 16f, y + 106f, labelPaint)
            }
        }
    }

    /** Kartu bernuansa navy dengan judul, subjudul, dan isi. */
    private inner class CardBlock(
        title: String,
        subtitle: String?,
        private val children: List<Block>,
        private val cardWidth: Float = fullCardW
    ) : Block {
        private val innerW = (cardWidth - pad * 2).toInt()
        private val titleBlock = TextBlock(title, textPaint(ReportTheme.TEXT, 36f, true), innerW - 24, if (subtitle != null) 4f else 16f)
        private val subBlock = subtitle?.let { TextBlock(it, textPaint(ReportTheme.DIM, 24f), innerW - 24, 20f) }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ReportTheme.CARD }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ReportTheme.CARD_BORDER; style = Paint.Style.STROKE; strokeWidth = 2f
        }
        private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ReportTheme.TEAL }

        override fun height(): Float {
            var h = pad + titleBlock.height() + (subBlock?.height() ?: 0f)
            children.forEach { h += it.height() }
            return h + pad
        }

        override fun draw(c: Canvas, x: Float, y: Float) {
            val r = RectF(x, y, x + cardWidth, y + height())
            c.drawRoundRect(r, 28f, 28f, bg)
            c.drawRoundRect(r, 28f, 28f, border)

            var cy = y + pad
            c.drawRoundRect(RectF(x + pad, cy + 4f, x + pad + 8f, cy + 38f), 4f, 4f, accent)
            titleBlock.draw(c, x + pad + 24f, cy); cy += titleBlock.height()
            subBlock?.let { it.draw(c, x + pad + 24f, cy); cy += it.height() }
            children.forEach { it.draw(c, x + pad, cy); cy += it.height() }
        }
    }

    // ── Render chart off-screen ────────────────────────────────────────────────

    private fun renderChart(chart: Chart<*>, w: Int, h: Int): Bitmap {
        (chart as View).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, w, h)
        }
        chart.notifyDataSetChanged()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(bmp))
        return bmp
    }

    private fun chartCard(
        title: String,
        subtitle: String?,
        chart: Chart<*>,
        h: Int,
        cardWidth: Float = colCardW,
        extra: List<Block> = emptyList()
    ): Block {
        val innerW = (cardWidth - pad * 2).toInt()
        return CardBlock(title, subtitle, listOf<Block>(ChartBlock(renderChart(chart, innerW, h))) + extra, cardWidth)
    }

    private fun compact(v: Int): String = when {
        v >= 1_000_000 -> String.format("%.1fM", v / 1_000_000f)
        v >= 10_000 -> String.format("%.1fk", v / 1000f)
        else -> v.toString()
    }

    // ── Build ──────────────────────────────────────────────────────────────────

    fun build(): Bitmap {
        val topFullBlocks = mutableListOf<Block>()
        val leftBlocks = mutableListOf<Block>()
        val rightBlocks = mutableListOf<Block>()
        val isActive = report.endTimeMs == 0L
        val headerH = 360f

        // KPI full width (2 baris tile)
        topFullBlocks += StatRowBlock(
            listOf(
                compact(report.totalRequestsCount) to "Request",
                compact(report.songsCompleted) to "Diputar",
                compact(report.songsSkipped) to "Di-skip",
                compact(report.requestsRejected) to "Ditolak"
            ), fullCardW
        )
        topFullBlocks += StatRowBlock(
            listOf(
                compact(report.totalGiftsCount) to "Gift",
                compact(report.totalCoinsEstimated) to "Est. Coins",
                compact(report.likesCount) to "Likes",
                compact(report.followersCount) to "Follower baru"
            ), fullCardW
        )

        // 1. Timeline (Full Width)
        val line = LineChart(context)
        if (ReportCharts.timeline(line, context, ReportCharts.timelineSnapshots(report), timeFormat, false, chartScale)) {
            topFullBlocks += chartCard("Aktivitas Live", "Request (kiri) dan estimasi coin (kanan)", line, 500, cardWidth = fullCardW)
        }

        val colInnerW = (colCardW - pad * 2).toInt()

        // ── Kolom Kiri ───────────────────────────────────────────────────────

        // 2. Status request
        val queued = (report.totalRequestsCount - report.songsCompleted - report.songsSkipped - report.requestsRejected).coerceAtLeast(0)
        val statusPie = PieChart(context)
        val statusOk = ReportCharts.donut(
            statusPie,
            listOf(
                ReportCharts.Slice("Diputar (${report.songsCompleted})", report.songsCompleted.toFloat(), ReportTheme.TEAL),
                ReportCharts.Slice("Di-skip (${report.songsSkipped})", report.songsSkipped.toFloat(), ReportTheme.AMBER),
                ReportCharts.Slice("Ditolak (${report.requestsRejected})", report.requestsRejected.toFloat(), ReportTheme.CORAL),
                ReportCharts.Slice("Antrian ($queued)", queued.toFloat(), ReportTheme.PURPLE)
            ),
            "${report.totalRequestsCount}", "Request", "", false, chartScale
        )
        if (statusOk) {
            val musicMin = report.totalMusicDurationSec / 60
            val musicSec = report.totalMusicDurationSec % 60
            val avg = if (report.songsCompleted > 0) report.totalMusicDurationSec / report.songsCompleted else 0L
            leftBlocks += chartCard(
                "Status Request Musik", "Total musik ${musicMin}m ${musicSec}s",
                statusPie, 480, cardWidth = colCardW,
                extra = listOf(
                    SpaceBlock(8f),
                    TextBlock(
                        "Unik requester: ${report.requestersMap.size} penonton  ·  Rata-rata ${avg / 60}m ${avg % 60}s",
                        textPaint(ReportTheme.DIM, 24f), colInnerW
                    )
                )
            )
        }

        // 3. Top requester
        val reqChart = HorizontalBarChart(context)
        val topReq = report.requestersMap.values.sortedByDescending { it.requestCount }.take(5)
        if (ReportCharts.horizontalPills(reqChart, topReq.map { it.username to it.requestCount.toFloat() }, "Request", "", false, chartScale)) {
            leftBlocks += chartCard("Top Requester", "Penonton paling aktif request", reqChart, 100 * topReq.size + 80, cardWidth = colCardW)
        }

        // 4. Performa sistem
        val enginePie = PieChart(context)
        val engineOk = ReportCharts.donut(
            enginePie,
            listOf(
                ReportCharts.Slice("NewPipe (${report.npeSuccessCount})", report.npeSuccessCount.toFloat(), ReportTheme.GREEN),
                ReportCharts.Slice("yt-dlp (${report.ytdlpFallbackCount})", report.ytdlpFallbackCount.toFloat(), ReportTheme.AMBER)
            ),
            "${report.npeSuccessCount + report.ytdlpFallbackCount}", "Ekstraksi", "", false, chartScale
        )
        val sysLines = TextBlock(
            "PoToken refresh ${report.poTokenRefreshCount} · error ${report.poTokenErrorCount}\n" +
                    "Auto-reconnect ${report.autoReconnectCount} kali",
            textPaint(ReportTheme.DIM, 24f), colInnerW
        )
        leftBlocks += CardBlock(
            "Performa Sistem", "Engine pencarian lagu & koneksi",
            (if (engineOk) listOf<Block>(ChartBlock(renderChart(enginePie, colInnerW, 440)), SpaceBlock(8f)) else emptyList()) + sysLines,
            cardWidth = colCardW
        )

        // ── Kolom Kanan ──────────────────────────────────────────────────────

        // 5. Top gifter
        val gifterChart = HorizontalBarChart(context)
        val topGifters = report.giftersMap.values.sortedByDescending { it.estimatedCoins }.take(5)
        if (ReportCharts.horizontalPills(gifterChart, topGifters.map { it.username to it.estimatedCoins.toFloat() }, "Coins", "", false, chartScale)) {
            rightBlocks += chartCard("Top Gifter", "Estimasi coin per penonton", gifterChart, 100 * topGifters.size + 80, cardWidth = colCardW)
        }

        // 6. Lagu terfavorit
        val songChart = BarChart(context)
        val topSongs = report.songRequestsMap.entries.sortedByDescending { it.value }.take(5)
        val songItems = topSongs.map { (if (it.key.length > 12) it.key.take(11) + "…" else it.key) to it.value.toFloat() }
        if (ReportCharts.verticalBars(songChart, songItems, "Request", "", false, chartScale)) {
            rightBlocks += chartCard("Lagu Terfavorit", "Paling sering di-request", songChart, 440, cardWidth = colCardW)
        }

        // 7. Interaksi
        val interPie = PieChart(context)
        val totalInter = report.likesCount + report.totalGiftsCount + report.followersCount + report.sharesCount + report.joinsCount
        val interOk = ReportCharts.donut(
            interPie,
            listOf(
                ReportCharts.Slice("Likes (${report.likesCount})", report.likesCount.toFloat(), ReportTheme.CORAL),
                ReportCharts.Slice("Gifts (${report.totalGiftsCount})", report.totalGiftsCount.toFloat(), ReportTheme.AMBER),
                ReportCharts.Slice("Followers (${report.followersCount})", report.followersCount.toFloat(), ReportTheme.GREEN),
                ReportCharts.Slice("Shares (${report.sharesCount})", report.sharesCount.toFloat(), ReportTheme.TEAL),
                ReportCharts.Slice("Joins (${report.joinsCount})", report.joinsCount.toFloat(), ReportTheme.PURPLE)
            ),
            compact(totalInter), "Interaksi", "", false, chartScale
        )
        if (interOk) rightBlocks += chartCard("Interaksi Penonton", "Distribusi aktivitas live", interPie, 480, cardWidth = colCardW)

        // Footer
        val footer = TextBlock(
            "Dibuat oleh KanaePlayer  ·  ${dateFormat.format(Date())}",
            textPaint(ReportTheme.DIM, 26f), fullCardW.toInt()
        )

        // Hitung tinggi total
        var topHeight = headerH + gap
        topFullBlocks.forEach { topHeight += it.height() + gap }

        var leftHeight = 0f
        leftBlocks.forEach { leftHeight += it.height() + gap }

        var rightHeight = 0f
        rightBlocks.forEach { rightHeight += it.height() + gap }

        val columnsHeight = maxOf(leftHeight, rightHeight)
        val total = topHeight + columnsHeight + footer.height() + gap + 24f

        val bitmap = Bitmap.createBitmap(width, total.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, total)
        drawHeader(canvas, isActive)

        // Render bagian atas (Full Width)
        var y = headerH + gap
        topFullBlocks.forEach { b ->
            b.draw(canvas, margin, y)
            y += b.height() + gap
        }

        // Render 2 Kolom (Kiri & Kanan)
        val startY = y
        var leftY = startY
        leftBlocks.forEach { b ->
            b.draw(canvas, margin, leftY)
            leftY += b.height() + gap
        }

        val rightX = margin + colCardW + gap
        var rightY = startY
        rightBlocks.forEach { b ->
            b.draw(canvas, rightX, rightY)
            rightY += b.height() + gap
        }

        // Render Footer
        footer.draw(canvas, margin, startY + columnsHeight)
        return bitmap
    }

    // ── Background & header ────────────────────────────────────────────────────

    private fun drawBackground(c: Canvas, totalH: Float) {
        c.drawColor(ReportTheme.BG)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width / 2f, 0f, 1200f,
                intArrayOf(ReportTheme.alpha(ReportTheme.TEAL, 80), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, width.toFloat(), minOf(1200f, totalH), glow)
    }

    private fun drawHeader(c: Canvas, isActive: Boolean) {
        val labelPaint = textPaint(ReportTheme.TEAL, 32f, true).apply { letterSpacing = 0.25f }
        c.drawText("LIVE RECAP", margin, 100f, labelPaint)

        val handlePaint = textPaint(ReportTheme.TEXT, 72f, true)
        val handle = "@${report.streamerUsername}"
        while (handlePaint.measureText(handle) > fullCardW && handlePaint.textSize > 36f) handlePaint.textSize -= 2f
        c.drawText(handle, margin, 190f, handlePaint)

        // Chip tanggal / durasi / status
        var x = margin
        val chips = listOf(
            dateFormat.format(Date(report.startTimeMs)),
            "Durasi $durationText",
            if (isActive) "Sedang live" else "Selesai"
        )
        val chipText = textPaint(ReportTheme.TEXT, 26f)
        val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ReportTheme.TILE }
        var chipY = 230f
        chips.forEachIndexed { i, label ->
            val w = chipText.measureText(label) + 40f
            if (x + w > margin + fullCardW) { x = margin; chipY += 64f }
            val bg = if (i == 2 && isActive) Paint(chipBg).apply { color = ReportTheme.alpha(ReportTheme.CORAL, 90) } else chipBg
            c.drawRoundRect(RectF(x, chipY, x + w, chipY + 52f), 26f, 26f, bg)
            c.drawText(label, x + 20f, chipY + 36f, chipText)
            x += w + 14f
        }
    }
}