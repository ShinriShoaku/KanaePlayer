package ame.project.kanae.canvas

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ame.project.kanae.R
import ame.project.kanae.service.PlayerForegroundService
import kotlin.math.*

/**
 * CanvasActivity
 * ──────────────
 * Transparent fullscreen activity.
 * Shows two widget cards (player + queue) that can be dragged freely.
 *
 * EDIT MODE  – widgets are draggable, control bar is visible.
 * LOCKED MODE – widgets are fixed in place, control bar slides up and
 *               hides itself after 2 s so the view is fully transparent.
 *
 * When "Lock" is tapped the saved positions are sent to
 * [PlayerForegroundService.enableCanvasMode] so the real overlay windows
 * move to the same coordinates before this Activity finishes.
 *
 * AndroidManifest.xml entry required:
 *   <activity
 *       android:name=".canvas.CanvasActivity"
 *       android:theme="@style/Theme.Canvas"
 *       android:exported="false"
 *       android:launchMode="singleTop" />
 *
 * Theme.Canvas in themes.xml:
 *   <style name="Theme.Canvas" parent="Theme.MaterialComponents.NoActionBar">
 *       <item name="android:windowIsTranslucent">true</item>
 *       <item name="android:windowBackground">@android:color/transparent</item>
 *       <item name="android:windowNoTitle">true</item>
 *   </style>
 */
class CanvasActivity : AppCompatActivity() {

