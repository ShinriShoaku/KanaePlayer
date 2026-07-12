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
import ame.project.kanae.model.CustomTheme
import com.bumptech.glide.Glide

/**
 * NOTE: Notif overlay intentionally does NOT use [PunchThroughLayout].
 * PunchThroughLayout is only for player/queue/lyrics/chat overlays.
 * The notif root layout uses `android:elevation`, and combining that with
 * PunchThroughLayout's `draw()` override (which uses `canvas.saveLayer` +
 * PorterDuff.CLEAR to punch a transparent hole) triggered a native rendering
 * recursion crash (infinite loop in ViewGroup.resetResolvedLayoutDirection)
 * on attach. So here `rootView` is attached to the WindowManager directly.
 */
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

    /**
     * Visual "punch through" mode is not supported for the notif overlay
     * (see class doc). Kept as a no-op so callers (e.g. PlayerForegroundService's
     * generic `updateVisualPunchEnabled("notif", enabled)`) don't need special-casing.
     */
    fun setVisualPunchEnabled(enabled: Boolean) {
        if (enabled) {
            Log.w("NotifOverlay", "Visual punch is not supported for the notif overlay; ignoring.")
        }
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
                windowView = null // Recreate on next show
            }
        }
    }

    fun setVolume(volume: Float) {
        notificationVolume = volume
        val actualVol = if (volume > 1.0f) 1.0f else volume
        mediaPlayer?.setVolume(actualVol, actualVol)

        if (volume > 1.0f) {
            val gain = ((volume - 1.0f) * 2000).toInt() // Max +2000mB (20dB) gain at 200%
            applyGain(gain)
        } else {
            applyGain(0)
        }
    }

    fun setUseTiktokGiftIcon(enabled: Boolean) {
        useTiktokGiftIcon = enabled
    }

    fun setUseCustomGiftSound(enabled: Boolean) {
        useCustomGiftSound = enabled
    }

    private fun applyGain(gainMb: Int) {
        try {
            loudnessEnhancer?.setTargetGain(gainMb)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun showNotification(userName: String, action: String, type: String, isDummy: Boolean = false, persistent: Boolean = false, giftIconUrl: String? = null, giftName: String? = null, giftId: Int? = null) {
        if (windowView == null) {
            setupView()
        }

        tvUser?.text = userName
        tvAction?.text = action

        var imageUri = if (type == "gift") giftImageUri else shareImageUri
        var audioUri = if (type == "gift") giftAudioUri else shareAudioUri

        // Custom Gift Sound Logic with Fallback
        if (type == "gift" && useCustomGiftSound && (giftName != null || giftId != null)) {
            val prefs = context.getSharedPreferences("gift_sounds_prefs", Context.MODE_PRIVATE)
            
            var customAudio: String? = null
            
            // 1. Try matching by giftId first (new system)
            if (giftId != null && giftId != 0) {
                // We need to find the gift name associated with this ID from tiktok_gifts.json
                // OR we can just store sounds by ID in the future.
                // For now, let's see if we can find the gift name in the JSON.
                val nameFromId = findGiftNameById(giftId)
                if (nameFromId != null) {
                    customAudio = prefs.getString("gift_sound_${nameFromId.trim()}", null)
                }
            }
            
            // 2. Fallback to giftName matching
            if (customAudio == null && giftName != null) {
                val cleanName = giftName.trim()
                customAudio = prefs.getString("gift_sound_$cleanName", null)
            }
            
            if (customAudio != null) {
                Log.d("NotifOverlay", "Using custom sound for ${giftName ?: giftId}: $customAudio")
                audioUri = Uri.parse(customAudio)
            } else {
                Log.d("NotifOverlay", "No custom sound for ${giftName ?: giftId}, falling back to default gift audio")
            }
        }

        ivImage?.let {
            Log.d("NotifOverlay", "Showing $type. useTiktokGiftIcon=$useTiktokGiftIcon, giftIconUrl=$giftIconUrl")
            if (type == "gift" && useTiktokGiftIcon && !giftIconUrl.isNullOrEmpty()) {
                Log.d("NotifOverlay", "Loading TikTok Gift Icon: $giftIconUrl")
                Glide.with(context).load(giftIconUrl).into(it)
            } else if (imageUri != null) {
                Log.d("NotifOverlay", "Loading Custom Image: $imageUri")
                Glide.with(context).load(imageUri).into(it)
            } else {
                Log.d("NotifOverlay", "No image found, using default placeholder")
                it.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        if (!isDummy) {
            audioUri?.let {
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                    loudnessEnhancer?.release()

                    mediaPlayer = MediaPlayer.create(context, it)
                    val actualVol = if (notificationVolume > 1.0f) 1.0f else notificationVolume
                    mediaPlayer?.setVolume(actualVol, actualVol)

                    val sessionId = mediaPlayer?.audioSessionId ?: 0
                    if (sessionId != 0) {
                        loudnessEnhancer = LoudnessEnhancer(sessionId)
                        if (notificationVolume > 1.0f) {
                            val gain = ((notificationVolume - 1.0f) * 2000).toInt()
                            loudnessEnhancer?.setTargetGain(gain)
                        }
                        loudnessEnhancer?.enabled = true
                    }

                    mediaPlayer?.setOnCompletionListener { player ->
                        loudnessEnhancer?.release()
                        loudnessEnhancer = null
                        player.release()
                        if (mediaPlayer == player) mediaPlayer = null
                    }
                    mediaPlayer?.start()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (!isShowing) {
            try {
                // Defensive guard: if windowView somehow still has a stale parent
                val currentParent = windowView?.parent
                if (currentParent is android.view.ViewGroup) {
                    currentParent.removeView(windowView)
                }
                
                // Apply last known config before adding
                applyConfigInternal(lastX, lastY, lastScale)
                
                wm.addView(windowView, layoutParams)
                isShowing = true
            } catch (e: Exception) {
                Log.e("NotifOverlay", "Failed to attach notif overlay", e)
            }
        }

        if (persistent) {
            handler.removeCallbacks(hideRunnable)
        } else if (autoHideEnabled) {
            resetHideTimer()
        }
    }

    fun applyConfig(x: Int, y: Int, scale: Float) {
        handler.post {
            lastX = x
            lastY = y
            lastScale = scale
            applyConfigInternal(x, y, scale)
        }
    }

    private fun applyConfigInternal(x: Int, y: Int, scale: Float) {
        if (windowView == null) setupView()
        val lp = layoutParams ?: return
        val content = contentView ?: return

        lp.x = x
        lp.y = y

        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale

        // Selalu gunakan WRAP_CONTENT agar ukurannya pas dengan konten
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val actualW = content.measuredWidth
        val actualH = content.measuredHeight

        // FIX: Paksa ukuran content tetap di ukuran aslinya
        val contentLp = content.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (contentLp != null) {
            contentLp.width = actualW
            contentLp.height = actualH
            content.layoutParams = contentLp
        }

        lp.width = (actualW * scale).toInt().coerceAtLeast(1)
        lp.height = (actualH * scale).toInt().coerceAtLeast(1)

        gestureHelper?.let {
            it.currentScale = scale
            it.updateBaseSize(actualW, actualH)
        }

        if (isShowing) {
            try { wm.updateViewLayout(windowView, lp) } catch (_: Exception) {}
        }
    }

    private fun setupView() {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        
        // Wrap dalam FrameLayout agar scaling tidak merusak layout (re-wrapping)
        val container = android.widget.FrameLayout(themed)
        val content = LayoutInflater.from(themed).inflate(currentLayoutId, container, false)
        container.addView(content)
        
        windowView = container
        contentView = content

        ivImage = content.findViewById(R.id.tiktok_notif_image)
        tvUser = content.findViewById(R.id.tiktok_notif_user)
        tvAction = content.findViewById(R.id.tiktok_notif_action)

        applyTheme(currentTheme)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        gestureHelper = OverlayGestureHelper(container, layoutParams!!, wm).apply {
            onInteraction = { 
                resetHideTimer()
                lastX = layoutParams?.x ?: lastX
                lastY = layoutParams?.y ?: lastY
                lastScale = currentScale
                onPositionChanged?.invoke(lastX, lastY, lastScale)
            }
        }
        container.setOnTouchListener(gestureHelper)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        if (isShowing && windowView != null) {
            try {
                wm.removeView(windowView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Bersihkan resource audio
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            loudnessEnhancer?.release()
        } catch (e: Exception) {}
        
        mediaPlayer = null
        loudnessEnhancer = null
        isShowing = false
        
        windowView = null
        contentView = null
        ivImage = null
        tvUser = null
        tvAction = null
        gestureHelper = null
        layoutParams = null
    }

    fun resetHideTimer() {
        if (!isShowing) return
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, displayDurationMs)
    }

    fun setConfig(shareImg: String?, giftImg: String?, shareAud: String?, giftAud: String?, duration: Int) {
        shareImageUri = shareImg?.let { Uri.parse(it) }
        giftImageUri = giftImg?.let { Uri.parse(it) }
        shareAudioUri = shareAud?.let { Uri.parse(it) }
        giftAudioUri = giftAud?.let { Uri.parse(it) }
        displayDurationMs = duration * 1000L
    }

    private var giftCache: Map<Int, String>? = null

    private fun findGiftNameById(id: Int): String? {
        if (giftCache == null) {
            loadGiftCache()
        }
        return giftCache?.get(id)
    }

    private fun loadGiftCache() {
        val cache = mutableMapOf<Int, String>()
        try {
            val inputStream = context.assets.open("tiktok_gifts.json")
            val reader = java.io.InputStreamReader(inputStream)
            val jsonObject = com.google.gson.Gson().fromJson(reader, com.google.gson.JsonObject::class.java)
            val giftsArray = jsonObject.getAsJsonArray("gifts")
            
            giftsArray.forEach {
                val obj = it.asJsonObject
                val name = obj.get("name")?.asString ?: "Gift"
                val idElement = obj.get("id") ?: return@forEach
                if (idElement.isJsonArray) {
                    val ids = idElement.asJsonArray
                    for (i in 0 until ids.size()) {
                        cache[ids[i].asInt] = name
                    }
                } else if (idElement.isJsonPrimitive) {
                    cache[idElement.asInt] = name
                }
            }
            giftCache = cache
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun overlayWindowType(): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }
}