package ame.project.kanae.overlay

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Button
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import kotlin.math.*

/**
 * Unified touch handler for floating overlay windows managed by [WindowManager].
 */
class OverlayGestureHelper(
    private val rootView: View,
    private val params: WindowManager.LayoutParams,
    private val wm: WindowManager,
    private val onSingleTap: (() -> Unit)? = null,
    private val onLongPress: (() -> Unit)? = null,
    var onInteraction: (() -> Unit)? = null
) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!hasMoved && !isMultiTouch) {
            isLongPressTriggered = true
            onLongPress?.invoke()
        }
    }

    // ── drag state ────────────────────────────────────────────────────
    private var initX    = 0;   private var initY    = 0
    private var rawDownX = 0f;  private var rawDownY = 0f

    // ── multi-touch state ─────────────────────────────────────────────
    private var isMultiTouch   = false
    private var hasMoved       = false
    private var isLongPressTriggered = false
    private var lastPinchDist  = 0f
    private var lastPinchAngle = 0f
    private var downTime       = 0L

    // ── transform state (persisted across gestures) ───────────────────
    var currentScale    = 1f
    var currentRotation = 0f

    private var origW = 0
    private var origH = 0

    var locked = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (locked) return false
        onInteraction?.invoke()

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initX    = params.x;   initY    = params.y
                rawDownX = event.rawX; rawDownY = event.rawY
                isMultiTouch = false;  hasMoved = false
                isLongPressTriggered = false
                downTime = System.currentTimeMillis()
                captureOrigSize()
                
                handler.postDelayed(longPressRunnable, 600)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isMultiTouch   = true
                handler.removeCallbacks(longPressRunnable)
                lastPinchDist  = pinchDist(event)
                lastPinchAngle = pinchAngle(event)
                captureOrigSize()
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - rawDownX
                val dy = event.rawY - rawDownY
                
                if (abs(dx) > 10f || abs(dy) > 10f) {
                    hasMoved = true
                    handler.removeCallbacks(longPressRunnable)
                }

                if (!isMultiTouch && !isLongPressTriggered) {
                    params.x = initX + dx.toInt()
                    params.y = initY + dy.toInt()
                    tryUpdate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                onInteraction?.invoke()
                
                val elapsed = System.currentTimeMillis() - downTime
                if (!hasMoved && !isLongPressTriggered && event.actionMasked == MotionEvent.ACTION_UP) {
                    // Try to click buttons first
                    val btn = findButtonAt(rootView as? ViewGroup, x, y)
                    if (btn != null) {
                        btn.performClick()
                    } else if (elapsed < 400) {
                        // It was a short tap and no button was hit.
                        // Pass the touch to the view BELOW (the WebView).
                        // Since we consumed ACTION_DOWN, we must manually dispatch.
                        dispatchTapToUnderlying(v, event)
                        onSingleTap?.invoke()
                    }
                }
            }
        }
        return true
    }

    /**
     * Pass the click event to the sibling view behind this gesture layer.
     * In our layout, the WebView is the first child, and this layer is the second.
     */
    private fun dispatchTapToUnderlying(gestureLayer: View, originalUp: MotionEvent) {
        val parent = gestureLayer.parent as? ViewGroup ?: return
        // The view behind us (usually at index 0)
        val target = parent.getChildAt(0) ?: return
        if (target == gestureLayer) return

        // Create a sequence of DOWN and UP at the same coordinate
        val down = MotionEvent.obtain(
            originalUp.downTime, originalUp.eventTime,
            MotionEvent.ACTION_DOWN, originalUp.x, originalUp.y, 0
        )
        val up = MotionEvent.obtain(originalUp)

        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)

        down.recycle()
        up.recycle()
    }

    private fun updateWindowBounds() {
        if (origW <= 0 || origH <= 0) return
        val scaledW = origW * currentScale
        val scaledH = origH * currentScale
        val rad  = Math.toRadians(currentRotation.toDouble())
        val cosA = abs(cos(rad)).toFloat()
        val sinA = abs(sin(rad)).toFloat()
        val margin = 12
        val newW = (scaledW * cosA + scaledH * sinA).toInt() + margin
        val newH = (scaledW * sinA + scaledH * cosA).toInt() + margin
        val cx = params.x + params.width  / 2
        val cy = params.y + params.height / 2
        params.width  = newW
        params.height = newH
        params.x = (cx - newW / 2).coerceAtLeast(0)
        params.y = (cy - newH / 2).coerceAtLeast(0)
        tryUpdate()
    }

    private fun captureOrigSize() {
        if (origW > 0 || rootView.width == 0) return
        origW = (rootView.width  / currentScale).toInt().coerceAtLeast(1)
        origH = (rootView.height / currentScale).toInt().coerceAtLeast(1)
    }

    fun updateBaseSize(wPx: Int, hPx: Int) {
        if (abs(origW - wPx) < 5 && abs(origH - hPx) < 5) return
        origW = wPx
        origH = hPx
        updateWindowBoundsNoCenter()
    }

    private fun updateWindowBoundsNoCenter() {
        if (origW <= 0 || origH <= 0) return
        val scaledW = origW * currentScale
        val scaledH = origH * currentScale
        val rad  = Math.toRadians(currentRotation.toDouble())
        val cosA = abs(cos(rad)).toFloat()
        val sinA = abs(sin(rad)).toFloat()
        val margin = 12
        val newW = (scaledW * cosA + scaledH * sinA).toInt() + margin
        val newH = (scaledW * sinA + scaledH * cosA).toInt() + margin
        params.width  = newW
        params.height = newH
        tryUpdate()
    }

    private fun tryUpdate() {
        try { wm.updateViewLayout(rootView, params) } catch (_: Exception) { }
    }

    private fun pinchDist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun pinchAngle(e: MotionEvent): Float =
        Math.toDegrees(atan2((e.getY(0) - e.getY(1)).toDouble(), (e.getX(0) - e.getX(1)).toDouble())).toFloat()

    private fun findButtonAt(group: ViewGroup?, x: Float, y: Float): View? {
        group ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val cx = x - child.left;  val cy = y - child.top
            if (cx < 0 || cy < 0 || cx > child.width || cy > child.height) continue
            if (child is ImageButton || child is Button) return child
            if (child is ViewGroup) {
                val found = findButtonAt(child, cx, cy)
                if (found != null) return found
            }
        }
        return null
    }
}
