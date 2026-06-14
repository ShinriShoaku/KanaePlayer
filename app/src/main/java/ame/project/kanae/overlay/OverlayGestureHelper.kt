package ame.project.kanae.overlay

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Button
import android.view.WindowManager
import kotlin.math.*

/**
 * Unified touch handler for floating overlay windows managed by [WindowManager].
 *
 * Gestures on the root view:
 *  ① Single-finger drag   → moves the window (params.x / params.y)
 *  ② Two-finger pinch     → scales content (scaleX / scaleY) range 0.35×–4×
 *  ③ Two-finger rotate    → rotates content freely
 *  ④ Short tap            → dispatches click to an [ImageButton]/[Button] under
 *                           the finger, or calls [onSingleTap] if none found
 *
 * KEY FIX – rotation / scale clipping:
 *   WindowManager clips drawing to the window rectangle.  When the view is
 *   rotated or scaled, its visual corners extend beyond that rectangle and get
 *   clipped.  This class solves it by recomputing the **axis-aligned bounding
 *   box** of the transformed content after every pinch/rotate gesture and
 *   updating params.width / params.height + re-centering params.x / params.y
 *   so the visual center stays fixed on screen.
 *
 * Usage:
 *   val helper = OverlayGestureHelper(rootView, params, wm) { toggleExpand() }
 *   rootView.setOnTouchListener(helper)
 */
class OverlayGestureHelper(
    private val rootView: View,
    private val params: WindowManager.LayoutParams,
    private val wm: WindowManager,
    private val onSingleTap: (() -> Unit)? = null
) : View.OnTouchListener {

    // ── drag state ────────────────────────────────────────────────────
    private var initX    = 0;   private var initY    = 0
    private var rawDownX = 0f;  private var rawDownY = 0f

    // ── multi-touch state ─────────────────────────────────────────────
    private var isMultiTouch   = false
    private var hasMoved       = false
    private var lastPinchDist  = 0f
    private var lastPinchAngle = 0f
    private var downTime       = 0L

    // ── transform state (persisted across gestures) ───────────────────
    var currentScale    = 1f
    var currentRotation = 0f

    /**
     * Original (unscaled, unrotated) content size, captured on first gesture.
     * Once set it never changes — we always compute bounds from these values.
     */
    private var origW = 0
    private var origH = 0

    /** When true all touch input is ignored (canvas locked mode). */
    var locked = false

    // ─────────────────────────────────────────────────────────────────
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (locked) return false

        when (event.actionMasked) {

            // ── finger down ───────────────────────────────────────────
            MotionEvent.ACTION_DOWN -> {
                initX    = params.x;   initY    = params.y
                rawDownX = event.rawX; rawDownY = event.rawY
                isMultiTouch = false;  hasMoved = false
                downTime = System.currentTimeMillis()
                // Capture original size lazily (view must be laid-out first)
                captureOrigSize()
                return true
            }

            // ── second finger touches ─────────────────────────────────
            MotionEvent.ACTION_POINTER_DOWN -> {
                isMultiTouch   = true
                lastPinchDist  = pinchDist(event)
                lastPinchAngle = pinchAngle(event)
                captureOrigSize()
            }

            // ── movement ──────────────────────────────────────────────
            MotionEvent.ACTION_MOVE -> {
                if (!isMultiTouch) {
                    // ── single-finger drag ────────────────────────────
                    val dx = event.rawX - rawDownX
                    val dy = event.rawY - rawDownY
                    if (abs(dx) > 5f || abs(dy) > 5f) hasMoved = true
                    params.x = initX + dx.toInt()
                    params.y = initY + dy.toInt()
                    tryUpdate()
                }
            }

            // ── second finger lifted ──────────────────────────────────
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    isMultiTouch = false
                    // Re-anchor single-touch drag so view doesn't jump
                    val idx   = if (event.actionIndex == 0) 1 else 0
                    rawDownX  = event.getX(idx) + rootView.left
                    rawDownY  = event.getY(idx) + rootView.top
                    initX     = params.x
                    initY     = params.y
                }
            }

            // ── finger up / cancelled ─────────────────────────────────
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val elapsed = System.currentTimeMillis() - downTime
                if (!hasMoved && elapsed < 350L &&
                    event.actionMasked == MotionEvent.ACTION_UP) {
                    // Short tap → try to click a button, else call onSingleTap
                    val btn = findButtonAt(rootView as? ViewGroup, event.x, event.y)
                    if (btn != null) btn.performClick()
                    else onSingleTap?.invoke()
                }
            }
        }
        return true
    }

    // ── Bounding-box update ───────────────────────────────────────────

    /**
     * Computes the axis-aligned bounding box of the content rectangle after
     * applying [currentScale] and [currentRotation], then updates
     * [params].width / height and re-centers [params].x / y so the visual
     * center of the overlay stays at the same screen position.
     *
     *  For a rectangle W×H rotated by θ and scaled by S:
     *    scaledW = W·S,  scaledH = H·S
     *    bboxW   = scaledW·|cos θ| + scaledH·|sin θ|
     *    bboxH   = scaledW·|sin θ| + scaledH·|cos θ|
     */
    private fun updateWindowBounds() {
        if (origW <= 0 || origH <= 0) return

        val scaledW = origW * currentScale
        val scaledH = origH * currentScale

        val rad  = Math.toRadians(currentRotation.toDouble())
        val cosA = abs(cos(rad)).toFloat()
        val sinA = abs(sin(rad)).toFloat()

        // Add a small margin so the rotated corners are never exactly on the edge
        val margin = 12
        val newW = (scaledW * cosA + scaledH * sinA).toInt() + margin
        val newH = (scaledW * sinA + scaledH * cosA).toInt() + margin

        // Current window center (before resize)
        val cx = params.x + params.width  / 2
        val cy = params.y + params.height / 2

        params.width  = newW
        params.height = newH

        // Re-center: move top-left so center stays at (cx, cy)
        params.x = (cx - newW / 2).coerceAtLeast(0)
        params.y = (cy - newH / 2).coerceAtLeast(0)

        tryUpdate()
    }

    /** Capture original unscaled dimensions from the laid-out view (once). */
    private fun captureOrigSize() {
        if (origW > 0 || rootView.width == 0) return
        origW = (rootView.width  / currentScale).toInt().coerceAtLeast(1)
        origH = (rootView.height / currentScale).toInt().coerceAtLeast(1)
    }

    /**
     * Updates the base dimensions when content size changes dynamically.
     * This ensures scaling/rotation remains accurate while keeping the
     * user's current zoom level (currentScale).
     */
    fun updateBaseSize(wPx: Int, hPx: Int) {
        // If the change is negligible, ignore it to prevent flickering
        if (abs(origW - wPx) < 5 && abs(origH - hPx) < 5) return

        origW = wPx
        origH = hPx
        
        // Refresh the window bounds to accommodate new content size 
        // while maintaining the user's rotation and scale.
        updateWindowBounds()
    }

    private fun tryUpdate() {
        try { wm.updateViewLayout(rootView, params) } catch (_: Exception) { }
    }

    // ── Math helpers ──────────────────────────────────────────────────

    private fun pinchDist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun pinchAngle(e: MotionEvent): Float =
        Math.toDegrees(
            atan2(
                (e.getY(0) - e.getY(1)).toDouble(),
                (e.getX(0) - e.getX(1)).toDouble()
            )
        ).toFloat()

    /**
     * Recursively searches [group] for an [ImageButton] or [Button] whose
     * layout bounds contain ([x], [y]) in the group's coordinate space.
     */
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
