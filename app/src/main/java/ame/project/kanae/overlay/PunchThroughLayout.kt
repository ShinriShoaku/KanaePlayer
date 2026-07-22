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

package ame.project.kanae.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

class PunchThroughLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Mode CLEAR akan membuat area yang digambar menjadi transparan bolong (tembus ke background)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    
    private var touchX = -1f
    private var touchY = -1f
    private var eraseRadius = 0f
    private val maxRadius = 100f // Ukuran lingkaran lubang transparan (dalam pixel)

    var targetWidth = 0
    var targetHeight = 0
    var currentScale = 1.0f
    
    var punchEnabled = false
        set(value) {
            field = value
            if (!value) {
                touchX = -1f
                touchY = -1f
                eraseRadius = 0f
            }
            invalidate()
        }

    init {
        // WAJIB: Android secara default tidak me-render ulang layout biasa. 
        // Kita harus set ini ke false agar fungsi onDraw() dipanggil.
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)

        // Gunakan targetWidth/Height sebagai "parent constraint" jika ada.
        // Jika tidak, gunakan ukuran yang diberikan oleh WindowManager.
        val parentWidth = if (targetWidth > 0) targetWidth else measuredWidth
        val parentHeight = if (targetHeight > 0) targetHeight else measuredHeight

        val childWidthSpec = MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(parentHeight, MeasureSpec.EXACTLY)
        
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != GONE) {
                // Gunakan helper FrameLayout untuk menghormati LayoutParams (misal 28dp)
                // sambil tetap menggunakan parentWidth/Height sebagai batas atas.
                measureChildWithMargins(child, childWidthSpec, 0, childHeightSpec, 0)
            }
        }

        // Ukuran layout ini sendiri HARUS mengikuti resolusi target (unscaled)
        // agar background color dan area gambar mencakup seluruh isi WebView.
        setMeasuredDimension(parentWidth, parentHeight)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (punchEnabled) {
            updateTouch(ev)
            // Return false to signal that this window is not consuming the touch,
            // which helps in passing the touch to windows below.
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (punchEnabled) {
            updateTouch(event)
            return false
        }
        return super.onTouchEvent(event)
    }

    private fun updateTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Sesuaikan koordinat sentuh dengan skala layout.
                // Jika layout discale 0.5x, maka koordinat internal 200px
                // sebenarnya adalah 400px dalam sistem koordinat WebView.
                touchX = event.x / currentScale
                touchY = event.y / currentScale
                eraseRadius = maxRadius // Tetap gunakan radius asli agar tidak ikut mengecil
                invalidate() // Paksa gambar ulang
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Saat jari dilepas, hilangkan lubangnya
                touchX = -1f
                touchY = -1f
                eraseRadius = 0f
                invalidate()
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (punchEnabled && touchX != -1f && touchY != -1f) {
            // Buat offscreen buffer untuk menerapkan efek Xfermode CLEAR secara sempurna
            val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            
            // Gambar isi layout asli (Textview, Imageview, dll)
            super.draw(canvas)
            
            // Gambar lubang transparan tepat di posisi koordinat jari
            canvas.drawCircle(touchX, touchY, eraseRadius, paint)
            
            canvas.restoreToCount(count)
        } else {
            super.draw(canvas)
        }
    }
}
