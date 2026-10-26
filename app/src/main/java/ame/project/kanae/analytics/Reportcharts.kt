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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.Chart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.IMarker
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.interfaces.dataprovider.BarDataProvider
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet
import com.github.mikephil.charting.model.GradientColor
import com.github.mikephil.charting.renderer.BarChartRenderer
import com.github.mikephil.charting.renderer.HorizontalBarChartRenderer
import com.github.mikephil.charting.utils.MPPointF
import com.github.mikephil.charting.utils.ViewPortHandler
import java.text.SimpleDateFormat
import java.util.Date

/** Palet warna bertema navy + teal (mengikuti referensi dashboard). */
object ReportTheme {
    val BG: Int = Color.parseColor("#0B1224")
    val CARD: Int = Color.parseColor("#121B36")
    val CARD_BORDER: Int = Color.parseColor("#1C2850")
    val TILE: Int = Color.parseColor("#1A2547")
    val GRID: Int = Color.parseColor("#1F2B4D")
    val TRACK: Int = Color.parseColor("#1A2444")
    val TEXT: Int = Color.parseColor("#E8ECF8")
    val DIM: Int = Color.parseColor("#8A94B0")

    val TEAL: Int = Color.parseColor("#2BB5B0")
    val TEAL_DARK: Int = Color.parseColor("#12484F")
    val CORAL: Int = Color.parseColor("#FF6B6B")
    val AMBER: Int = Color.parseColor("#FFB547")
    val PURPLE: Int = Color.parseColor("#7C6CF0")
    val PURPLE_DARK: Int = Color.parseColor("#3A3480")
    val GREEN: Int = Color.parseColor("#5ED6A0")

    val PALETTE: List<Int> = listOf(GREEN, PURPLE, AMBER, CORAL, TEAL)

    fun alpha(color: Int, a: Int): Int = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}

/** Semua fungsi mengembalikan true kalau ada data, false kalau kosong (chart di-clear + pesan no-data). */
object ReportCharts {

    // ── Data helper ────────────────────────────────────────────────────────────

    fun timelineSnapshots(report: LiveAnalyticsReport): List<TimelineSnapshot> {
        if (report.timelineSnapshots.isNotEmpty()) return report.timelineSnapshots
        val result = mutableListOf<TimelineSnapshot>()
        val start = report.startTimeMs
        val end = if (report.endTimeMs > 0) report.endTimeMs else System.currentTimeMillis()

        result.add(TimelineSnapshot(timestampMs = start, totalRequests = 0, totalCoins = 0))
        if (report.requestLogs.isNotEmpty()) {
            var reqCount = 0
            report.requestLogs.sortedBy { it.timestamp }.forEach { log ->
                reqCount++
                result.add(
                    TimelineSnapshot(
                        timestampMs = log.timestamp,
                        totalRequests = reqCount,
                        totalCoins = (reqCount * (report.totalCoinsEstimated.toFloat() /
                                report.totalRequestsCount.coerceAtLeast(1))).toInt()
                    )
                )
            }
        }
        result.add(
            TimelineSnapshot(
                timestampMs = end,
                totalRequests = report.totalRequestsCount,
                totalCoins = report.totalCoinsEstimated
            )
        )
        return result
    }

    // ── Formatter ──────────────────────────────────────────────────────────────

