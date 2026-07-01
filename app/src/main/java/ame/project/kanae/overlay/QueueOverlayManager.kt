package ame.project.kanae.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ame.project.kanae.R
import ame.project.kanae.model.Song
import ame.project.kanae.model.CustomTheme

/**
 * Floating queue overlay.
 *
 * - Layout is wrap_content → height grows automatically as songs are added.
 * - Auto-shows when a new song is added via chat (call [autoShowIfNeeded]).
 * - Auto-hides empty state when queue becomes non-empty.
 * - Supports drag, pinch-to-scale, and two-finger rotate via [OverlayGestureHelper].
 * - Displays at most [MAX_VISIBLE_ITEMS] items to keep the overlay manageable.
 */
class QueueOverlayManager(
    context: Context,
    private val onPlay: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onClose: () -> Unit
) {
    private val context = context.applicationContext
    companion object {
        private const val MAX_VISIBLE_ITEMS = 8
    }

    private val wm = this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View?              = null
    private var punchLayout: PunchThroughLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null
    private var adapter: QueueAdapter?       = null
    private var tvEmpty: TextView?           = null
    private var tvBadge: TextView?           = null
    private var rvQueue: RecyclerView?       = null

    private var autoHideEnabled = false
    private var displayDurationMs = 10000L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    private var currentLayoutId: Int = R.layout.overlay_queue_layout
    private var currentItemLayoutId: Int = R.layout.item_queue
    private var currentTheme: CustomTheme = CustomTheme()
    private var lastQueue: List<Song> = emptyList()

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

    private var canvasLocked = false

    fun updateStyle(containerLayoutId: Int, itemLayoutId: Int) {
        var changed = false
        if (this.currentLayoutId != containerLayoutId) {
            this.currentLayoutId = containerLayoutId
            changed = true
        }
        if (this.currentItemLayoutId != itemLayoutId) {
            this.currentItemLayoutId = itemLayoutId
            adapter?.updateItemLayout(itemLayoutId)
        }

        if (changed && isShowing) {
            val lastX = layoutParams?.x ?: 16
            val lastY = layoutParams?.y ?: 420
            val lastScale = gestureHelper?.currentScale ?: 1f
            hide()
            show(lastQueue)
            
            rootView?.post {
                applyConfig(lastX, lastY, lastScale)
            }
        }
    }

    fun applyTheme(theme: CustomTheme) {
        this.currentTheme = theme
        if (isShowing) {
            applyThemeToView(rootView)
            adapter?.notifyDataSetChanged()
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
            tvBadge?.setTextColor(color)
            tvEmpty?.setTextColor(color)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    fun show(queue: List<Song> = emptyList()) {
        if (isShowing) {
            updateQueue(queue)
            return
        }

        val themed = ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view   = LayoutInflater.from(themed).inflate(currentLayoutId, null)
        rootView   = view

        val punch = PunchThroughLayout(themed).apply {
            punchEnabled = visualPunchEnabled
            // Use FrameLayout.LayoutParams for the inner view to avoid ClassCastException
            val lp = FrameLayout.LayoutParams(
                300.dp, 
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(view, lp)
        }
        punchLayout = punch

        tvEmpty = view.findViewById(R.id.overlay_queue_empty)
        tvBadge = view.findViewById(R.id.overlay_queue_count_badge)
        rvQueue = view.findViewById(R.id.overlay_rv_queue)

        rvQueue?.layoutManager = LinearLayoutManager(themed)
        adapter = QueueAdapter(themed).also {
            rvQueue?.adapter = it
        }

        applyThemeToView(view)

        view.findViewById<ImageButton>(R.id.overlay_queue_close)
            ?.setOnClickListener { hide(); onClose() }

        // Disable view-tree clipping so rotated corners are not cut off
        if (view is ViewGroup) {
            view.clipChildren  = false
            view.clipToPadding = false
        }

        val type = overlayWindowType()
        val params = WindowManager.LayoutParams(
            300.dp, WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // FLAG_LAYOUT_NO_LIMITS allows window to extend beyond screen edges during rotation
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16; y = 420
        }
        layoutParams = params

        gestureHelper = OverlayGestureHelper(
            rootView    = punch, // Use punch as the window root
            params      = params,
            wm          = wm,
            onSingleTap = null
        ).also { 
            if (visualPunchEnabled) view.setOnTouchListener(null)
            else view.setOnTouchListener(it)
        }

        wm.addView(punch, params)
        isShowing = true

        updateQueue(queue)
        
        if (autoHideEnabled) {
            startHideTimer()
        }
    }

    private fun startHideTimer() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, displayDurationMs)
    }

    fun hide() {
        if (!isShowing) return
        handler.removeCallbacks(hideRunnable)
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
        tvEmpty       = null
        tvBadge       = null
        rvQueue       = null
        adapter       = null
        isShowing     = false
    }

    /**
     * Update the displayed queue.
     * Toggles empty state vs. RecyclerView visibility automatically.
     */
    fun updateQueue(queue: List<Song>) {
        lastQueue = queue
        val visible = queue.take(MAX_VISIBLE_ITEMS)
        adapter?.submitList(visible)
        tvBadge?.text = queue.size.toString()

        val hasItems = visible.isNotEmpty()
        tvEmpty?.visibility = if (hasItems) View.GONE  else View.VISIBLE
        rvQueue?.visibility = if (hasItems) View.VISIBLE else View.GONE

        // Notify WindowManager that height may have changed
        punchLayout?.post {
            layoutParams?.let { runCatching { wm.updateViewLayout(punchLayout, it) } }
        }
    }

    /**
     * Show the overlay automatically when a new song enters the queue from chat.
     * Call this after adding to queue; only shows if not already visible.
     */
    fun autoShowIfNeeded(queue: List<Song>) {
        if (queue.isEmpty()) return
        if (isShowing) {
            updateQueue(queue)
            if (autoHideEnabled) startHideTimer()
        } else show(queue)
    }

    fun setAutoHide(enabled: Boolean, durationSeconds: Int) {
        this.autoHideEnabled = enabled
        this.displayDurationMs = durationSeconds * 1000L
        if (isShowing) {
            if (enabled) startHideTimer()
            else handler.removeCallbacks(hideRunnable)
        }
    }

    fun resetHideTimer() {
        if (isShowing && autoHideEnabled) {
            startHideTimer()
        }
    }

    // ── Canvas locked mode ────────────────────────────────────────────
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
        val dp = context!!.resources.displayMetrics.density
        val baseW = if (width > 0) (width * dp).toInt() else (300 * dp).toInt()
        
        view.measure(
            View.MeasureSpec.makeMeasureSpec(baseW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val baseH = if (height > 0) (height * dp).toInt() else view.measuredHeight

        // Update ukuran jendela
        params.width  = (baseW * scale).toInt()
        params.height = (baseH * scale).toInt()

        gestureHelper?.let {
            it.currentScale = scale
            it.updateBaseSize(baseW, baseH)
        }

        runCatching { wm.updateViewLayout(view, params) }
    }

    // ── Inner adapter ─────────────────────────────────────────────────
    private inner class QueueAdapter(
        private val ctx: Context
    ) : RecyclerView.Adapter<QueueAdapter.VH>() {

        private val items = mutableListOf<Song>()
        private var itemLayoutId = currentItemLayoutId

        fun updateItemLayout(layoutId: Int) {
            itemLayoutId = layoutId
            notifyDataSetChanged()
        }

        fun submitList(list: List<Song>) {
            items.clear(); items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(ctx).inflate(itemLayoutId, parent, false)
        )

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.tvTitle?.text  = "${pos + 1}. ${s.title}"
            h.tvMeta?.text   = buildString {
                if (!s.requestedBy.isNullOrBlank()) append("by ${s.requestedBy} ")
                if (s.duration > 0) append("• ${s.durationFormatted}")
            }

            // Apply Theme to item text
            currentTheme.textPrimary?.let { color ->
                h.tvTitle?.setTextColor(color)
                h.tvMeta?.setTextColor(color)
            }

            h.btnPlay?.setOnClickListener   { onPlay(pos) }
            h.btnRemove?.setOnClickListener { onRemove(pos) }
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView?      = v.findViewById(R.id.tv_song_title)
            val tvMeta: TextView?       = v.findViewById(R.id.tv_song_meta)
            val btnPlay: ImageButton?   = v.findViewById(R.id.btn_play_now)
            val btnRemove: ImageButton? = v.findViewById(R.id.btn_remove)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private val Int.dp: Int get() =
        (this * context.resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("DEPRECATION")
    private fun overlayWindowType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
}
