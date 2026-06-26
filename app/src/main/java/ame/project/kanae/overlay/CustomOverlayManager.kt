package ame.project.kanae.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.*
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import ame.project.kanae.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

data class CustomOverlayConfig(
    val id: String,
    var name: String,
    var url: String,
    var posX: Int = 40,
    var posY: Int = 200,
    var scale: Float = 1.0f,
    var width: Int = 400,
    var height: Int = 250,
    var bgColor: Int = Color.TRANSPARENT,
    var autoHide: Boolean = false,
    var durationSec: Int = 5
)

class CustomOverlayManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val PREF_FILE = "custom_overlay_prefs_v5"
        const val PREF_CONFIGS = "configs_json"
    }

    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    private data class OverlayEntry(
        val config: CustomOverlayConfig,
        val root: View,
        val webView: WebView,
        val params: WindowManager.LayoutParams,
        val gesture: OverlayGestureHelper,
        var hideJob: Job? = null
    )

    private val active = mutableMapOf<String, OverlayEntry>()
    private var adjusterRoot: View? = null

    var onWidgetVisibilityChanged: ((String, Boolean) -> Unit)? = null

    fun getConfigs(): MutableList<CustomOverlayConfig> {
        val json = prefs.getString(PREF_CONFIGS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<CustomOverlayConfig>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun saveConfigs(configs: List<CustomOverlayConfig>) {
        prefs.edit().putString(PREF_CONFIGS, gson.toJson(configs)).apply()
    }

    fun addConfig(name: String, url: String): CustomOverlayConfig {
        val configs = getConfigs()
        val newConfig = CustomOverlayConfig(
            id = System.currentTimeMillis().toString(),
            name = name,
            url = url
        )
        configs.add(newConfig)
        saveConfigs(configs)
        return newConfig
    }

    fun removeConfig(id: String) {
        stopWidget(id)
        val configs = getConfigs()
        configs.removeAll { it.id == id }
        saveConfigs(configs)
    }

    fun updateConfig(updated: CustomOverlayConfig) {
        val configs = getConfigs()
        val idx = configs.indexOfFirst { it.id == updated.id }
        if (idx != -1) {
            val oldConfig = configs[idx].copy()
            configs[idx] = updated
            saveConfigs(configs)
            active[updated.id]?.let { entry ->
                entry.root.setBackgroundColor(updated.bgColor)
                val dp = context.resources.displayMetrics.density
                entry.params.width = (updated.width * dp * updated.scale).toInt()
                entry.params.height = (updated.height * dp * updated.scale).toInt()
                entry.params.x = updated.posX
                entry.params.y = updated.posY
                entry.root.scaleX = updated.scale
                entry.root.scaleY = updated.scale
                entry.gesture.currentScale = updated.scale

                try { wm.updateViewLayout(entry.root, entry.params) } catch(_:Exception){}

                // Update auto-hide state if changed
                if (updated.autoHide != oldConfig.autoHide || updated.durationSec != oldConfig.durationSec) {
                    if (updated.autoHide) {
                        // Trigger immediate check/hide timer if currently visible
                        if (entry.root.visibility == View.VISIBLE) {
                            setInternalVisibility(updated.id, true)
                        }
                    } else {
                        // Disable auto-hide: cancel job and force show
                        entry.hideJob?.cancel()
                        setInternalVisibility(updated.id, true)
                    }
                }
            }
        }
    }

    fun isEnabled(id: String) = active.containsKey(id)

    fun startWidget(id: String) {
        if (isEnabled(id)) return
        val config = getConfigs().find { it.id == id } ?: return
        if (config.url.isBlank()) return
        
        val dp = context.resources.displayMetrics.density
        val entry = buildEntry(config, (config.width * dp).toInt(), (config.height * dp).toInt())
        active[id] = entry
        onWidgetVisibilityChanged?.invoke(id, true)
        
        // Initial force show so user sees it loading
        setInternalVisibility(id, true)
    }

    fun stopWidget(id: String) {
        val entry = active.remove(id) ?: return
        entry.hideJob?.cancel()
        entry.webView.destroy()
        runCatching { wm.removeView(entry.root) }
        if (active.isEmpty()) hideAdjuster()
        onWidgetVisibilityChanged?.invoke(id, false)
    }

    fun toggleWidget(id: String): Boolean =
        if (isEnabled(id)) { stopWidget(id); false }
        else                   { startWidget(id); true  }

    fun hideAll() { active.keys.toList().forEach { stopWidget(it) } }
    fun reloadAll() {
        val was = active.keys.toList()
        was.forEach { stopWidget(it) }
        was.forEach { startWidget(it) }
    }

    private fun setInternalVisibility(id: String, visible: Boolean) {
        val entry = active[id] ?: return
        val config = entry.config

        scope.launch(Dispatchers.Main) {
            if (visible) {
                entry.root.visibility = View.VISIBLE
                entry.root.alpha = 1.0f
                entry.params.flags = entry.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                
                if (config.autoHide) {
                    entry.hideJob?.cancel()
                    entry.hideJob = scope.launch {
                        delay(config.durationSec * 1000L)
                        checkMediaAndHide(id)
                    }
                }
            } else {
                // Truly hide from layout but keep WebView alive
                entry.root.alpha = 0.0f
                entry.root.visibility = View.GONE
                entry.params.flags = entry.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try { wm.updateViewLayout(entry.root, entry.params) } catch(e:Exception){}
        }
    }

    private fun checkMediaAndHide(id: String) {
        val entry = active[id] ?: return
        val config = entry.config
        
        entry.webView.evaluateJavascript("(function() { " +
            "const media = document.querySelectorAll('audio, video'); " +
            "let isPlaying = false; " +
            "media.forEach(m => { if(!m.paused && !m.ended && m.readyState > 2) isPlaying = true; }); " +
            "return isPlaying; " +
            "})()") { result ->
            if (result == "false") {
                setInternalVisibility(id, false)
            } else {
                // Still playing, re-check in 1s
                entry.hideJob = scope.launch {
                    delay(1000)
                    checkMediaAndHide(id)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun buildEntry(
        config: CustomOverlayConfig,
        widthPx: Int,
        heightPx: Int
    ): OverlayEntry {

        val themed = ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val root = LayoutInflater.from(themed).inflate(R.layout.overlay_saweria_widget, null)

        val wv = root.findViewById<WebView>(R.id.saweria_webview).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            setBackgroundColor(0x00000000)
            
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun triggerShow() { 
                    Log.d("CustomOverlay", "Wake up triggered for ${config.name}")
                    setInternalVisibility(config.id, true) 
                }
            }, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    v?.evaluateJavascript("document.body.style.background='transparent';document.documentElement.style.background='transparent';", null)
                    
                    val script = """
                        (function() {
                            const wake = () => { AndroidBridge.triggerShow(); };
                            
                            // 1. Hijack HTMLAudioElement.prototype.play
                            const originalPlay = HTMLAudioElement.prototype.play;
                            HTMLAudioElement.prototype.play = function() {
                                wake();
                                return originalPlay.apply(this, arguments);
                            };

                            // 2. Hijack HTMLVideoElement.prototype.play
                            const originalVideoPlay = HTMLVideoElement.prototype.play;
                            HTMLVideoElement.prototype.play = function() {
                                wake();
                                return originalVideoPlay.apply(this, arguments);
                            };
                            
                            // 3. Hijack AudioContext
                            if (window.AudioContext || window.webkitAudioContext) {
                                const AC = window.AudioContext || window.webkitAudioContext;
                                const originalResume = AC.prototype.resume;
                                AC.prototype.resume = function() {
                                    wake();
                                    return originalResume.apply(this, arguments);
                                };
                            }

                            // 4. Mutation Observer for visual changes
                            // Throttled to avoid excessive calls
                            let lastWake = 0;
                            const observer = new MutationObserver((mutations) => { 
                                const now = Date.now();
                                if (now - lastWake > 500) {
                                    wake();
                                    lastWake = now;
                                }
                            });
                            observer.observe(document.body, { childList: true, subtree: true, attributes: true });
                            
                            wake(); // Show on initial load
                        })();
                    """.trimIndent()
                    v?.evaluateJavascript(script, null)
                }
            }
            loadUrl(config.url)
        }

        root.findViewById<ImageButton>(R.id.saweria_btn_close).setOnClickListener { stopWidget(config.id) }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            (widthPx * config.scale).toInt(), (heightPx * config.scale).toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { 
            it.gravity = Gravity.TOP or Gravity.START
            it.x = config.posX
            it.y = config.posY
        }

        root.setBackgroundColor(config.bgColor)

        val gesture = OverlayGestureHelper(rootView = root, params = params, wm = wm, onSingleTap = {
            showAdjuster(config.id)
        }).apply {
            onInteraction = {
                config.posX = params.x
                config.posY = params.y
                updateConfig(config)
            }
        }
        
        root.pivotX = 0f
        root.pivotY = 0f
        root.scaleX = config.scale
        root.scaleY = config.scale
        gesture.currentScale = config.scale

        root.findViewById<View>(R.id.saweria_gesture_layer).setOnTouchListener(gesture)

        wm.addView(root, params)
        return OverlayEntry(config, root, wv, params, gesture)
    }

    fun showAdjuster(id: String) {
        hideAdjuster()
        val entry = active[id] ?: return
        val config = entry.config
        
        setInternalVisibility(id, true)
        
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

        tvTitle.text = "Adjust: ${config.name}"
        
        val dp = context.resources.displayMetrics.density
        var currW = config.width
        var currH = config.height
        var currS = config.scale
        
        var currAlpha = Color.alpha(config.bgColor)
        var currRGB   = config.bgColor and 0x00FFFFFF

        skW.progress = currW; tvW.text = "Width: ${currW}dp"
        skH.progress = currH; tvH.text = "Height: ${currH}dp"
        skS.progress = (currS * 100).toInt(); tvS.text = "Scale: %.2fx".format(currS)
        skA.progress = currAlpha; tvA.text = "BG Opacity: ${(currAlpha * 100 / 255)}%"

        val update = {
            config.width = currW
            config.height = currH
            config.scale = currS
            config.posX = entry.params.x
            config.posY = entry.params.y
            
            entry.params.width = (currW * dp * currS).toInt()
            entry.params.height = (currH * dp * currS).toInt()
            entry.root.pivotX = 0f
            entry.root.pivotY = 0f
            entry.root.scaleX = currS
            entry.root.scaleY = currS
            entry.gesture.currentScale = currS
            
            val finalColor = (currAlpha shl 24) or currRGB
            config.bgColor = finalColor
            entry.root.setBackgroundColor(finalColor)
            
            updateConfig(config)
            
            try { wm.updateViewLayout(entry.root, entry.params) } catch(e:Exception){}
        }

        skW.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if(b) { currW = p.coerceAtLeast(50); tvW.text = "Width: ${currW}dp"; update() } }
            override fun onStartTrackingTouch(p: SeekBar?) {}
            override fun onStopTrackingTouch(p: SeekBar?) {}
        })
        skH.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if(b) { currH = p.coerceAtLeast(50); tvH.text = "Height: ${currH}dp"; update() } }
            override fun onStartTrackingTouch(p: SeekBar?) {}
            override fun onStopTrackingTouch(p: SeekBar?) {}
        })
        skS.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if(b) { currS = p.coerceAtLeast(30) / 100f; tvS.text = "Scale: %.2fx".format(currS); update() } }
            override fun onStartTrackingTouch(p: SeekBar?) {}
            override fun onStopTrackingTouch(p: SeekBar?) {}
        })
        skA.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if(b) { currAlpha = p; tvA.text = "BG Opacity: ${(p * 100 / 255)}%"; update() } }
            override fun onStartTrackingTouch(p: SeekBar?) {}
            override fun onStopTrackingTouch(p: SeekBar?) {}
        })

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
}
