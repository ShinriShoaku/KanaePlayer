package ame.project.kanae.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import ame.project.kanae.R
import ame.project.kanae.SettingsManager
import ame.project.kanae.OverlayConfig
import kotlinx.coroutines.CoroutineScope

class SaweriaOverlayManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val BASE = "https://saweria.co/widgets"
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
    private val settingsManager = SettingsManager.getInstance(context)

    private data class OverlayEntry(
        val widget: SaweriaWidget,
        val root: View,
        val punchLayout: PunchThroughLayout,
        val webView: WebView,
        val params: WindowManager.LayoutParams,
        val gesture: OverlayGestureHelper
    )

    private val active = mutableMapOf<SaweriaWidget, OverlayEntry>()
    private var adjusterRoot: View? = null

    var onWidgetVisibilityChanged: ((SaweriaWidget, Boolean) -> Unit)? = null

    var streamKey: String
        get() = settingsManager.settings.saweriaStreamKey
        set(value) { settingsManager.settings.saweriaStreamKey = value; settingsManager.saveSettings() }

    fun isShowing(widget: SaweriaWidget) = active.containsKey(widget)

    fun showWidget(widget: SaweriaWidget) {
        if (isShowing(widget)) return
        val key = streamKey
        if (key.isBlank()) return
        
        val url = "$BASE/${widgetPath(widget)}?streamKey=$key"
        val config = loadConfig(widget)
        val dp = context.resources.displayMetrics.density
        
        val entry = buildEntry(widget, url, config, dp)
        active[widget] = entry
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
        config: OverlayConfig,
        dp: Float
    ): OverlayEntry {
        val root = LayoutInflater.from(context).inflate(R.layout.overlay_saweria_widget, null)
        val punchLayout = root as PunchThroughLayout
        punchLayout.targetWidth = (config.width * dp).toInt()
        punchLayout.targetHeight = (config.height * dp).toInt()
        punchLayout.currentScale = config.scale
        punchLayout.punchEnabled = config.visualPunch

        val wv = root.findViewById<WebView>(R.id.saweria_webview).apply {
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW; cacheMode = WebSettings.LOAD_NO_CACHE
                useWideViewPort = true; loadWithOverviewMode = true; textZoom = 100
            }
            setBackgroundColor(0x00000000)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    v?.evaluateJavascript("document.body.style.background='transparent';document.documentElement.style.background='transparent';", null)
                }
            }
            loadUrl(url)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            (config.width * dp * config.scale).toInt(), (config.height * dp * config.scale).toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { 
            it.gravity = Gravity.TOP or Gravity.START
            it.x = config.x
            it.y = config.y
        }

        root.setBackgroundColor(config.bgColor)

        val gesture = OverlayGestureHelper(rootView = root, params = params, wm = wm, onSingleTap = {
            showAdjuster(widget)
        }).apply {
            onInteraction = {
                if (params.x != -10000) {
                    config.x = params.x
                    config.y = params.y
                    config.scale = currentScale
                    settingsManager.saveSettings()
                }
            }
        }
        
        root.pivotX = 0f; root.pivotY = 0f; root.scaleX = config.scale; root.scaleY = config.scale
        gesture.currentScale = config.scale
        gesture.updateBaseSize((config.width * dp).toInt(), (config.height * dp).toInt())
        root.findViewById<View>(R.id.saweria_gesture_layer).setOnTouchListener(if (config.visualPunch) null else gesture)

        wm.addView(root, params)
        return OverlayEntry(widget, root, punchLayout, wv, params, gesture)
    }

    fun showAdjuster(widget: SaweriaWidget) {
        hideAdjuster()
        val entry = active[widget] ?: return
        val themed = ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themed).inflate(R.layout.layout_saweria_adjuster, null)
        adjusterRoot = view

        val tvTitle = view.findViewById<TextView>(R.id.adj_title)
        val tvW = view.findViewById<TextView>(R.id.tv_width_label); val tvH = view.findViewById<TextView>(R.id.tv_height_label)
        val tvS = view.findViewById<TextView>(R.id.tv_scale_label); val tvA = view.findViewById<TextView>(R.id.tv_alpha_label)
        val skW = view.findViewById<SeekBar>(R.id.seek_width); val skH = view.findViewById<SeekBar>(R.id.seek_height)
        val skS = view.findViewById<SeekBar>(R.id.seek_scale); val skA = view.findViewById<SeekBar>(R.id.seek_alpha)

        tvTitle.text = "Adjust ${widget.displayName}"
        val dp = context.resources.displayMetrics.density
        val config = settingsManager.settings.saweriaWidgets[widget.name] ?: OverlayConfig()
        
        var currW = config.width; var currH = config.height; var currS = config.scale
        var currAlpha = Color.alpha(config.bgColor); var currRGB = config.bgColor and 0x00FFFFFF

        skW.progress = currW; tvW.text = "Width: ${currW}dp"
        skH.progress = currH; tvH.text = "Height: ${currH}dp"
        skS.progress = (currS * 100).toInt(); tvS.text = "Scale: %.2fx".format(currS)
        skA.progress = currAlpha; tvA.text = "BG Opacity: ${(currAlpha * 100 / 255)}%"

        val update = {
            entry.params.width = (currW * dp * currS).toInt(); entry.params.height = (currH * dp * currS).toInt()
            entry.root.pivotX = 0f; entry.root.pivotY = 0f; entry.root.scaleX = currS; entry.root.scaleY = currS
            entry.punchLayout.targetWidth = (currW * dp).toInt()
            entry.punchLayout.targetHeight = (currH * dp).toInt()
            entry.punchLayout.currentScale = currS
            entry.gesture.currentScale = currS
            entry.gesture.updateBaseSize((currW * dp).toInt(), (currH * dp).toInt())
            val finalColor = (currAlpha shl 24) or currRGB
            entry.root.setBackgroundColor(finalColor)
            
            config.width = currW; config.height = currH; config.scale = currS; config.bgColor = finalColor
            config.x = entry.params.x; config.y = entry.params.y
            
            entry.root.findViewById<View>(R.id.saweria_gesture_layer).setOnTouchListener(if (config.visualPunch) null else entry.gesture)

            settingsManager.saveSettings()
            try { wm.updateViewLayout(entry.root, entry.params) } catch(_:Exception){}
        }

        skW.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if(b) { currW = p.coerceAtLeast(50); tvW.text = "Width: ${currW}dp"; update() } }; override fun onStartTrackingTouch(p: SeekBar?) {}; override fun onStopTrackingTouch(p: SeekBar?) {} })
        skH.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if(b) { currH = p.coerceAtLeast(50); tvH.text = "Height: ${currH}dp"; update() } }; override fun onStartTrackingTouch(p: SeekBar?) {}; override fun onStopTrackingTouch(p: SeekBar?) {} })
        skS.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if(b) { currS = p.coerceAtLeast(30) / 100f; tvS.text = "Scale: %.2fx".format(currS); update() } }; override fun onStartTrackingTouch(p: SeekBar?) {}; override fun onStopTrackingTouch(p: SeekBar?) {} })
        skA.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if(b) { currAlpha = p; tvA.text = "BG Opacity: ${(p * 100 / 255)}%"; update() } }; override fun onStartTrackingTouch(p: SeekBar?) {}; override fun onStopTrackingTouch(p: SeekBar?) {} })

        val setRGB = { rgb: Int -> currRGB = rgb; update() }
        view.findViewById<View>(R.id.color_none).setOnClickListener { currAlpha = 0; skA.progress = 0; update() }
        view.findViewById<View>(R.id.color_black).setOnClickListener { setRGB(0x000000) }
        view.findViewById<View>(R.id.color_teal).setOnClickListener { setRGB(0x008080) }
        view.findViewById<View>(R.id.color_blue).setOnClickListener { setRGB(0x1A1A2E) }
        view.findViewById<View>(R.id.color_green).setOnClickListener { setRGB(0x00FF00) }
        view.findViewById<Button>(R.id.btn_close_adjuster).setOnClickListener { hideAdjuster() }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        wm.addView(view, params)
    }

    private fun hideAdjuster() { adjusterRoot?.let { runCatching { wm.removeView(it) } }; adjusterRoot = null }

    private fun loadConfig(widget: SaweriaWidget): OverlayConfig {
        return settingsManager.settings.saweriaWidgets.getOrPut(widget.name) {
            when(widget) {
                SaweriaWidget.MILESTONE -> OverlayConfig(x = 20, y = 140, width = 450, height = 80)
                SaweriaWidget.LEADERBOARD -> OverlayConfig(x = 20, y = 140, width = 300, height = 450)
                SaweriaWidget.WHEEL -> OverlayConfig(x = 20, y = 140, width = 400, height = 400)
                else -> OverlayConfig(x = 20, y = 140, width = 400, height = 250)
            }
        }
    }
}

enum class SaweriaWidget(val displayName: String, val emoji: String) {
    ALERT("Alert", "🔔"), TOPUP("Top Up", "💰"), MEDIASHARE("Media Share", "🎬"),
    QR("QR Code", "📱"), MILESTONE("Milestone", "🎯"), LEADERBOARD("Leaderboard", "🏆"),
    RECENT("Recent", "🕐"), WHEEL("Wheel", "🎡"), SUBATHON("Subathon", "⏱️"), VOTE("Voting", "🗳️")
}
