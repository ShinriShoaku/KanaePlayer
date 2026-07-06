package ame.project.kanae.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import ame.project.kanae.R
import ame.project.kanae.subtitle.SubtitleCue
import ame.project.kanae.subtitle.SubtitleFetcher
import ame.project.kanae.subtitle.SubtitleResult
import ame.project.kanae.model.Song
import ame.project.kanae.model.CustomTheme
import kotlinx.coroutines.*

/**
 * Floating lyrics overlay.
 */
class LyricsOverlayManager(
    context: Context,
    private val scope: CoroutineScope,
    private val preferredLang: String = "id",
    private val onClose: () -> Unit
) {
    private val context = context.applicationContext
    companion object {
        private const val TAG = "LyricsOverlay"
    }

    private val wm = this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val fetcher = SubtitleFetcher(context)

    // ── View references ───────────────────────────────────────────────
    private var rootView:    View?        = null
    private var punchLayout: PunchThroughLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null

    private var lastX: Int = 16
    private var lastY: Int = 750
    private var lastScale: Float = 1f
    
    var onPositionChanged: ((x: Int, y: Int, scale: Float) -> Unit)? = null

    private var currentLayoutId: Int = R.layout.overlay_lyrics_layout
    private var currentTheme: CustomTheme = CustomTheme()

    private var tvPrev:     TextView?    = null
    private var tvCurrent:  TextView?    = null
    private var tvNext:     TextView?    = null
    private var tvPrefix:   TextView?    = null
    private var tvSuffix:   TextView?    = null
    private var tvLang:     TextView?    = null
    private var tvTime:     TextView?    = null
    private var tvStatus:   TextView?    = null
    private var statusRow:  LinearLayout? = null
    private var spinner:    ProgressBar? = null

    // ── State ─────────────────────────────────────────────────────────
    private var subtitleResult:  SubtitleResult? = null
    private var fetchJob:        Job? = null
    private var currentSongId:   String? = null
    private var lastCueIndex:    Int = -1

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

    fun updateStyle(layoutId: Int) {
        if (this.currentLayoutId != layoutId) {
            this.currentLayoutId = layoutId
            if (isShowing) {
                val lastX = layoutParams?.x ?: 16
                val lastY = layoutParams?.y ?: 750
                val lastScale = gestureHelper?.currentScale ?: 1f
                hide()
                show(lastX, lastY)
                rootView?.post {
                    applyConfig(lastX, lastY, lastScale)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    fun show(x: Int = lastX, y: Int = lastY) {
        Log.d(TAG, "show() called")
        if (isShowing) return
        this.lastX = x
        this.lastY = y

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view   = LayoutInflater.from(themed)
            .inflate(currentLayoutId, null)
        rootView = view

        val punch = PunchThroughLayout(themed).apply {
            punchEnabled = visualPunchEnabled
            clipChildren = false
            clipToPadding = false
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(view, lp)
        }
        punchLayout = punch

        // Bind views
        tvPrev     = view.findViewById(R.id.overlay_lyrics_prev)
        tvCurrent  = view.findViewById(R.id.overlay_lyrics_current)
        tvNext     = view.findViewById(R.id.overlay_lyrics_next)
        tvPrefix   = view.findViewById(R.id.overlay_lyrics_prefix)
        tvSuffix   = view.findViewById(R.id.overlay_lyrics_suffix)
        tvLang     = view.findViewById(R.id.overlay_lyrics_lang)
        tvTime     = view.findViewById(R.id.overlay_lyrics_time)
        tvStatus   = view.findViewById(R.id.overlay_lyrics_status)
        statusRow  = view.findViewById(R.id.overlay_lyrics_status_row)
        spinner    = view.findViewById(R.id.overlay_lyrics_spinner)

        applyThemeToView(view)

        view.findViewById<ImageButton>(R.id.overlay_lyrics_close)
            ?.setOnClickListener { 
                Log.d(TAG, "Close button clicked")
                hide()
                onClose()
            }

        // Disable view-tree clipping for rotation support
        if (view is ViewGroup) {
            view.clipChildren  = false
            view.clipToPadding = false
        }

        val type = overlayType()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = lastX; it.y = lastY
        }
        layoutParams = params

        gestureHelper = OverlayGestureHelper(
            rootView    = punch,
            params      = params,
            wm          = wm,
            onSingleTap = null
        ).also { 
            it.currentScale = lastScale
            it.onInteraction = {
                lastX = params.x
                lastY = params.y
                lastScale = it.currentScale
                onPositionChanged?.invoke(lastX, lastY, lastScale)
            }
            if (visualPunchEnabled) view.setOnTouchListener(null)
            else view.setOnTouchListener(it)
        }

        wm.addView(punch, params)
        isShowing = true
        
        rootView?.post {
            applyConfig(lastX, lastY, lastScale)
        }

        showStatus("Menunggu lagu…")
    }

    fun hide() {
        Log.d(TAG, "hide() called")
        if (!isShowing) return
        fetchJob?.cancel()
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
        
        tvPrev        = null
        tvCurrent     = null
        tvNext        = null
        tvPrefix      = null
        tvSuffix      = null
        tvLang        = null
        tvTime        = null
        tvStatus      = null
        statusRow     = null
        spinner       = null

        isShowing     = false
        clearState()
    }

    fun loadForSong(song: Song) {
        if (!isShowing) return
        if (song.id == currentSongId) return

        currentSongId = song.id
        lastCueIndex  = -1
        subtitleResult = null
        clearCues()
        showStatus("Fetching lyrics…")

        fetchJob?.cancel()
        fetchJob = scope.launch {
            val result = fetcher.fetch(song.youtubeUrl, preferredLang)
            withContext(Dispatchers.Main) {
                if (!isShowing) return@withContext
                if (result == null || result.cues.isEmpty()) {
                    hideStatus()
                    setCurrentText("— No lyrics available —")
                    tvLang?.text = "—"
                } else {
                    subtitleResult = result
                    hideStatus()
                    val langLabel = result.languageTag.uppercase()
                    tvLang?.text  = if (result.isAutoGenerated) "$langLabel·AUTO" else langLabel
                    setCurrentText("")
                }
            }
        }
    }

    fun updatePosition(positionMs: Long) {
        if (!isShowing) return
        tvTime?.text = fmt((positionMs / 1000).toInt())

        val result = subtitleResult ?: return
        val cues   = result.cues
        val activeIdx = findCueIndex(cues, positionMs)
        if (activeIdx == lastCueIndex) return
        
        lastCueIndex = activeIdx
        val prevText = cues.getOrNull(activeIdx - 1)?.text ?: ""
        val currText = if (activeIdx >= 0) cues[activeIdx].text else ""
        val nextText = cues.getOrNull(activeIdx + 1)?.text ?: ""

        animateCueChange(currText)
        tvPrev?.text = prevText
        tvNext?.text = nextText
    }

    fun setCanvasMode(locked: Boolean, x: Int = 0, y: Int = 0) {
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
            tvPrev?.setTextColor(color)
            tvCurrent?.setTextColor(color)
            tvNext?.setTextColor(color)
            tvPrefix?.setTextColor(color)
            tvSuffix?.setTextColor(color)
            tvLang?.setTextColor(color)
            tvTime?.setTextColor(color)
            tvStatus?.setTextColor(color)
        }
    }

    fun applyConfig(x: Int, y: Int, scale: Float, width: Int = 0, height: Int = 0) {
        this.lastX = x
        this.lastY = y
        this.lastScale = scale

        val params = layoutParams ?: return
        val view   = punchLayout ?: return
        val content = rootView ?: return

        params.x = x
        params.y = y
        
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale

        val dm = context.resources.displayMetrics
        val maxW = (dm.widthPixels / scale).toInt()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val baseW = content.measuredWidth
        val baseH = content.measuredHeight

        val contentLp = content.layoutParams as? FrameLayout.LayoutParams
        if (contentLp != null) {
            contentLp.width = baseW
            contentLp.height = baseH
            content.layoutParams = contentLp
        }

        params.width  = (baseW * scale).toInt().coerceAtLeast(1)
        params.height = (baseH * scale).toInt().coerceAtLeast(1)

        gestureHelper?.let {
            it.currentScale = scale
            it.updateBaseSize(baseW, baseH)
        }

        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun findCueIndex(cues: List<SubtitleCue>, posMs: Long): Int {
        var lo = 0; var hi = cues.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= posMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (result != -1) {
            val cue = cues[result]
            if (posMs > cue.endMs + 5000) return -1
        }
        return result
    }

    private fun animateCueChange(text: String) {
        val tv = tvCurrent ?: return
        if (tv.text == text) return
        tv.animate().alpha(0f).setDuration(120).withEndAction {
            tv.text = text
            applyConfig(lastX, lastY, lastScale)
            tv.animate().alpha(1f).setDuration(180).start()
        }.start()
    }

    private fun setCurrentText(text: String) { 
        tvCurrent?.text = text
        applyConfig(lastX, lastY, lastScale)
    }

    private fun clearCues() {
        tvPrev?.text    = ""
        tvCurrent?.text = "— No lyrics —"
        tvNext?.text    = ""
    }

    private fun clearState() {
        subtitleResult = null; currentSongId = null; lastCueIndex = -1
    }

    private fun showStatus(msg: String) {
        statusRow?.visibility  = View.VISIBLE
        tvStatus?.text = msg
        applyConfig(lastX, lastY, lastScale)
    }

    private fun hideStatus() {
        statusRow?.visibility = View.GONE
        applyConfig(lastX, lastY, lastScale)
    }

    private fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

    @Suppress("DEPRECATION")
    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
}