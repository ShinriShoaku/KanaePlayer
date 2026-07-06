package ame.project.kanae.overlay

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
    private val maxRadius = 150f // Ukuran lingkaran lubang transparan (dalam pixel)
    
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
                touchX = event.x
                touchY = event.y
                eraseRadius = maxRadius
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
