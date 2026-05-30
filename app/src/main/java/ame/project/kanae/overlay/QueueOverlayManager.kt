package ame.project.kanae.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ame.project.kanae.R
import ame.project.kanae.model.Song

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
    private val context: Context,
    private val onPlay: (Int) -> Unit,
    private val onRemove: (Int) -> Unit
) {
    companion object {
        private const val MAX_VISIBLE_ITEMS = 8
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View?              = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null
    private var adapter: QueueAdapter?       = null
    private var tvEmpty: TextView?           = null
    private var tvBadge: TextView?           = null
    private var rvQueue: RecyclerView?       = null

    var isShowing: Boolean = false
        private set

    private var canvasLocked = false

    // ─────────────────────────────────────────────────────────────────
    fun show(queue: List<Song> = emptyList()) {
        if (isShowing) {
            updateQueue(queue)
            return
        }

        val themed = ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view   = LayoutInflater.from(themed).inflate(R.layout.overlay_queue_layout, null)
        rootView   = view

        tvEmpty = view.findViewById(R.id.overlay_queue_empty)
        tvBadge = view.findViewById(R.id.overlay_queue_count_badge)
        rvQueue = view.findViewById(R.id.overlay_rv_queue)

        rvQueue?.layoutManager = LinearLayoutManager(themed)
        adapter = QueueAdapter(themed).also {
            rvQueue?.adapter = it
        }

        view.findViewById<ImageButton>(R.id.overlay_queue_close)
            .setOnClickListener { hide() }

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
            rootView    = view,
            params      = params,
            wm          = wm,
            onSingleTap = null
        ).also { view.setOnTouchListener(it) }

        wm.addView(view, params)
        isShowing = true

        updateQueue(queue)
    }

    fun hide() {
        if (!isShowing) return
        rootView?.let { runCatching { wm.removeView(it) } }
        rootView  = null
        isShowing = false
    }

    /**
     * Update the displayed queue.
     * Toggles empty state vs. RecyclerView visibility automatically.
     */
    fun updateQueue(queue: List<Song>) {
        val visible = queue.take(MAX_VISIBLE_ITEMS)
        adapter?.submitList(visible)
        tvBadge?.text = queue.size.toString()

        val hasItems = visible.isNotEmpty()
        tvEmpty?.visibility = if (hasItems) View.GONE  else View.VISIBLE
        rvQueue?.visibility = if (hasItems) View.VISIBLE else View.GONE

        // Notify WindowManager that height may have changed
        rootView?.post {
            layoutParams?.let { runCatching { wm.updateViewLayout(rootView, it) } }
        }
    }

    /**
     * Show the overlay automatically when a new song enters the queue from chat.
     * Call this after adding to queue; only shows if not already visible.
     */
    fun autoShowIfNeeded(queue: List<Song>) {
        if (queue.isEmpty()) return
        if (isShowing) updateQueue(queue)
        else show(queue)
    }

    // ── Canvas locked mode ────────────────────────────────────────────
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

    // ── Inner adapter ─────────────────────────────────────────────────
    private inner class QueueAdapter(
        private val ctx: Context
    ) : RecyclerView.Adapter<QueueAdapter.VH>() {

        private val items = mutableListOf<Song>()

        fun submitList(list: List<Song>) {
            items.clear(); items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(ctx).inflate(R.layout.item_queue, parent, false)
        )

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.tvTitle.text  = "${pos + 1}. ${s.title}"
            h.tvMeta.text   = buildString {
                if (!s.requestedBy.isNullOrBlank()) append("by ${s.requestedBy} ")
                if (s.duration > 0) append("• ${s.durationFormatted}")
            }
            h.btnPlay.setOnClickListener   { onPlay(pos) }
            h.btnRemove.setOnClickListener { onRemove(pos) }
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView      = v.findViewById(R.id.tv_song_title)
            val tvMeta: TextView       = v.findViewById(R.id.tv_song_meta)
            val btnPlay: ImageButton   = v.findViewById(R.id.btn_play_now)
            val btnRemove: ImageButton = v.findViewById(R.id.btn_remove)
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
