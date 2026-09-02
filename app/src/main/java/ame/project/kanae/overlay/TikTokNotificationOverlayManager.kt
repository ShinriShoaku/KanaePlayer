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

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import ame.project.kanae.R
import ame.project.kanae.SettingsManager
import ame.project.kanae.model.CustomTheme
import com.bumptech.glide.Glide

class TikTokNotificationOverlayManager(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var windowView: android.widget.FrameLayout? = null
    private var contentView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null

    private var lastX: Int = 100
    private var lastY: Int = 100
    private var lastScale: Float = 1.0f

    var onPositionChanged: ((x: Int, y: Int, scale: Float) -> Unit)? = null

    private var ivImage: ImageView? = null
    private var tvUser: TextView? = null
    private var tvAction: TextView? = null

    private var autoHideEnabled = true
    private var displayDurationMs = 5000L
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    private var shareImageUri: Uri? = null
    private var giftImageUri: Uri? = null
    private var shareAudioUri: Uri? = null
    private var giftAudioUri: Uri? = null
    private var joinAudioUri: Uri? = null
    private var followAudioUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var notificationVolume: Float = 1.0f
    private var useTiktokGiftIcon: Boolean = true
    private var useCustomGiftSound: Boolean = false
    private var currentTheme: CustomTheme = CustomTheme()
    private var currentLayoutId: Int = R.layout.overlay_tiktok_notification

    var isShowing = false
        private set

    fun applyTheme(theme: CustomTheme) {
        this.currentTheme = theme
        val view = contentView ?: return
        val bgAlpha = theme.alpha

        theme.bgPrimary?.let { color ->
            val colorWithAlpha = Color.argb(bgAlpha, Color.red(color), Color.green(color), Color.blue(color))
            view.background?.let { bg ->
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(bg.mutate())
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, colorWithAlpha)
                view.background = wrapped
            } ?: run {
                view.setBackgroundColor(colorWithAlpha)
            }
        } ?: run {
            view.background?.mutate()?.alpha = bgAlpha
        }

        theme.textPrimary?.let { color ->
            tvUser?.setTextColor(color)
            tvAction?.setTextColor(color)
        }
    }

    fun setVisualPunchEnabled(enabled: Boolean) {
        if (enabled) Log.w("NotifOverlay", "Visual punch is not supported for the notif overlay; ignoring.")
    }

    fun updateStyle(layoutId: Int) {
        if (currentLayoutId != layoutId) {
            currentLayoutId = layoutId
            if (isShowing) {
                handler.post {
                    val wasShowing = isShowing
                    hide()
                    if (wasShowing) showNotification("Preview User", "Action Preview", "gift", isDummy = true)
                }
            } else {
                windowView = null
            }
        }
    }

    fun setVolume(volume: Float) {
        notificationVolume = volume
        val actualVol = if (volume > 1.0f) 1.0f else volume
        mediaPlayer?.setVolume(actualVol, actualVol)
        if (volume > 1.0f) {
            applyGain(((volume - 1.0f) * 2000).toInt())
        } else {
            applyGain(0)
        }
    }

    fun setUseTiktokGiftIcon(enabled: Boolean) { useTiktokGiftIcon = enabled }
    fun setUseCustomGiftSound(enabled: Boolean) { useCustomGiftSound = enabled }

    private fun applyGain(gainMb: Int) {
        try { loudnessEnhancer?.setTargetGain(gainMb) } catch (_: Exception) {}
    }

    private val mediaPlayers = mutableMapOf<String, MediaPlayer>()
    private val enhancers = mutableMapOf<String, LoudnessEnhancer>()
    private val lastPlayTime = mutableMapOf<String, Long>()
    private val SOUND_COOLDOWN = 300L // ms

    fun playNotificationSound(type: String) {
        val now = System.currentTimeMillis()
        if (now - (lastPlayTime[type] ?: 0L) < SOUND_COOLDOWN) return
        lastPlayTime[type] = now

        val audioUri = when (type) {
            "gift" -> giftAudioUri
            "join" -> joinAudioUri
            "follow" -> followAudioUri
            else -> shareAudioUri
        }
        audioUri?.let { playAudioInternal(it, type) }
    }

    private fun playAudioInternal(uri: Uri, type: String) {
        try {
            // Release previous player for this specific type to avoid OOM
            mediaPlayers[type]?.let {
                it.stop()
                it.release()
            }
            enhancers[type]?.let {
                it.release()
            }

            val mp = MediaPlayer()
            mp.setDataSource(context, uri)
            
            // Set volume & Enhancer
            val actualVol = if (notificationVolume > 1.0f) 1.0f else notificationVolume
            mp.setVolume(actualVol, actualVol)
            
            mp.setOnPreparedListener { player ->
                val sessionId = player.audioSessionId
                if (sessionId != 0 && notificationVolume > 1.0f) {
                    val enhancer = LoudnessEnhancer(sessionId)
                    enhancer.setTargetGain(((notificationVolume - 1.0f) * 2000).toInt())
                    enhancer.enabled = true
                    enhancers[type] = enhancer
                }
                player.start()
            }

            mp.setOnCompletionListener { player ->
                enhancers[type]?.release()
                enhancers.remove(type)
                player.release()
                if (mediaPlayers[type] == player) mediaPlayers.remove(type)
            }

            mp.setOnErrorListener { player, _, _ ->
                player.release()
                mediaPlayers.remove(type)
                true
            }

            mediaPlayers[type] = mp
            mp.prepareAsync()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun showNotification(userName: String, action: String, type: String, isDummy: Boolean = false, persistent: Boolean = false, giftIconUrl: String? = null, giftName: String? = null, giftId: Int? = null) {
        if (windowView == null) setupView()

        tvUser?.text = userName
        tvAction?.text = action

        var imageUri = if (type == "gift") giftImageUri else shareImageUri
        var audioUri: Uri? = null // Handled by playNotificationSound if needed

        if (type == "gift" && useCustomGiftSound) {
            val settingsManager = SettingsManager.getInstance(context)
            var customAudio: String? = null
            
            if (giftId != null && giftId != 0) {
                val nameFromId = findGiftNameById(giftId)
                if (nameFromId != null) {
                    customAudio = settingsManager.settings.giftSounds.find { it.giftName == nameFromId.trim() }?.soundUri
                }
            }
            
            if (customAudio == null && giftName != null) {
                customAudio = settingsManager.settings.giftSounds.find { it.giftName == giftName.trim() }?.soundUri
            }
            
            if (customAudio != null) {
                audioUri = Uri.parse(customAudio)
            }
        }

        ivImage?.let {
            if (type == "gift" && useTiktokGiftIcon && !giftIconUrl.isNullOrEmpty()) {
                Glide.with(context).load(giftIconUrl).into(it)
            } else if (imageUri != null) {
                Glide.with(context).load(imageUri).into(it)
            } else {
                it.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        if (!isDummy) {
            if (type == "gift" && audioUri != null) {
                // For custom gift sounds, play directly
                playAudioInternal(audioUri, "gift_custom_$giftId")
            } else {
                playNotificationSound(type)
            }
        }

        if (!isShowing) {
            try {
                windowView?.parent?.let { (it as android.view.ViewGroup).removeView(windowView) }
                applyConfigInternal(lastX, lastY, lastScale)
                wm.addView(windowView, layoutParams)
                isShowing = true
            } catch (_: Exception) {}
        }

        if (persistent) handler.removeCallbacks(hideRunnable)
        else if (autoHideEnabled) resetHideTimer()
    }

    fun applyConfig(x: Int, y: Int, scale: Float) {
        handler.post { lastX = x; lastY = y; lastScale = scale; applyConfigInternal(x, y, scale) }
    }

    private fun applyConfigInternal(x: Int, y: Int, scale: Float) {
        if (windowView == null) setupView()
        val lp = layoutParams ?: return
        val content = contentView ?: return
        lp.x = x; lp.y = y
        content.pivotX = 0f; content.pivotY = 0f; content.scaleX = scale; content.scaleY = scale
        content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val actualW = content.measuredWidth; val actualH = content.measuredHeight
        val contentLp = content.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (contentLp != null) { contentLp.width = actualW; contentLp.height = actualH; content.layoutParams = contentLp }
        lp.width = (actualW * scale).toInt().coerceAtLeast(1); lp.height = (actualH * scale).toInt().coerceAtLeast(1)
        gestureHelper?.let { it.currentScale = scale; it.updateBaseSize(actualW, actualH) }
        if (isShowing) try { wm.updateViewLayout(windowView, lp) } catch (_: Exception) {}
    }

    private fun setupView() {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val container = android.widget.FrameLayout(themed)
        val content = LayoutInflater.from(themed).inflate(currentLayoutId, container, false)
        container.addView(content)
        windowView = container; contentView = content
        ivImage = content.findViewById(R.id.tiktok_notif_image); tvUser = content.findViewById(R.id.tiktok_notif_user); tvAction = content.findViewById(R.id.tiktok_notif_action)
        applyTheme(currentTheme)
        layoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayWindowType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 100 }
        gestureHelper = OverlayGestureHelper(container, layoutParams!!, wm).apply { onInteraction = { resetHideTimer(); lastX = layoutParams?.x ?: lastX; lastY = layoutParams?.y ?: lastY; lastScale = currentScale; onPositionChanged?.invoke(lastX, lastY, lastScale) } }
        container.setOnTouchListener(gestureHelper)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        if (isShowing && windowView != null) try { wm.removeView(windowView) } catch (_: Exception) {}
        
        mediaPlayers.values.forEach { 
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        mediaPlayers.clear()
        enhancers.values.forEach { 
            try { it.release() } catch (_: Exception) {}
        }
        enhancers.clear()

        isShowing = false; windowView = null; contentView = null; ivImage = null; tvUser = null; tvAction = null; gestureHelper = null; layoutParams = null
    }

    fun resetHideTimer() {
        if (!isShowing) return
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, displayDurationMs)
    }

    fun setConfig(shareImg: String?, giftImg: String?, shareAud: String?, giftAud: String?, joinAud: String?, followAud: String?, duration: Int) {
        shareImageUri = shareImg?.let { Uri.parse(it) }
        giftImageUri = giftImg?.let { Uri.parse(it) }
        shareAudioUri = shareAud?.let { Uri.parse(it) }
        giftAudioUri = giftAud?.let { Uri.parse(it) }
        joinAudioUri = joinAud?.let { Uri.parse(it) }
        followAudioUri = followAud?.let { Uri.parse(it) }
        displayDurationMs = duration * 1000L
    }

    private var giftCache: Map<Int, String>? = null
    private fun findGiftNameById(id: Int): String? { if (giftCache == null) loadGiftCache(); return giftCache?.get(id) }
    private fun loadGiftCache() {
        val cache = mutableMapOf<Int, String>()
        try {
            val inputStream = context.assets.open("tiktok_gifts.json")
            val reader = java.io.InputStreamReader(inputStream)
            val jsonObject = com.google.gson.Gson().fromJson(reader, com.google.gson.JsonObject::class.java)
            val giftsArray = jsonObject.getAsJsonArray("gifts")
            giftsArray.forEach {
                val obj = it.asJsonObject; val name = obj.get("name")?.asString ?: "Gift"; val idElement = obj.get("id") ?: return@forEach
                if (idElement.isJsonArray) { val ids = idElement.asJsonArray; for (i in 0 until ids.size()) cache[ids[i].asInt] = name }
                else if (idElement.isJsonPrimitive) cache[idElement.asInt] = name
            }
            giftCache = cache
        } catch (_: Exception) {}
    }

    private fun overlayWindowType(): Int = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
