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

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Equalizer mini (4 batang) untuk pojok tile Now Playing.
 * Bergerak saat [setPlaying] true, diam (batang pendek) saat false.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val barCount = 4
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var playing = false

    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        if (value) startAnim() else stopAnim()
    }

    private fun startAnim() {
        if (animator?.isRunning == true || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) startAnim()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w * 0.12f
        val barW = (w - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val level = if (animator != null) 0.3f + 0.7f * abs(sin(phase + i * 1.3f)) else 0.25f
            val left = i * (barW + gap)
            canvas.drawRect(left, h - h * level, left + barW, h, paint)
        }
    }
}

object Metro {

    /**
     * Efek "tilt" khas Windows Phone: tile miring ke arah titik sentuh lalu kembali saat dilepas.
     * Listener mengembalikan false sehingga onClick / onLongClick tetap berjalan normal.
     * Kalau arah miringnya terasa terbalik di perangkatmu, cukup balik tanda di rotationX/rotationY.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun tilt(target: View) {
        target.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val w = v.width.toFloat()
                    val h = v.height.toFloat()
                    if (w > 0f && h > 0f) {
                        val density = v.resources.displayMetrics.density
                        v.cameraDistance = density * 8000f
                        val nx = (e.x / w - 0.5f) * 2f
                        val ny = (e.y / h - 0.5f) * 2f
                        // tile besar (mis. Now Playing yang sedang expand) miring lebih halus
                        val maxDeg = if (h > 420f * density) 2f else 8f
                        v.animate()
                            .rotationY(-nx * maxDeg)
                            .rotationX(ny * maxDeg)
                            .scaleX(0.97f).scaleY(0.97f)
                            .setDuration(90)
                            .start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .rotationX(0f).rotationY(0f)
                        .scaleX(1f).scaleY(1f)
                        .setDuration(160)
                        .start()
                }
            }
            false
        }
    }

    /**
     * Ambil warna aksen dari cover: pilih piksel paling "hidup" (saturasi tinggi),
     * lalu atur kecerahannya supaya teks putih tetap terbaca. Cover abu-abu -> [fallback].
     */
    fun accentFromBitmap(src: Bitmap, fallback: Int): Int {
        return try {
            val small = Bitmap.createScaledBitmap(src, 24, 24, true)
            val hsv = FloatArray(3)
            var bestScore = 0f
            val best = FloatArray(3)
            for (x in 0 until small.width) {
                for (y in 0 until small.height) {
                    Color.colorToHSV(small.getPixel(x, y), hsv)
                    val score = hsv[1] * (0.4f + hsv[2])
                    if (score > bestScore) {
                        bestScore = score
                        hsv.copyInto(best)
                    }
                }
            }
            if (small !== src) small.recycle()
            if (bestScore < 0.25f) return fallback
            best[1] = max(best[1], 0.55f)
            best[2] = best[2].coerceIn(0.45f, 0.80f)
            Color.HSVToColor(best)
        } catch (_: Exception) {
            fallback
        }
    }
}
