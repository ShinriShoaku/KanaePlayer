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
import android.widget.ProgressBar
import android.widget.TextView
import ame.project.kanae.R
import ame.project.kanae.model.Song
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request

class OverlayManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPlayPause: () -> Unit,
    private val onSkip: () -> Unit,
    private val onClose: () -> Unit
) {
    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var tvTitle: TextView? = null
    private var tvQueue: TextView? = null
    private var tvTime: TextView? = null
    private var progressBar: ProgressBar? = null
    private var dotLive: View? = null
    private var btnPlayPause: ImageButton? = null
    private var ivThumbnail: ImageView? = null
    private var currentSongId: String? = null

    private val http = OkHttpClient()

    var isShowing: Boolean = false
        private set

    fun show() {
        if (isShowing) return

        val themedContext = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.overlay_layout, null)
        rootView = view

        tvTitle     = view.findViewById(R.id.overlay_title)
        tvQueue     = view.findViewById(R.id.overlay_queue_count)
        tvTime      = view.findViewById(R.id.overlay_time)
        progressBar = view.findViewById(R.id.overlay_progress)
        dotLive     = view.findViewById(R.id.overlay_live_dot)
        btnPlayPause = view.findViewById(R.id.overlay_btn_play_pause)
        ivThumbnail = view.findViewById(R.id.overlay_thumbnail)

        btnPlayPause?.setOnClickListener { onPlayPause() }
        view.findViewById<ImageButton>(R.id.overlay_btn_skip)?.setOnClickListener { onSkip() }
        view.findViewById<ImageButton>(R.id.overlay_btn_close)?.setOnClickListener {
            hide()
            onClose()
        }

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
        currentSongId = null
    }

    fun updateSong(song: Song?, positionMs: Long, durationMs: Long) {
        if (!isShowing) return

        if (song?.id != currentSongId) {
            currentSongId = song?.id
            tvTitle?.text = song?.title ?: "– Nothing playing –"
            tvTitle?.alpha = 0f
            tvTitle?.animate()?.alpha(1f)?.setDuration(350)?.start()
            updateThumbnail(song?.thumbnail)
        }

        val progress = if (durationMs > 0) (positionMs * 100 / durationMs).toInt() else 0
        progressBar?.progress = progress

        val posSec  = (positionMs / 1000).toInt()
        val durSec  = (durationMs / 1000).toInt()
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
        ivThumbnail?.let { iv ->
            (iv.tag as? AnimatorSet)?.cancel()
            if (isPlaying) {
                val scaleX = ObjectAnimator.ofFloat(iv, "scaleX", 1.0f, 1.08f).apply {
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                }
                val scaleY = ObjectAnimator.ofFloat(iv, "scaleY", 1.0f, 1.08f).apply {
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                }
                val set = AnimatorSet().apply {
                    playTogether(scaleX, scaleY)
                    duration = 2000
                }
                iv.tag = set
                set.start()
            } else {
                iv.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
    }

    private fun updateThumbnail(url: String?) {
        ivThumbnail ?: return
        if (url.isNullOrBlank()) {
            ivThumbnail?.setImageResource(android.R.drawable.ic_media_play)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                val resp = http.newCall(req).execute()
                val bmp = resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                withContext(Dispatchers.Main) {
                    ivThumbnail?.setImageBitmap(bmp)
                    ivThumbnail?.alpha = 0f
                    ivThumbnail?.scaleX = 0.8f
                    ivThumbnail?.scaleY = 0.8f
                    ivThumbnail?.animate()
                        ?.alpha(1f)
                        ?.scaleX(1f)
                        ?.scaleY(1f)
                        ?.setDuration(400)
                        ?.start()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    ivThumbnail?.setImageResource(android.R.drawable.ic_media_play)
                }
            }
        }
    }

    private fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
}

internal class OverlayDragListener(
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