package ame.project.kanae.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import ame.project.kanae.R
import ame.project.kanae.model.Song
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request

class OverlayManager(
    context: Context,
    private val scope: CoroutineScope,
    private val onPlayPause: () -> Unit,
    private val onSkip: () -> Unit,
    private val onClose: () -> Unit
) {
    private val context = context.applicationContext
    private val wm: WindowManager =
        this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null

    // ── View references ───────────────────────────────────────────────
    private var tvTitle: TextView?       = null
    private var tvQueue: TextView?       = null
    private var tvTime: TextView?        = null
    private var progressBar: ProgressBar? = null
    private var dotLive: View?           = null
    private var btnPlayPause: ImageButton? = null
    private var ivThumbnail: ImageView?  = null
    private var expandedSection: LinearLayout? = null

    private var isExpanded      = false
    private var currentSongId: String? = null
    private val http = OkHttpClient()

    var isShowing: Boolean = false
        private set

    /** When true the overlay is fixed (canvas locked) – not draggable/resizable */
    private var canvasLocked = false

    // ─────────────────────────────────────────────────────────────────
    fun show() {
        if (isShowing) return

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view   = LayoutInflater.from(themed).inflate(R.layout.overlay_layout, null)
        rootView   = view

        // Bind view references
        tvTitle       = view.findViewById(R.id.overlay_title)
        tvTitle?.text = "- Nothing playing -"
        tvQueue       = view.findViewById(R.id.overlay_queue_count)
        tvTime        = view.findViewById(R.id.overlay_time)
        progressBar   = view.findViewById(R.id.overlay_progress)
        dotLive       = view.findViewById(R.id.overlay_live_dot)
        btnPlayPause  = view.findViewById(R.id.overlay_btn_play_pause)
        ivThumbnail   = view.findViewById(R.id.overlay_thumbnail)
        expandedSection = view.findViewById(R.id.overlay_expanded_section)

        // Wire control buttons (clicks dispatched by OverlayGestureHelper)
        view.findViewById<ImageButton>(R.id.overlay_btn_play_pause)
            .setOnClickListener { onPlayPause() }
        view.findViewById<ImageButton>(R.id.overlay_btn_skip)
            .setOnClickListener { onSkip() }
        view.findViewById<ImageButton>(R.id.overlay_btn_close)
            .setOnClickListener { hide(); onClose() }

        // Disable Android's own view-tree clipping so rotated corners aren't cut off
        if (view is ViewGroup) {
            view.clipChildren  = false
            view.clipToPadding = false
        }

        val type = overlayWindowType()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // FLAG_LAYOUT_NO_LIMITS lets the window grow past screen edges during rotation
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = 16; it.y = 100
        }
        layoutParams = params

        // Gesture: drag + pinch-scale + rotate + tap-to-expand
        gestureHelper = OverlayGestureHelper(
            rootView    = view,
            params      = params,
            wm          = wm,
            onSingleTap = ::toggleExpand
        ).also { view.setOnTouchListener(it) }

        wm.addView(view, params)
        isShowing = true
    }

    fun hide() {
        if (!isShowing) return
        
        // Cancel thumbnail pulsing animation
        ivThumbnail?.let { iv ->
            (iv.tag as? AnimatorSet)?.cancel()
            iv.tag = null
        }

        rootView?.let { 
            it.setOnTouchListener(null)
            runCatching { wm.removeView(it) } 
        }
        
        rootView      = null
        layoutParams  = null
        gestureHelper = null
        
        tvTitle       = null
        tvQueue       = null
        tvTime        = null
        progressBar   = null
        dotLive       = null
        btnPlayPause  = null
        ivThumbnail   = null
        expandedSection = null

        isShowing     = false
        isExpanded    = false
        currentSongId = null
    }

    // ── Expand / collapse animated ────────────────────────────────────
    private fun toggleExpand() {
        val section = expandedSection ?: return
        isExpanded  = !isExpanded

        if (isExpanded) {
            section.visibility = View.VISIBLE
            section.alpha      = 0f
            section.scaleY     = 0.8f
            section.animate()
                .alpha(1f).scaleY(1f)
                .setDuration(220).start()
        } else {
            section.animate()
                .alpha(0f).scaleY(0.8f)
                .setDuration(180)
                .withEndAction { section.visibility = View.GONE }
                .start()
        }
        // Notify WM that size changed
        rootView?.post {
            layoutParams?.let { runCatching { wm.updateViewLayout(rootView, it) } }
        }
    }

    // ── Canvas locked mode ────────────────────────────────────────────
    /**
     * Move overlay to ([x], [y]) and optionally lock it so it cannot be
     * dragged, scaled, or rotated.
     */
    fun setCanvasMode(locked: Boolean, x: Int = 0, y: Int = 0) {
        canvasLocked = locked
        gestureHelper?.locked = locked

        val params = layoutParams ?: return
        val view   = rootView    ?: return

        if (locked) {
            params.x = x; params.y = y
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                           WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                           WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                           WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun applyConfig(x: Int, y: Int, scale: Float) {
        val params = layoutParams ?: return
        val view   = rootView    ?: return
        
        params.x = x
        params.y = y
        view.scaleX = scale
        view.scaleY = scale

        runCatching { wm.updateViewLayout(view, params) }
    }

    // ── Data updates ──────────────────────────────────────────────────
    fun updateSong(song: Song?, positionMs: Long, durationMs: Long) {
        if (!isShowing) return

        if (song?.id != currentSongId) {
            currentSongId = song?.id
            tvTitle?.text  = song?.title ?: "- Nothing playing -"
            tvTitle?.alpha = 0f
            tvTitle?.animate()?.alpha(1f)?.setDuration(300)?.start()
            updateThumbnail(song?.thumbnail)
        }

        val progress = if (durationMs > 0) (positionMs * 100 / durationMs).toInt() else 0
        progressBar?.progress = progress

        val posSec = (positionMs / 1000).toInt()
        val durSec = (durationMs / 1000).toInt()
        tvTime?.text = "${fmt(posSec)} / ${fmt(durSec)}"
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
        // Pulsing thumbnail animation while playing
        ivThumbnail?.let { iv ->
            (iv.tag as? AnimatorSet)?.cancel()
            if (isPlaying) {
                val sx = ObjectAnimator.ofFloat(iv, "scaleX", 1f, 1.07f).apply {
                    repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE
                }
                val sy = ObjectAnimator.ofFloat(iv, "scaleY", 1f, 1.07f).apply {
                    repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE
                }
                AnimatorSet().also { set ->
                    set.playTogether(sx, sy); set.duration = 2000; iv.tag = set; set.start()
                }
            } else {
                iv.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────
    private fun updateThumbnail(url: String?) {
        ivThumbnail ?: return
        if (url.isNullOrBlank()) {
            ivThumbnail?.setImageResource(android.R.drawable.ic_media_play); return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val req  = Request.Builder().url(url).build()
                val resp = http.newCall(req).execute()
                val bmp  = resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                withContext(Dispatchers.Main) {
                    ivThumbnail?.apply {
                        setImageBitmap(bmp)
                        alpha = 0f; scaleX = 0.85f; scaleY = 0.85f
                        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(350).start()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    ivThumbnail?.setImageResource(android.R.drawable.ic_media_play)
                }
            }
        }
    }

    private fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

    @Suppress("DEPRECATION")
    private fun overlayWindowType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
}
