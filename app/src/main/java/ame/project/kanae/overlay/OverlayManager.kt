package ame.project.kanae.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.*
import ame.project.kanae.R
import ame.project.kanae.model.Song
import ame.project.kanae.model.CustomTheme
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
    private var punchLayout: PunchThroughLayout? = null
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
    private var expandedSection: View? = null

    private var isExpanded      = false
    private var currentSongId: String? = null
    private val http = OkHttpClient()

    private var currentLayoutId: Int = R.layout.overlay_layout
    private var currentTheme: CustomTheme = CustomTheme()
    private var lastSong: Song? = null
    private var lastPosition: Long = 0
    private var lastDuration: Long = 0
    private var lastPlayingState: Boolean = false

    var isShowing: Boolean = false
        private set

    private var visualPunchEnabled = false

    fun setVisualPunchEnabled(enabled: Boolean) {
        this.visualPunchEnabled = enabled
        punchLayout?.punchEnabled = enabled
        rootView?.setOnTouchListener(if (enabled) null else gestureHelper)
        rootView?.isClickable = !enabled
        rootView?.isFocusable = !enabled
    }

    /** When true the overlay is fixed (canvas locked) – not draggable/resizable */
    private var canvasLocked = false

    fun updateStyle(layoutId: Int) {
        if (this.currentLayoutId == layoutId) return
        this.currentLayoutId = layoutId
        if (isShowing) {
            val lastX = layoutParams?.x ?: 16
            val lastY = layoutParams?.y ?: 100
            val lastScale = gestureHelper?.currentScale ?: 1f
            val wasExpanded = isExpanded
            hide()
            show(lastX, lastY)
            
            // Post to ensure view is inflated and measured correctly
            rootView?.post {
                applyConfig(lastX, lastY, lastScale)
                
                // Restore state
                if (wasExpanded) {
                    isExpanded = true
                    expandedSection?.visibility = View.VISIBLE
                    expandedSection?.alpha = 1f
                    expandedSection?.scaleY = 1f
                    
                    // Re-apply config after expanding to update window size
                    rootView?.post {
                        applyConfig(lastX, lastY, lastScale)
                    }
                }
                updateSong(lastSong, lastPosition, lastDuration)
                setPlayingState(lastPlayingState)
            }
        }
    }

    fun applyTheme(theme: CustomTheme) {
        this.currentTheme = theme
        if (isShowing) {
            applyThemeToView(rootView)
        }
    }

    private fun applyThemeToView(view: View?) {
        val v = view ?: return
        val theme = currentTheme
        val bgAlpha = theme.alpha
        
        theme.bgPrimary?.let { color ->
            val colorWithAlpha = Color.argb(bgAlpha, Color.red(color), Color.green(color), Color.blue(color))
            v.background?.let { bg ->
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(bg.mutate())
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, colorWithAlpha)
                v.background = wrapped
            } ?: run {
                v.setBackgroundColor(colorWithAlpha)
            }
        } ?: run {
            v.background?.mutate()?.alpha = bgAlpha
        }
        
        theme.textPrimary?.let { color ->
            tvTitle?.setTextColor(color)
            tvQueue?.setTextColor(color)
            tvTime?.setTextColor(color)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    fun show(x: Int = 16, y: Int = 100) {
        if (isShowing) return

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view   = LayoutInflater.from(themed).inflate(currentLayoutId, null)
        rootView   = view

        val punch = PunchThroughLayout(themed).apply {
            punchEnabled = visualPunchEnabled
            // Use FrameLayout.LayoutParams for the inner view to avoid ClassCastException
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(view, lp)
        }
        punchLayout = punch

        // Bind view references
        tvTitle       = view.findViewById(R.id.overlay_title)
        tvQueue       = view.findViewById(R.id.overlay_queue_count)
        tvTime        = view.findViewById(R.id.overlay_time)
        progressBar   = view.findViewById(R.id.overlay_progress)
        dotLive       = view.findViewById(R.id.overlay_live_dot)
        btnPlayPause  = view.findViewById(R.id.overlay_btn_play_pause)
        ivThumbnail   = view.findViewById(R.id.overlay_thumbnail)
        expandedSection = view.findViewById<View>(R.id.overlay_expanded_section)

        applyThemeToView(view)

        tvTitle?.text = lastSong?.title ?: "- Nothing playing -"

        // Wire control buttons (clicks dispatched by OverlayGestureHelper)
        view.findViewById<ImageButton>(R.id.overlay_btn_play_pause)
            ?.setOnClickListener { onPlayPause() }
        view.findViewById<ImageButton>(R.id.overlay_btn_skip)
            ?.setOnClickListener { onSkip() }
        view.findViewById<ImageButton>(R.id.overlay_btn_close)
            ?.setOnClickListener { hide(); onClose() }

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
            it.x = x; it.y = y
        }
        layoutParams = params

        // Gesture: drag + pinch-scale + rotate + tap-to-expand
        gestureHelper = OverlayGestureHelper(
            rootView    = punch, // Use punch as the window root
            params      = params,
            wm          = wm,
            onSingleTap = ::toggleExpand
        ).also { 
            if (visualPunchEnabled) view.setOnTouchListener(null)
            else view.setOnTouchListener(it)
        }

        wm.addView(punch, params)
        isShowing = true
    }

    fun hide() {
        if (!isShowing) return
        
        // Cancel thumbnail pulsing animation
        ivThumbnail?.let { iv ->
            (iv.tag as? AnimatorSet)?.cancel()
            iv.tag = null
        }

        punchLayout?.let { 
            runCatching { wm.removeView(it) } 
        }
        rootView?.let { 
            it.setOnTouchListener(null)
        }
        
        rootView      = null
        punchLayout   = null
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
        punchLayout?.post {
            layoutParams?.let { runCatching { wm.updateViewLayout(punchLayout, it) } }
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
        val view   = punchLayout ?: return

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

    fun applyConfig(x: Int, y: Int, scale: Float, width: Int = 0, height: Int = 0) {
        val params = layoutParams ?: return
        val view   = punchLayout ?: return
        val content = rootView ?: return
        
        params.x = x
        params.y = y
        
        // Pivot di pojok kiri atas
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale

        // Hitung ukuran dasar (1.0x)
        val density = context.resources.displayMetrics.density
        
        // If specific width/height in DP are provided, use them as base
        val baseW: Int
        val baseH: Int
        
        if (width > 0 || height > 0) {
            baseW = if (width > 0) (width * density).toInt() else content.measuredWidth
            baseH = if (height > 0) (height * density).toInt() else content.measuredHeight
        } else {
            // Measure to get WRAP_CONTENT size
            content.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            baseW = content.measuredWidth
            baseH = content.measuredHeight
        }

        // Update ukuran jendela agar tidak terpotong
        params.width  = (baseW * scale).toInt().coerceAtLeast(1)
        params.height = (baseH * scale).toInt().coerceAtLeast(1)

        gestureHelper?.let {
            it.currentScale = scale
            it.updateBaseSize(baseW, baseH)
        }

        runCatching { wm.updateViewLayout(view, params) }
    }

    // ── Data updates ──────────────────────────────────────────────────
    fun updateSong(song: Song?, positionMs: Long, durationMs: Long) {
        lastSong = song
        lastPosition = positionMs
        lastDuration = durationMs

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
        lastPlayingState = isPlaying
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