    // ── Service binding ───────────────────────────────────────────────
    private var service: PlayerForegroundService? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlayerForegroundService.LocalBinder).getService()
            serviceBound = true
            syncWidgetData()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null; serviceBound = false
        }
    }

    // ── Views ─────────────────────────────────────────────────────────
    private lateinit var controlBar: View
    private lateinit var btnToggleLock: Button
    private lateinit var hintLabel: TextView
    private lateinit var playerWidget: View
    private lateinit var queueWidget: View

    private var isLocked = false

    // ── Default starting positions (dp) ───────────────────────────────
    private var playerX = 16f;  private var playerY = 80f
    private var queueX  = 16f;  private var queueY  = 250f

    // ─────────────────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_canvas)

        controlBar    = findViewById(R.id.canvas_control_bar)
        btnToggleLock = findViewById(R.id.canvas_btn_toggle_lock)
        hintLabel     = findViewById(R.id.canvas_hint)

        playerWidget  = findViewById(R.id.canvas_player_widget)
        queueWidget   = findViewById(R.id.canvas_queue_widget)

        // Restore saved positions (if any)
        val prefs = getSharedPreferences("ytplayer_prefs", Context.MODE_PRIVATE)
        playerX = prefs.getFloat("canvas_act_px", 16f)
        playerY = prefs.getFloat("canvas_act_py", 80f)
        queueX  = prefs.getFloat("canvas_act_qx", 16f)
        queueY  = prefs.getFloat("canvas_act_qy", 250f)

        applyWidgetPositions()

        // Touch listeners for dragging widgets (edit mode only)
        playerWidget.setOnTouchListener(WidgetDragListener { x, y ->
            playerX = x; playerY = y
        })
        queueWidget.setOnTouchListener(WidgetDragListener { x, y ->
            queueX = x; queueY = y
        })

        btnToggleLock.setOnClickListener { toggleLock() }
        findViewById<Button>(R.id.canvas_btn_close).setOnClickListener { finishCanvas() }

        // Bind service
        bindService(
            Intent(this, PlayerForegroundService::class.java),
            connection, BIND_AUTO_CREATE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
    }

    // ── Lock / Unlock ─────────────────────────────────────────────────
    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) lockCanvas() else unlockCanvas()
    }

    @SuppressLint("SetTextI18n")
    private fun lockCanvas() {
        isLocked = true

        // Disable widget touch
        playerWidget.setOnTouchListener(null)
        queueWidget.setOnTouchListener(null)

        // Save positions
        savePositions()

        // Notify service → real overlay windows move + lock
        service?.enableCanvasMode(
            px = playerX.toInt(), py = (playerY + statusBarHeight()).toInt(),
            qx = queueX.toInt(),  qy = (queueY  + statusBarHeight()).toInt()
        )

        // Tutup activity dan kembali ke MainActivity.
        // User bisa matikan canvas dari tombol "🎨 Canvas ON ✕" di MainActivity.
        android.widget.Toast.makeText(
            this,
            "Canvas dikunci! Tekan '🎨 Canvas ON ✕' di app untuk menonaktifkan.",
            android.widget.Toast.LENGTH_LONG
        ).show()

        finish()
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun unlockCanvas() {
        isLocked = false
        btnToggleLock.text = "🔒 Kunci"
        hintLabel.visibility = View.VISIBLE

        // Show control bar again
        controlBar.visibility = View.VISIBLE
        controlBar.animate().alpha(1f).setDuration(250).start()

        // Re-enable drag
        playerWidget.setOnTouchListener(WidgetDragListener { x, y ->
            playerX = x; playerY = y
        })
        queueWidget.setOnTouchListener(WidgetDragListener { x, y ->
            queueX = x; queueY = y
        })

        service?.disableCanvasMode()
    }

    // ── Sync widget content from service ─────────────────────────────
    private fun syncWidgetData() {
        val svc = service ?: return

        // Update player widget title
        val state = svc.getStateMap()
        val titleTv = playerWidget.findViewById<TextView?>(R.id.overlay_title)
        val songJson = state["current_song"] as? String
        if (songJson != null) {
            val song = com.google.gson.Gson()
                .fromJson(songJson, ame.project.kanae.model.Song::class.java)
            titleTv?.text = song.title
        }

        // Update queue widget badge
        val qCount  = state["queue_count"] as? Int ?: 0
        playerWidget.findViewById<TextView?>(R.id.overlay_queue_count)?.text = "Q: $qCount"
        queueWidget.findViewById<TextView?>(R.id.overlay_queue_count_badge)?.text = "$qCount"

        // Populate queue list in canvas widget
        val queueList = svc.getQueue()
        val emptyTv = queueWidget.findViewById<TextView?>(R.id.overlay_queue_empty)
        val rv      = queueWidget.findViewById<androidx.recyclerview.widget.RecyclerView?>(
            R.id.overlay_rv_queue)

        if (queueList.isEmpty()) {
            emptyTv?.visibility = View.VISIBLE
            rv?.visibility      = View.GONE
        } else {
            emptyTv?.visibility = View.GONE
            rv?.visibility      = View.VISIBLE
            // Simple adapter just showing titles (non-interactive in canvas)
            rv?.adapter = SimpleQueueAdapter(queueList)
            rv?.layoutManager =
                androidx.recyclerview.widget.LinearLayoutManager(this)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private fun applyWidgetPositions() {
        fun setPos(v: View, x: Float, y: Float) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = x.toInt()
            lp.topMargin  = y.toInt()
            v.layoutParams = lp
        }
        setPos(playerWidget, playerX, playerY)
        setPos(queueWidget,  queueX,  queueY)
    }

    private fun savePositions() {
        getSharedPreferences("ytplayer_prefs", Context.MODE_PRIVATE).edit()
            .putFloat("canvas_act_px", playerX)
            .putFloat("canvas_act_py", playerY)
            .putFloat("canvas_act_qx", queueX)
            .putFloat("canvas_act_qy", queueY)
            .apply()
    }

    private fun finishCanvas() {
        if (isLocked) service?.disableCanvasMode()
        savePositions()
        finish()
    }

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    // ── WidgetDragListener (FrameLayout child drag) ───────────────────
    /**
     * Drags a View that is a direct child of a [FrameLayout].
     * Calls [onMoved] with the new (left, top) in px.
     */
    private inner class WidgetDragListener(
        private val onMoved: (Float, Float) -> Unit
    ) : View.OnTouchListener {

        private var startX = 0f; private var startY = 0f
        private var initL  = 0;  private var initT  = 0
        private var hasMoved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX   = e.rawX; startY = e.rawY
                    val lp   = v.layoutParams as? FrameLayout.LayoutParams
                    initL    = lp?.leftMargin ?: 0
                    initT    = lp?.topMargin  ?: 0
                    hasMoved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (abs(dx) > 4 || abs(dy) > 4) hasMoved = true
                    val newL = (initL + dx).coerceAtLeast(0f)
                    val newT = (initT + dy).coerceAtLeast(0f)
                    val lp   = v.layoutParams as? FrameLayout.LayoutParams ?: return true
                    lp.leftMargin = newL.toInt()
                    lp.topMargin  = newT.toInt()
                    v.layoutParams = lp
                    onMoved(newL, newT)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    // Let child buttons handle taps when not moved
                    if (!hasMoved) v.performClick()
                }
            }
            return false
        }
    }

    // ── Simple read-only queue adapter (for canvas preview) ───────────
    private inner class SimpleQueueAdapter(
        private val items: List<ame.project.kanae.model.Song>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SimpleQueueAdapter.VH>() {

        inner class VH(v: View) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val tvTitle: TextView    = v.findViewById(R.id.tv_song_title)
            val tvMeta: TextView     = v.findViewById(R.id.tv_song_meta)
            val btnPlay: ImageButton = v.findViewById(R.id.btn_play_now)
            val btnRm: ImageButton   = v.findViewById(R.id.btn_remove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            layoutInflater.inflate(R.layout.item_queue, parent, false)
        )

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.tvTitle.text = "${pos + 1}. ${s.title}"
            h.tvMeta.text  = buildString {
                if (!s.requestedBy.isNullOrBlank()) append("by ${s.requestedBy} ")
                if (s.duration > 0) append("• ${s.durationFormatted}")
            }
            // In canvas preview, buttons are non-functional
            h.btnPlay.isEnabled = false
            h.btnRm.isEnabled   = false
        }

        override fun getItemCount() = items.size.coerceAtMost(8)
    }
}