    class CompactFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val v = value.toInt()
            return if (v >= 1000) {
                val k = v / 100 / 10f
                if (k % 1f == 0f) "${k.toInt()}k" else "${k}k"
            } else v.toString()
        }
    }

    // ── Pembantu umum ──────────────────────────────────────────────────────────

    private fun Chart<*>.baseStyle(noDataMsg: String) {
        description.isEnabled = false
        setNoDataText(noDataMsg)
        setNoDataTextColor(ReportTheme.DIM)
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun emptyChart(chart: Chart<*>, msg: String): Boolean {
        chart.clear()
        chart.baseStyle(msg)
        chart.invalidate()
        return false
    }

    private fun styleXAxis(x: XAxis, labels: List<String>, scale: Float, labelCount: Int = labels.size) {
        x.position = XAxis.XAxisPosition.BOTTOM
        x.setDrawGridLines(false)
        x.setDrawAxisLine(false)
        x.textColor = ReportTheme.DIM
        x.textSize = 10f * scale
        x.granularity = 1f
        x.valueFormatter = IndexAxisValueFormatter(labels)
        x.setLabelCount(labelCount.coerceAtLeast(2), false)
    }

    private fun styleValueAxis(y: YAxis, scale: Float, color: Int = ReportTheme.DIM, grid: Boolean = true) {
        y.setDrawGridLines(grid)
        y.gridColor = ReportTheme.GRID
        y.gridLineWidth = 1f
        y.setDrawAxisLine(false)
        y.textColor = color
        y.textSize = 10f * scale
        y.axisMinimum = 0f
        y.valueFormatter = CompactFormatter()
    }

    private fun styleLegend(chart: Chart<*>, scale: Float) {
        chart.legend.apply {
            isEnabled = true
            textColor = ReportTheme.DIM
            textSize = 11f * scale
            form = Legend.LegendForm.CIRCLE
            formSize = 8f * scale
            horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
            verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
            orientation = Legend.LegendOrientation.HORIZONTAL
            setDrawInside(false)
            isWordWrapEnabled = true
        }
    }

    // ── 1. Timeline (line + gradient fill) ─────────────────────────────────────

    fun timeline(
        chart: LineChart,
        context: Context,
        snapshots: List<TimelineSnapshot>,
        timeFormat: SimpleDateFormat,
        animate: Boolean,
        scale: Float = 1f
    ): Boolean {
        if (snapshots.size < 2) return emptyChart(chart, "Data timeline belum cukup untuk membuat grafik")
        chart.baseStyle("")

        val labels = snapshots.map { timeFormat.format(Date(it.timestampMs)) }
        val reqEntries = snapshots.mapIndexed { i, s -> Entry(i.toFloat(), s.totalRequests.toFloat()) }
        val coinEntries = snapshots.mapIndexed { i, s -> Entry(i.toFloat(), s.totalCoins.toFloat()) }

        val reqSet = LineDataSet(reqEntries, "Request").apply {
            color = ReportTheme.TEAL
            lineWidth = 2.5f * scale
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.15f
            setDrawCircles(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillDrawable = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ReportTheme.alpha(ReportTheme.TEAL, 120), ReportTheme.alpha(ReportTheme.TEAL, 0))
            )
            axisDependency = YAxis.AxisDependency.LEFT
            highLightColor = ReportTheme.alpha(Color.WHITE, 140)
            setDrawHorizontalHighlightIndicator(false)
            highlightLineWidth = 1f
            enableDashedHighlightLine(8f, 6f, 0f)
        }

        val coinSet = LineDataSet(coinEntries, "Est. Coins").apply {
            color = ReportTheme.AMBER
            lineWidth = 2.2f * scale
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.15f
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.RIGHT
            highLightColor = ReportTheme.alpha(Color.WHITE, 140)
            setDrawHorizontalHighlightIndicator(false)
            highlightLineWidth = 1f
            enableDashedHighlightLine(8f, 6f, 0f)
        }

        chart.apply {
            setExtraOffsets(4f, 14f, 4f, 6f)
            setDrawGridBackground(false)
            setScaleEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled = false
            data = LineData(reqSet, coinSet)

            styleXAxis(xAxis, labels, scale, labelCount = 5)
            styleValueAxis(axisLeft, scale, ReportTheme.TEAL)
            axisRight.isEnabled = true
            styleValueAxis(axisRight, scale, ReportTheme.AMBER, grid = false)
            styleLegend(this, scale)

            marker = if (animate) PillMarker(context, labels) else null
            setDrawMarkers(animate)
            if (animate) animateX(700)
            invalidate()
        }
        return true
    }

    /** Tooltip pil putih seperti "Oct · 92" pada referensi. */
    private class PillMarker(context: Context, private val labels: List<String>) : IMarker {
        private val density = context.resources.displayMetrics.density
        private val offset = MPPointF()
        private var text = ""
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F1F4FF") }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ReportTheme.BG
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun getOffset(): MPPointF = offset
        override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF = offset

        override fun refreshContent(e: Entry, highlight: Highlight) {
            val label = labels.getOrNull(e.x.toInt()) ?: ""
            val unit = if (highlight.dataSetIndex == 1) "coin" else "req"
            text = "$label · ${e.y.toInt()} $unit"
        }

        override fun draw(canvas: Canvas, posX: Float, posY: Float) {
            val padX = 10f * density
            val h = 26f * density
            val w = tp.measureText(text) + padX * 2
            val left = (posX - w / 2f).coerceIn(2f * density, (canvas.width - w - 2f * density).coerceAtLeast(2f * density))
            val top = (posY - h - 12f * density).coerceAtLeast(2f * density)
            canvas.drawRoundRect(RectF(left, top, left + w, top + h), h / 2f, h / 2f, bg)
            val baseline = top + h / 2f - (tp.descent() + tp.ascent()) / 2f
            canvas.drawText(text, left + w / 2f, baseline, tp)
        }
    }

    // ── 2. Bar vertikal (rounded + gradient, nilai tertinggi ungu) ─────────────

    fun verticalBars(
        chart: BarChart,
        items: List<Pair<String, Float>>,
        name: String,
        noDataMsg: String,
        animate: Boolean,
        scale: Float = 1f
    ): Boolean {
        if (items.isEmpty()) return emptyChart(chart, noDataMsg)
        chart.baseStyle(noDataMsg)

        val labels = items.map { it.first }
        val entries = items.mapIndexed { i, p -> BarEntry(i.toFloat(), p.second) }
        val maxIdx = items.indices.maxByOrNull { items[it].second } ?: 0

        val gradients = items.indices.map { i ->
            if (i == maxIdx && items.size > 1) GradientColor(ReportTheme.PURPLE_DARK, ReportTheme.PURPLE)
            else GradientColor(ReportTheme.TEAL_DARK, ReportTheme.TEAL)
        }

        val set = BarDataSet(entries, name).apply {
            setGradientColors(gradients)
            setDrawValues(true)
            valueTextColor = ReportTheme.TEXT
            valueTextSize = 11f * scale
            valueTypeface = Typeface.DEFAULT_BOLD
            valueFormatter = CompactFormatter()
        }

        chart.apply {
            setExtraOffsets(4f, 14f, 4f, 6f)
            setDrawGridBackground(false)
            setScaleEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled = false
            setDrawBarShadow(false)
            data = BarData(set).apply { barWidth = 0.55f }
            renderer = RoundedBarRenderer(this, animator, viewPortHandler, 7f * resources.displayMetrics.density * scale)

            styleXAxis(xAxis, labels, scale)
            styleValueAxis(axisLeft, scale)
            axisLeft.axisMaximum = (items.maxOf { it.second } * 1.2f).coerceAtLeast(1f)
            axisRight.isEnabled = false
            legend.isEnabled = false
            if (animate) animateY(700)
            invalidate()
        }
        return true
    }

    // ── 3. Bar horizontal (pill + track, tiap bar beda warna) ─────────────────

    /** items urut dari peringkat 1 ke bawah; fungsi ini yang membalik urutannya untuk tampilan. */
    fun horizontalPills(
        chart: HorizontalBarChart,
        items: List<Pair<String, Float>>,
        name: String,
        noDataMsg: String,
        animate: Boolean,
        scale: Float = 1f
    ): Boolean {
        if (items.isEmpty()) return emptyChart(chart, noDataMsg)
        chart.baseStyle(noDataMsg)

        val display = items.reversed()
        val labels = display.map { if (it.first.length > 14) it.first.take(13) + "…" else it.first }
        val entries = display.mapIndexed { i, p -> BarEntry(i.toFloat(), p.second) }
        val n = display.size
        val colors = display.indices.map { i -> ReportTheme.PALETTE[(n - 1 - i) % ReportTheme.PALETTE.size] }
        val maxV = items.maxOf { it.second }.coerceAtLeast(1f)

        val set = BarDataSet(entries, name).apply {
            this.colors = colors
            barShadowColor = ReportTheme.TRACK
            setDrawValues(true)
            valueTextColor = ReportTheme.TEXT
            valueTextSize = 11f * scale
            valueFormatter = CompactFormatter()
        }

        chart.apply {
            setExtraOffsets(4f, 4f, 16f, 4f)
            setDrawGridBackground(false)
            setScaleEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled = false
            setDrawBarShadow(true)
            data = BarData(set).apply { barWidth = 0.5f }
            renderer = RoundedHorizontalBarRenderer(this, animator, viewPortHandler)

            styleXAxis(xAxis, labels, scale)
            xAxis.textColor = ReportTheme.TEXT
            xAxis.setLabelCount(n.coerceAtLeast(2), false)
            listOf(axisLeft, axisRight).forEach { ax ->
                ax.setDrawLabels(false)
                ax.setDrawGridLines(false)
                ax.setDrawAxisLine(false)
                ax.axisMinimum = 0f
                ax.axisMaximum = maxV * 1.25f
            }
            legend.isEnabled = false
            if (animate) animateY(700)
            invalidate()
        }
        return true
    }

    // ── 4. Donut ───────────────────────────────────────────────────────────────

    data class Slice(val label: String, val value: Float, val color: Int)

    fun donut(
        chart: PieChart,
        slices: List<Slice>,
        centerTop: String,
        centerBottom: String,
        noDataMsg: String,
        animate: Boolean,
        scale: Float = 1f
    ): Boolean {
        val valid = slices.filter { it.value > 0f }
        if (valid.isEmpty()) return emptyChart(chart, noDataMsg)
        chart.baseStyle(noDataMsg)

        val set = PieDataSet(valid.map { PieEntry(it.value, it.label) }, "").apply {
            colors = valid.map { it.color }
            sliceSpace = 3f
            setDrawValues(false)
        }

        val center = SpannableString("$centerTop\n$centerBottom").apply {
            setSpan(RelativeSizeSpan(1.9f), 0, centerTop.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ReportTheme.TEXT), 0, centerTop.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(ReportTheme.DIM),
                centerTop.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        chart.apply {
            setExtraOffsets(8f, 4f, 8f, 4f)
            setUsePercentValues(false)
            setDrawEntryLabels(false)
            isDrawHoleEnabled = true
            holeRadius = 68f
            transparentCircleRadius = 0f
            setHoleColor(Color.TRANSPARENT)
            setDrawCenterText(true)
            centerText = center
            setCenterTextColor(ReportTheme.DIM)
            setCenterTextSize(12f * scale)
            data = PieData(set)
            styleLegend(this, scale)
            legend.textColor = ReportTheme.TEXT
            if (animate) animateY(700)
            invalidate()
        }
        return true
    }

    // ── Renderer: bar vertikal dengan sudut atas membulat + gradient per-bar ───

    private class RoundedBarRenderer(
        chart: BarDataProvider,
        animator: ChartAnimator,
        viewPortHandler: ViewPortHandler,
        private val radius: Float
    ) : BarChartRenderer(chart, animator, viewPortHandler) {

        private val rect = RectF()
        private val path = Path()

        override fun drawDataSet(c: Canvas, dataSet: IBarDataSet, index: Int) {
            val trans = mChart.getTransformer(dataSet.axisDependency)
            if (mBarBuffers == null || index !in mBarBuffers.indices || mBarBuffers[index] == null) {
                initBuffers()
            }
            val buffer = mBarBuffers?.getOrNull(index) ?: return
            buffer.setPhases(mAnimator.phaseX, mAnimator.phaseY)
            buffer.setDataSet(index)
            buffer.setInverted(mChart.isInverted(dataSet.axisDependency))
            buffer.setBarWidth(mChart.barData?.barWidth ?: 0f)
            buffer.feed(dataSet)
            trans.pointValuesToPixel(buffer.buffer)

            var j = 0
            while (j < buffer.size()) {
                if (!mViewPortHandler.isInBoundsLeft(buffer.buffer[j + 2])) { j += 4; continue }
                if (!mViewPortHandler.isInBoundsRight(buffer.buffer[j])) break

                val left = buffer.buffer[j]
                val top = buffer.buffer[j + 1]
                val right = buffer.buffer[j + 2]
                val bottom = buffer.buffer[j + 3]

                val g = dataSet.getGradientColor(j / 4)
                if (g != null) {
                    mRenderPaint.shader = LinearGradient(
                        left, bottom, left, top, g.startColor, g.endColor, Shader.TileMode.CLAMP
                    )
                } else {
                    mRenderPaint.shader = null
                    mRenderPaint.color = dataSet.getColor(j / 4)
                }

                rect.set(left, top, right, bottom)
                val r = radius.coerceAtMost((right - left) / 2f)
                path.reset()
                path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                c.drawPath(path, mRenderPaint)
                j += 4
            }
            mRenderPaint.shader = null
        }
    }

    // ── Renderer: bar horizontal bentuk pill + track di belakang ───────────────

    private class RoundedHorizontalBarRenderer(
        chart: BarDataProvider,
        animator: ChartAnimator,
        viewPortHandler: ViewPortHandler
    ) : HorizontalBarChartRenderer(chart, animator, viewPortHandler) {

        private val rect = RectF()
        private val barShadowRectBuffer = RectF()

        override fun drawDataSet(c: Canvas, dataSet: IBarDataSet, index: Int) {
            val trans = mChart.getTransformer(dataSet.axisDependency)
            val phaseX = mAnimator.phaseX

            // Track (latar abu-navy di belakang tiap bar)
            if (mChart.isDrawBarShadowEnabled && mChart.barData != null) {
                mShadowPaint.color = dataSet.barShadowColor
                val half = (mChart.barData?.barWidth ?: 0f) / 2f
                val count = Math.min(Math.ceil((dataSet.entryCount * phaseX).toDouble()).toInt(), dataSet.entryCount)
                for (i in 0 until count) {
                    val x = dataSet.getEntryForIndex(i).x
                    barShadowRectBuffer.top = x + half
                    barShadowRectBuffer.bottom = x - half
                    trans.rectValueToPixel(barShadowRectBuffer)
                    if (!mViewPortHandler.isInBoundsTop(barShadowRectBuffer.bottom)) continue
                    if (!mViewPortHandler.isInBoundsBottom(barShadowRectBuffer.top)) break
                    barShadowRectBuffer.left = mViewPortHandler.contentLeft()
                    barShadowRectBuffer.right = mViewPortHandler.contentRight()
                    val h = barShadowRectBuffer.height()
                    c.drawRoundRect(barShadowRectBuffer, h / 2f, h / 2f, mShadowPaint)
                }
            }

            if (mBarBuffers == null || index !in mBarBuffers.indices || mBarBuffers[index] == null) {
                initBuffers()
            }
            val buffer = mBarBuffers?.getOrNull(index) ?: return
            buffer.setPhases(phaseX, mAnimator.phaseY)
            buffer.setDataSet(index)
            buffer.setInverted(mChart.isInverted(dataSet.axisDependency))
            buffer.setBarWidth(mChart.barData?.barWidth ?: 0f)
            buffer.feed(dataSet)
            trans.pointValuesToPixel(buffer.buffer)

            var j = 0
            while (j < buffer.size()) {
                if (!mViewPortHandler.isInBoundsTop(buffer.buffer[j + 3])) { j += 4; continue }
                if (!mViewPortHandler.isInBoundsBottom(buffer.buffer[j + 1])) break

                mRenderPaint.color = dataSet.getColor(j / 4)
                rect.set(buffer.buffer[j], buffer.buffer[j + 1], buffer.buffer[j + 2], buffer.buffer[j + 3])
                val h = rect.height()
                c.drawRoundRect(rect, h / 2f, h / 2f, mRenderPaint)
                j += 4
            }
        }
    }
}