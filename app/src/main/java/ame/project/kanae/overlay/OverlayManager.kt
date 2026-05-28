package ame.project.kanae.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import ame.project.kanae.R
import ame.project.kanae.model.Song

/**
 * OverlayManager
 *
 * Creates a floating, drag-and-drop overlay window using WindowManager.
 * Requires SYSTEM_ALERT_WINDOW permission.
 *
 * The overlay shows:
 *  • Current song title + progress bar
 *  • Queue count badge
 *  • TikTok Live connection indicator (green dot)
 *  • Play/Pause, Skip, and Close buttons
 *
 * Usage:
 *   overlayManager.show()
 *   overlayManager.updateSong(song, posMs, durMs)
 *   overlayManager.hide()
 */
class OverlayManager(
    private val context: Context,
    private val onPlayPause: () -> Unit,
    private val onSkip: () -> Unit,
    private val onClose: () -> Unit
) {
    // ── WindowManager ─────────────────────────────────────────────────────────

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // ── Views (cached after inflation) ───────────────────────────────────────

    private var tvTitle: TextView? = null
    private var tvQueue: TextView? = null
    private var tvTime: TextView? = null
    private var progressBar: ProgressBar? = null
    private var dotLive: View? = null
    private var btnPlayPause: ImageButton? = null

    var isShowing: Boolean = false
        private set

    // ── Show / Hide ───────────────────────────────────────────────────────────

    fun show() {
        if (isShowing) return

        // Wrap the context with the app theme so that theme attributes like
        // ?attr/selectableItemBackgroundBorderless can be resolved during inflation.
        val themedContext = ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.overlay_layout, null)
        rootView = view

        // Cache views
        tvTitle     = view.findViewById(R.id.overlay_title)
        tvQueue     = view.findViewById(R.id.overlay_queue_count)
        tvTime      = view.findViewById(R.id.overlay_time)
        progressBar = view.findViewById(R.id.overlay_progress)
        dotLive     = view.findViewById(R.id.overlay_live_dot)
        btnPlayPause = view.findViewById(R.id.overlay_btn_play_pause)

        // Button listeners
        btnPlayPause?.setOnClickListener { onPlayPause() }
        view.findViewById<ImageButton>(R.id.overlay_btn_skip)?.setOnClickListener { onSkip() }
        view.findViewById<ImageButton>(R.id.overlay_btn_close)?.setOnClickListener {
            hide()
            onClose()
        }

        // WindowManager layout params
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = 16
            it.y = 100
        }
        layoutParams = params

        // Drag listener – move overlay with finger
        val dragListener = OverlayDragListener(view, params, wm)
        view.setOnTouchListener(dragListener)

        wm.addView(view, params)
        isShowing = true
    }

    fun hide() {
        if (!isShowing) return
        rootView?.let { wm.removeView(it) }
        rootView = null
        isShowing = false
    }

    // ── Update data ───────────────────────────────────────────────────────────

    fun updateSong(song: Song?, positionMs: Long, durationMs: Long) {
        if (!isShowing) return
        tvTitle?.text = song?.title ?: "– Nothing playing –"

        val progress = if (durationMs > 0) (positionMs * 100 / durationMs).toInt() else 0
        progressBar?.progress = progress

        val posSec  = (positionMs / 1000).toInt()
        val durSec  = (durationMs / 1000).toInt()
        tvTime?.text = "${fmt(posSec)} / ${fmt(durSec)}"

        btnPlayPause?.setImageResource(
            if (song != null) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    fun updateQueueCount(count: Int) {
        tvQueue?.text = "Q: $count"
    }

    fun setLiveStatus(connected: Boolean) {
        dotLive?.setBackgroundResource(
            if (connected) R.drawable.dot_green else R.drawable.dot_red
        )
    }

    fun setPlayingState(isPlaying: Boolean) {
        btnPlayPause?.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
}

// ── Drag Touch Listener ───────────────────────────────────────────────────────

private class OverlayDragListener(
    private val view: View,
    private val params: WindowManager.LayoutParams,
    private val wm: WindowManager
) : View.OnTouchListener {

    private var initX = 0
    private var initY = 0
    private var touchX = 0f
    private var touchY = 0f

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initX = params.x
                initY = params.y
                touchX = event.rawX
                touchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = initX + (event.rawX - touchX).toInt()
                params.y = initY + (event.rawY - touchY).toInt()
                wm.updateViewLayout(view, params)
                return true
            }
        }
        return false
    }
}
