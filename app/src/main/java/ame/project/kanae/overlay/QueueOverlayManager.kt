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

class QueueOverlayManager(
    private val context: Context,
    private val onPlay: (Int) -> Unit,
    private val onRemove: (Int) -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var adapter: QueueAdapter? = null

    var isShowing: Boolean = false
        private set

    fun show(queue: List<Song>) {
        if (isShowing) {
            adapter?.submitList(queue)
            return
        }
        val themedContext = ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_queue_layout, null)
        rootView = view

        val rv = view.findViewById<RecyclerView>(R.id.overlay_rv_queue)
        rv.layoutManager = LinearLayoutManager(themedContext)
        adapter = QueueAdapter(themedContext).also { it.submitList(queue) }
        rv.adapter = adapter

        view.findViewById<ImageButton>(R.id.overlay_queue_close).setOnClickListener { hide() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            320, 400,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 420
        }

        val drag = OverlayDragListener(view, params, wm)
        view.findViewById<View>(R.id.overlay_queue_header).setOnTouchListener(drag)

        wm.addView(view, params)
        isShowing = true
    }

    fun hide() {
        if (!isShowing) return
        rootView?.let { wm.removeView(it) }
        rootView = null
        isShowing = false
    }

    fun updateQueue(queue: List<Song>) {
        adapter?.submitList(queue)
    }

    private inner class QueueAdapter(private val themedContext: Context) : RecyclerView.Adapter<QueueAdapter.VH>() {
        private val items = mutableListOf<Song>()
        fun submitList(list: List<Song>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(
            LayoutInflater.from(themedContext).inflate(R.layout.item_queue, p, false)
        )
        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.tvTitle.text = "${pos + 1}. ${s.title}"
            h.tvMeta.text = buildString {
                if (!s.requestedBy.isNullOrBlank()) append("by ${s.requestedBy} ")
                if (s.duration > 0) append("• ${s.durationFormatted}")
            }
            h.btnPlay.setOnClickListener { onPlay(pos) }
            h.btnRemove.setOnClickListener { onRemove(pos) }
        }
        override fun getItemCount() = items.size
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tv_song_title)
            val tvMeta: TextView = v.findViewById(R.id.tv_song_meta)
            val btnPlay: ImageButton = v.findViewById(R.id.btn_play_now)
            val btnRemove: ImageButton = v.findViewById(R.id.btn_remove)
        }
    }
}