package ame.project.kanae.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import ame.project.kanae.R
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

class SaweriaOverlayManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val PREF_FILE       = "saweria_prefs"
        const val PREF_STREAM_KEY = "stream_key"
        private const val BASE    = "https://saweria.co/widgets"

        fun widgetPath(widget: SaweriaWidget): String = when (widget) {
            SaweriaWidget.ALERT        -> "alert"
            SaweriaWidget.TOPUP        -> "topup"
            SaweriaWidget.MEDIASHARE   -> "mediashare"
            SaweriaWidget.QR           -> "qr"
            SaweriaWidget.MILESTONE    -> "milestone"
            SaweriaWidget.LEADERBOARD  -> "leaderboard"
            SaweriaWidget.RECENT       -> "recent"
            SaweriaWidget.WHEEL        -> "wheel"
            SaweriaWidget.SUBATHON     -> "subathon"
            SaweriaWidget.VOTE         -> "vote"
        }
    }

    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private data class OverlayEntry(
        val widget: SaweriaWidget,
        val root: View,
        val webView: WebView,
        val params: WindowManager.LayoutParams,
        val gesture: OverlayGestureHelper
    )

    private val active = mutableMapOf<SaweriaWidget, OverlayEntry>()
    private var adjusterRoot: View? = null

    /** Callback to notify UI (Activity) when a widget is shown or hidden from within the manager */
    var onWidgetVisibilityChanged: ((SaweriaWidget, Boolean) -> Unit)? = null

    var streamKey: String
        get() = prefs.getString(PREF_STREAM_KEY, "") ?: ""
        set(value) { prefs.edit().putString(PREF_STREAM_KEY, value).apply() }

    fun isShowing(widget: SaweriaWidget) = active.containsKey(widget)

    fun showWidget(widget: SaweriaWidget) {
        if (isShowing(widget)) return
        val key = streamKey
        if (key.isBlank()) return
        
        val url = "$BASE/${widgetPath(widget)}?streamKey=$key"
        val (w, h, s) = loadSize(widget)
        val dp = context.resources.displayMetrics.density
        
        active[widget] = buildEntry(widget, url, (w * dp).toInt(), (h * dp).toInt(), s)
        onWidgetVisibilityChanged?.invoke(widget, true)
    }

    fun hideWidget(widget: SaweriaWidget) {
        val entry = active.remove(widget) ?: return
        entry.webView.destroy()
        runCatching { wm.removeView(entry.root) }
        if (active.isEmpty()) hideAdjuster()
        onWidgetVisibilityChanged?.invoke(widget, false)
    }

    fun toggleWidget(widget: SaweriaWidget): Boolean =
        if (isShowing(widget)) { hideWidget(widget); false }
        else                   { showWidget(widget); true  }

    fun hideAll() { SaweriaWidget.entries.forEach { hideWidget(it) } }
    fun reloadAll() {
        val was = active.keys.toList()
        was.forEach { hideWidget(it) }
        was.forEach { showWidget(it) }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun buildEntry(
        widget: SaweriaWidget,
        url: String,
        widthPx: Int,
        heightPx: Int,
        savedScale: Float
    ): OverlayEntry {

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_saweria_widget, null)

        val wv = root.findViewById<WebView>(R.id.saweria_webview).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                useWideViewPort = true
                loadWithOverviewMode = true
                textZoom = 100
            }
            setBackgroundColor(0x00000000)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    v?.evaluateJavascript("document.body.style.background='transparent';document.documentElement.style.background='transparent';", null)
                }
            }
            loadUrl(url)
        }

        root.findViewById<ImageButton>(R.id.saweria_btn_close).setOnClickListener { hideWidget(widget) }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            widthPx, heightPx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { 
            it.gravity = Gravity.TOP or Gravity.START
            it.x = prefs.getInt("pos_x_${widget.name}", 20)
            it.y = prefs.getInt("pos_y_${widget.name}", 140)
        }

        // Load and apply background color
        val bgColor = prefs.getInt("bg_color_${widget.name}", Color.TRANSPARENT)
        root.setBackgroundColor(bgColor)

        val gesture = OverlayGestureHelper(rootView = root, params = params, wm = wm, onSingleTap = {
            showAdjuster(widget)
        })
        
        root.scaleX = savedScale
        root.scaleY = savedScale
        gesture.currentScale = savedScale

        root.findViewById<View>(R.id.saweria_gesture_layer).setOnTouchListener(gesture)

        wm.addView(root, params)
        return OverlayEntry(widget, root, wv, params, gesture)
    }

    // ── Adjustment UI (Floating Bottom Sheet) ────────────────────────

    fun showAdjuster(widget: SaweriaWidget) {
        hideAdjuster()
        val entry = active[widget] ?: return
        
        val themed = ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themed).inflate(R.layout.layout_saweria_adjuster, null)
        adjusterRoot = view

        val tvTitle = view.findViewById<TextView>(R.id.adj_title)
        val tvW = view.findViewById<TextView>(R.id.tv_width_label)
        val tvH = view.findViewById<TextView>(R.id.tv_height_label)
        val tvS = view.findViewById<TextView>(R.id.tv_scale_label)
        val tvA = view.findViewById<TextView>(R.id.tv_alpha_label)
        
        val skW = view.findViewById<SeekBar>(R.id.seek_width)
        val skH = view.findViewById<SeekBar>(R.id.seek_height)
        val skS = view.findViewById<SeekBar>(R.id.seek_scale)
        val skA = view.findViewById<SeekBar>(R.id.seek_alpha)

        tvTitle.text = "Adjust ${widget.displayName}"
        
        val dp = context.resources.displayMetrics.density
        var currW = (entry.params.width / dp).toInt()
        var currH = (entry.params.height / dp).toInt()
        var currS = entry.root.scaleX
        
        val savedColor = prefs.getInt("bg_color_${widget.name}", Color.TRANSPARENT)
        var currAlpha = Color.alpha(savedColor)
        var currRGB   = savedColor and 0x00FFFFFF

        skW.progress = currW; tvW.text = "Width: ${currW}dp"
        skH.progress = currH; tvH.text = "Height: ${currH}dp"
        skS.progress = (currS * 100).toInt(); tvS.text = "Scale: %.2fx".format(currS)
        skA.progress = currAlpha; tvA.text = "BG Opacity: ${(currAlpha * 100 / 255)}%"

        val update = {
            entry.params.width = (currW * dp).toInt()
            entry.params.height = (currH * dp).toInt()
            entry.root.scaleX = currS
            entry.root.scaleY = currS
            entry.gesture.currentScale = currS
            
            val finalColor = (currAlpha shl 24) or currRGB
            entry.root.setBackgroundColor(finalColor)
            
            saveSize(widget, currW, currH, currS, finalColor)
            
            prefs.edit().putInt("pos_x_${widget.name}", entry.params.x)
                        .putInt("pos_y_${widget.name}", entry.params.y).apply()
            
            try { wm.updateViewLayout(entry.root, entry.params) } catch(e:Exception){}
        }

        skW.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { currW = p.coerceAtLeast(50); tvW.text = "Width: ${currW}dp"; update() }
            override fun onStartTrackingTouch(p: SeekBar?) {}
            override fun onStopTrackingTouch(p: SeekBar?) {}
        })
        skH.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { currH = p.coerceAtLeast(50); tvH.text = "Height: ${currH}dp"; update() }
            override fun onStartTrackingTouch(p: SeekBar?) {}
            override fun onStopTrackingTouch(p: SeekBar?) {}
        })
        skS.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { currS = p.coerceAtLeast(30) / 100f; tvS.text = "Scale: %.2fx".format(currS); update() }
            override fun onStartTrackingTouch(p: SeekBar?) {}
            override fun onStopTrackingTouch(p: SeekBar?) {}
        })
        skA.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { currAlpha = p; tvA.text = "BG Opacity: ${(p * 100 / 255)}%"; update() }
            override fun onStartTrackingTouch(p: SeekBar?) {}
            override fun onStopTrackingTouch(p: SeekBar?) {}
        })

        // Color Presets
        val setRGB = { rgb: Int -> currRGB = rgb; update() }
        view.findViewById<View>(R.id.color_none).setOnClickListener { currAlpha = 0; skA.progress = 0; update() }
        view.findViewById<View>(R.id.color_black).setOnClickListener { setRGB(0x000000) }
        view.findViewById<View>(R.id.color_teal).setOnClickListener { setRGB(0x008080) }
        view.findViewById<View>(R.id.color_blue).setOnClickListener { setRGB(0x1A1A2E) }
        view.findViewById<View>(R.id.color_green).setOnClickListener { setRGB(0x00FF00) }

        view.findViewById<Button>(R.id.btn_close_adjuster).setOnClickListener { hideAdjuster() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        wm.addView(view, params)
    }

    private fun hideAdjuster() {
        adjusterRoot?.let { runCatching { wm.removeView(it) } }
        adjusterRoot = null
    }

    private fun saveSize(widget: SaweriaWidget, w: Int, h: Int, s: Float, color: Int) {
        prefs.edit()
            .putInt("w_${widget.name}", w)
            .putInt("h_${widget.name}", h)
            .putFloat("s_${widget.name}", s)
            .putInt("bg_color_${widget.name}", color)
            .apply()
    }

    private fun loadSize(widget: SaweriaWidget): Triple<Int, Int, Float> {
        val def = when(widget) {
            SaweriaWidget.MILESTONE -> Triple(450, 80, 1.0f)
            SaweriaWidget.LEADERBOARD -> Triple(300, 450, 1.0f)
            SaweriaWidget.WHEEL -> Triple(400, 400, 1.0f)
            else -> Triple(400, 250, 1.0f)
        }
        return Triple(
            prefs.getInt("w_${widget.name}", def.first),
            prefs.getInt("h_${widget.name}", def.second),
            prefs.getFloat("s_${widget.name}", def.third)
        )
    }
}

enum class SaweriaWidget(val displayName: String, val emoji: String) {
    ALERT("Alert", "🔔"), TOPUP("Top Up", "💰"), MEDIASHARE("Media Share", "🎬"),
    QR("QR Code", "📱"), MILESTONE("Milestone", "🎯"), LEADERBOARD("Leaderboard", "🏆"),
    RECENT("Recent", "🕐"), WHEEL("Wheel", "🎡"), SUBATHON("Subathon", "⏱️"), VOTE("Voting", "🗳️")
}
