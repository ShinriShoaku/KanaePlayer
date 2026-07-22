/*
 * KanaePlayer -
 * Copyright (C) 2026 KanaePlayer Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed WITHOUT ANY WARRANTY; see the
 * GNU General Public License for more details: <https://www.gnu.org/licenses/>.
 */

package ame.project.kanae.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.*
import android.webkit.WebSettings
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import ame.project.kanae.R
import ame.project.kanae.SettingsManager
import com.google.gson.Gson
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
    var bgColor: Int = 0xFF1A1A2E.toInt(), // Default Opaque Dark Blue
    var autoHide: Boolean = false,
    var durationSec: Int = 5,
    var visualPunch: Boolean = false
)

class CustomOverlayManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "CustomOverlayMgr"
    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settingsManager = SettingsManager.getInstance(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private data class OverlayEntry(
        var config: CustomOverlayConfig,
        val root: View,
        val punchLayout: PunchThroughLayout,
        val gestureLayer: View,
        val webView: WebView,
        val params: WindowManager.LayoutParams,
        val gesture: OverlayGestureHelper,
        val bridgeName: String,
        var hideJob: Job? = null,
        var isUiVisible: Boolean = true
    )

    private val active = mutableMapOf<String, OverlayEntry>()
    private var adjusterRoot: View? = null

    var onWidgetVisibilityChanged: ((String, Boolean) -> Unit)? = null
    var onConfigUpdated: ((CustomOverlayConfig) -> Unit)? = null

    fun getConfigs(): MutableList<CustomOverlayConfig> = settingsManager.settings.customWebConfigs

    fun saveConfigs(configs: List<CustomOverlayConfig>) {
        settingsManager.settings.customWebConfigs = configs.toMutableList()
        settingsManager.saveSettings()
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
            onConfigUpdated?.invoke(updated)

            active[updated.id]?.let { entry ->
                val needsReload = updated.url != oldConfig.url
                entry.config = updated.copy()
                entry.root.setBackgroundColor(updated.bgColor)
                val dp = context.resources.displayMetrics.density
                
                // Update target resolution in PunchThroughLayout
                entry.punchLayout.targetWidth = (updated.width * dp).toInt()
                entry.punchLayout.targetHeight = (updated.height * dp).toInt()
                
                entry.params.width = (updated.width * dp * updated.scale).toInt()
                entry.params.height = (updated.height * dp * updated.scale).toInt()
                val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

                if (entry.isUiVisible) {
                    entry.params.x = updated.posX
                    entry.params.y = updated.posY
                    entry.params.flags = baseFlags
                } else {
                    entry.params.x = -10000
                    entry.params.flags = baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                
                entry.root.scaleX = updated.scale
                entry.root.scaleY = updated.scale
                entry.punchLayout.currentScale = updated.scale
                entry.gesture.currentScale = updated.scale
                entry.gesture.updateBaseSize((updated.width * dp).toInt(), (updated.height * dp).toInt())
                entry.punchLayout.punchEnabled = updated.visualPunch
                entry.gestureLayer.setOnTouchListener(if (updated.visualPunch) null else entry.gesture)

                try { wm.updateViewLayout(entry.root, entry.params) } catch (_: Exception) {}
                if (needsReload) entry.webView.loadUrl(updated.url)

                if (updated.autoHide != oldConfig.autoHide || updated.durationSec != oldConfig.durationSec) {
                    if (updated.autoHide) {
                        if (entry.isUiVisible) scheduleHideTimer(updated.id)
                    } else {
                        entry.hideJob?.cancel()
                        entry.hideJob = null
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
        setInternalVisibility(id, true)
    }

    fun stopWidget(id: String) {
        val entry = active.remove(id) ?: return
        entry.hideJob?.cancel()
        entry.hideJob = null
        entry.webView.destroy()
        runCatching { wm.removeView(entry.root) }
        if (active.isEmpty()) hideAdjuster()
        onWidgetVisibilityChanged?.invoke(id, false)
    }

    fun toggleWidget(id: String): Boolean =
        if (isEnabled(id)) { stopWidget(id); false }
        else { startWidget(id); true }

    fun hideAll() { active.keys.toList().forEach { stopWidget(it) } }

    fun reloadAll() {
        val was = active.keys.toList()
        was.forEach { stopWidget(it) }
        was.forEach { startWidget(it) }
    }

    private fun setInternalVisibility(id: String, visible: Boolean) {
        scope.launch(Dispatchers.Main) {
            val e = active[id] ?: return@launch
            Log.d(TAG, "setInternalVisibility id=$id visible=$visible current=${e.isUiVisible}")
            
            if (visible) {
                e.isUiVisible = true
                e.hideJob?.cancel()
                
                // Pastikan posisi kembali ke awal (un-hide dari -10000)
                e.params.x = e.config.posX
                e.params.y = e.config.posY
                e.params.flags = e.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                
                try { wm.updateViewLayout(e.root, e.params) } catch (_: Exception) {}
                
                if (e.root.visibility == View.VISIBLE && e.root.alpha >= 1.0f) {
                    if (e.config.autoHide) scheduleHideTimer(id)
                    return@launch
                }
                
                e.root.visibility = View.VISIBLE
                e.root.animate().cancel()
                e.root.animate().alpha(1.0f).setDuration(400).setListener(null).start()
                e.webView.onResume()
                
                if (e.config.autoHide) scheduleHideTimer(id)
            } else {
                if (!e.isUiVisible) return@launch
                e.isUiVisible = false
                e.hideJob?.cancel()
                e.hideJob = null
                
                e.root.animate().cancel()
                e.root.animate().alpha(0.0f).setDuration(600).withEndAction {
                    // Cek lagi apakah masih dalam state hide (mencegah race condition jika keburu di-show lagi)
                    if (!e.isUiVisible) {
                        e.params.flags = e.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        e.params.x = -10000 
                        try { wm.updateViewLayout(e.root, e.params) } catch (_: Exception) {}
                        Log.d(TAG, "Overlay $id moved off-screen (-10000)")
                    }
                }.start()
            }
        }
    }

    private fun scheduleHideTimer(id: String) {
        val entry = active[id] ?: return
        entry.hideJob?.cancel()
        val durationMs = entry.config.durationSec.coerceAtLeast(1) * 1000L
        entry.hideJob = scope.launch {
            delay(durationMs)
            val e = active[id] ?: return@launch
            if (!e.config.autoHide) return@launch
            checkMediaAndHide(id)
        }
    }

    private fun checkMediaAndHide(id: String) {
        scope.launch(Dispatchers.Main) {
            val entry = active[id] ?: return@launch
            if (entry.root.alpha == 0f && !entry.config.autoHide) return@launch
            entry.webView.evaluateJavascript("window.__isBusy()") { result ->
                val e = active[id] ?: return@evaluateJavascript
                if (!e.config.autoHide) return@evaluateJavascript
                if (result == null || result == "null") {
                    e.hideJob?.cancel()
                    e.hideJob = scope.launch { delay(2000); checkMediaAndHide(id) }
                    return@evaluateJavascript
                }
                val status = result.replace("\"", "")
                if (status == "false") setInternalVisibility(id, false)
                else {
                    e.hideJob?.cancel()
                    e.hideJob = scope.launch { delay(1000); checkMediaAndHide(id) }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun buildEntry(config: CustomOverlayConfig, widthPx: Int, heightPx: Int): OverlayEntry {
        val themed = ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val root = LayoutInflater.from(themed).inflate(R.layout.overlay_custom_widget, null)
        val punchLayout = root.findViewById<PunchThroughLayout>(R.id.punch_layout)
        val gestureLayer = root.findViewById<View>(R.id.custom_gesture_layer)
        val entryCfg = config.copy()
        punchLayout.targetWidth = widthPx
        punchLayout.targetHeight = heightPx
        punchLayout.punchEnabled = entryCfg.visualPunch
        val uniqueBridgeName = "AndroidBridge_${entryCfg.id}"
        val bridgeGuardKey = "__bridge_${entryCfg.id}_installed"

        val wv = root.findViewById<WebView>(R.id.custom_webview).apply {
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW; cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true; loadWithOverviewMode = true; databaseEnabled = true; javaScriptCanOpenWindowsAutomatically = true
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(0x00000000)
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface fun triggerShow() { setInternalVisibility(entryCfg.id, true) }
                @android.webkit.JavascriptInterface fun log(msg: String) { Log.d(TAG, "[JS_${entryCfg.name}] $msg") }
            }, uniqueBridgeName)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    v?.evaluateJavascript("document.body.style.background='transparent';document.documentElement.style.background='transparent';", null)
                    val script = """
                        (function() {
                            if (window['$bridgeGuardKey']) return;
                            window['$bridgeGuardKey'] = true;
                            const bridge = window['$uniqueBridgeName'];
                            let lastWakeTime = 0;
                            const wake = (reason) => {
                                const now = Date.now();
                                if (now - lastWakeTime < 1000) return;
                                lastWakeTime = now;
                                if (typeof bridge !== 'undefined' && bridge !== null) bridge.triggerShow();
                            };
                            window.__activeMedia = window.__activeMedia || new Set();
                            const trackMedia = (m) => {
                                m.addEventListener('play', () => { window.__activeMedia.add(m); wake("media_play"); });
                                m.addEventListener('pause', () => { if (window.__isBusy() !== 'false') { setTimeout(() => { if (!m.ended) m.play().catch(e => {}); }, 1000); } window.__activeMedia.delete(m); });
                                m.addEventListener('ended', () => { window.__activeMedia.delete(m); });
                                if (!m.paused && !m.ended) window.__activeMedia.add(m);
                            };
                            const originalPlay = HTMLAudioElement.prototype.play;
                            HTMLAudioElement.prototype.play = function() { trackMedia(this); wake("audio_proto_play"); return originalPlay.apply(this, arguments); };
                            const originalVideoPlay = HTMLVideoElement.prototype.play;
                            HTMLVideoElement.prototype.play = function() { trackMedia(this); wake("video_proto_play"); return originalVideoPlay.apply(this, arguments); };
                            document.querySelectorAll('audio,video').forEach(trackMedia);
                            window.__activeAudioContexts = window.__activeAudioContexts || new Set();
                            if (window.AudioContext || window.webkitAudioContext) {
                                const AC = window.AudioContext || window.webkitAudioContext;
                                const originalResume = AC.prototype.resume;
                                AC.prototype.resume = function() { window.__activeAudioContexts.add(this); wake("audiocontext_resume"); return originalResume.apply(this, arguments); };
                            }
                            let lastActivity = Date.now();
                            const observer = new MutationObserver((mutations) => {
                                let meaningful = false;
                                for(let m of mutations) { if(m.addedNodes.length > 0 || m.removedNodes.length > 0) { meaningful = true; break; } }
                                if(meaningful) { lastActivity = Date.now(); wake("dom_mutation"); }
                            });
                            observer.observe(document.body, { childList: true, subtree: true });
                            window.__isBusy = function() {
                                try {
                                    const m = document.querySelectorAll('audio,video');
                                    let isAnyPlaying = false;
                                    for(let i=0; i<m.length; i++) { if(!m[i].paused && !m[i].ended && m[i].readyState >= 1) { isAnyPlaying = true; break; } }
                                    if (isAnyPlaying) return "Media_DOM_Active";
                                    if(window.__activeMedia && window.__activeMedia.size > 0) {
                                        let trackedPlaying = false;
                                        window.__activeMedia.forEach(am => { if(!am.paused && !am.ended) trackedPlaying = true; });
                                        if(trackedPlaying) return "Media_Tracked";
                                    }
                                    const iframes = document.querySelectorAll('iframe');
                                    for(let i=0; i<iframes.length; i++) {
                                        const s = window.getComputedStyle(iframes[i]);
                                        if(s.display !== 'none' && s.visibility !== 'hidden' && parseFloat(s.opacity) > 0.1) {
                                            if(iframes[i].offsetWidth > 30 && iframes[i].offsetHeight > 30) {
                                                try { iframes[i].contentWindow.postMessage('{"event":"command","func":"playVideo","args":""}', '*'); } catch(e){}
                                                return "Iframe_Active";
                                            }
                                        }
                                    }
                                    const idleSec = (Date.now() - lastActivity) / 1000;
                                    if(idleSec < 12.0) return "Active_" + idleSec.toFixed(0) + "s";
                                    return "false";
                                } catch(e) { return "Error_" + e.message; }
                            };
                            wake("init");
                        })();
                    """.trimIndent()
                    v?.evaluateJavascript(script, null)
                }
                override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {}
                override fun onRenderProcessGone(v: WebView?, d: RenderProcessGoneDetail?): Boolean = true
            }
            loadUrl(entryCfg.url)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams((widthPx * entryCfg.scale).toInt(), (heightPx * entryCfg.scale).toInt(), type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).also {
            it.gravity = Gravity.TOP or Gravity.START; it.x = entryCfg.posX; it.y = entryCfg.posY
        }
        root.setBackgroundColor(entryCfg.bgColor)
        val gesture = OverlayGestureHelper(rootView = root, params = params, wm = wm, onLongPress = { showAdjuster(entryCfg.id) }).apply {
            onInteraction = { 
                active[entryCfg.id]?.let { e -> 
                    if (e.isUiVisible && params.x != -10000) {
                        e.config.posX = params.x
                        e.config.posY = params.y
                        updateConfig(e.config) 
                    }
                } 
            }
        }
        root.pivotX = 0f; root.pivotY = 0f; root.scaleX = entryCfg.scale; root.scaleY = entryCfg.scale; gesture.currentScale = entryCfg.scale
        gesture.updateBaseSize(widthPx, heightPx)
        entryCfg.scale.let { punchLayout.currentScale = it }
        gestureLayer.setOnTouchListener(if (entryCfg.visualPunch) null else gesture)
        wm.addView(root, params)
        return OverlayEntry(entryCfg, root, punchLayout, gestureLayer, wv, params, gesture, uniqueBridgeName)
    }

    fun showAdjuster(id: String) {
        hideAdjuster()
        val entry = active[id] ?: return
        val config = entry.config
        entry.hideJob?.cancel()
        entry.hideJob = null
        scope.launch(Dispatchers.Main) {
            val e = active[id] ?: return@launch
            e.isUiVisible = true; e.root.visibility = View.VISIBLE; e.root.alpha = 1.0f
            val dp = context.resources.displayMetrics.density
            e.params.width = (e.config.width * dp * e.config.scale).toInt()
            e.params.height = (e.config.height * dp * e.config.scale).toInt()
            e.params.x = e.config.posX; e.params.y = e.config.posY
            e.params.flags = e.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { wm.updateViewLayout(e.root, e.params) } catch (_: Exception) {}
        }

        val themed = ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themed).inflate(R.layout.layout_custom_adjuster, null)
        adjusterRoot = view
        val skW = view.findViewById<SeekBar>(R.id.seek_width); val skH = view.findViewById<SeekBar>(R.id.seek_height)
        val skS = view.findViewById<SeekBar>(R.id.seek_scale); val skA = view.findViewById<SeekBar>(R.id.seek_alpha)
        val cbPunch = view.findViewById<CheckBox>(R.id.cb_visual_punch)
        val etName = view.findViewById<android.widget.EditText>(R.id.et_adj_name)
        val etUrl = view.findViewById<android.widget.EditText>(R.id.et_adj_url)
        val tvW = view.findViewById<TextView>(R.id.tv_width_label); val tvH = view.findViewById<TextView>(R.id.tv_height_label)
        val tvS = view.findViewById<TextView>(R.id.tv_scale_label); val tvA = view.findViewById<TextView>(R.id.tv_alpha_label)
        view.findViewById<TextView>(R.id.adj_title).text = "Adjust: ${config.name}"
        etName.setText(config.name); etUrl.setText(config.url)
        val dp = context.resources.displayMetrics.density
        var currW = config.width; var currH = config.height; var currS = config.scale; var currAlpha = Color.alpha(config.bgColor); var currRGB = config.bgColor and 0x00FFFFFF; var currPunch = config.visualPunch
        skW.progress = currW; tvW.text = "Width: ${currW}dp"; skH.progress = currH; tvH.text = "Height: ${currH}dp"
        skS.progress = (currS * 100).toInt(); tvS.text = "Scale: %.2fx".format(currS); skA.progress = currAlpha; tvA.text = "BG Opacity: ${(currAlpha * 100 / 255)}%"; cbPunch.isChecked = currPunch

        val update = let@{
            val e = active[id] ?: return@let
            e.config.name = etName.text.toString(); e.config.url = etUrl.text.toString(); e.config.width = currW; e.config.height = currH; e.config.scale = currS; e.config.posX = e.params.x; e.config.posY = e.params.y; e.config.visualPunch = currPunch
            e.params.width = (currW * dp * currS).toInt(); e.params.height = (currH * dp * currS).toInt()
            e.root.pivotX = 0f; e.root.pivotY = 0f; e.root.scaleX = currS; e.root.scaleY = currS; e.gesture.currentScale = currS
            e.punchLayout.currentScale = currS
            e.punchLayout.punchEnabled = currPunch; e.gestureLayer.setOnTouchListener(if (currPunch) null else e.gesture)
            val finalColor = (currAlpha shl 24) or currRGB; e.config.bgColor = finalColor; e.root.setBackgroundColor(finalColor)
            updateConfig(e.config); try { wm.updateViewLayout(e.root, e.params) } catch (_: Exception) {}
        }
        etName.setOnEditorActionListener { _, _, _ -> update(); false }; etUrl.setOnEditorActionListener { _, _, _ -> update(); false }
        skW.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if (b) { currW = p.coerceAtLeast(50); tvW.text = "Width: ${currW}dp"; update() } }; override fun onStartTrackingTouch(p: SeekBar?) {}; override fun onStopTrackingTouch(p: SeekBar?) {} })
        skH.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if (b) { currH = p.coerceAtLeast(50); tvH.text = "Height: ${currH}dp"; update() } }; override fun onStartTrackingTouch(p: SeekBar?) {}; override fun onStopTrackingTouch(p: SeekBar?) {} })
        skS.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if (b) { currS = p.coerceAtLeast(30) / 100f; tvS.text = "Scale: %.2fx".format(currS); update() } }; override fun onStartTrackingTouch(p: SeekBar?) {}; override fun onStopTrackingTouch(p: SeekBar?) {} })
        skA.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { if (b) { currAlpha = p; tvA.text = "BG Opacity: ${(p * 100 / 255)}%"; update() } }; override fun onStartTrackingTouch(p: SeekBar?) {}; override fun onStopTrackingTouch(p: SeekBar?) {} })
        cbPunch.setOnCheckedChangeListener { _, isChecked -> currPunch = isChecked; update() }
        val setRGB = { rgb: Int -> currRGB = rgb; update() }
        view.findViewById<View>(R.id.color_none).setOnClickListener { currAlpha = 0; skA.progress = 0; update() }
        view.findViewById<View>(R.id.color_black).setOnClickListener { setRGB(0x000000) }
        view.findViewById<View>(R.id.color_teal).setOnClickListener { setRGB(0x008080) }
        view.findViewById<View>(R.id.color_blue).setOnClickListener { setRGB(0x1A1A2E) }
        view.findViewById<View>(R.id.color_green).setOnClickListener { setRGB(0x00FF00) }
        view.findViewById<Button>(R.id.btn_close_adjuster).setOnClickListener { hideAdjuster(); active[id]?.let { e -> if (e.config.autoHide) scheduleHideTimer(id) } }
        val adjParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        wm.addView(view, adjParams)
    }

    private fun hideAdjuster() { adjusterRoot?.let { runCatching { wm.removeView(it) } }; adjusterRoot = null }
}
