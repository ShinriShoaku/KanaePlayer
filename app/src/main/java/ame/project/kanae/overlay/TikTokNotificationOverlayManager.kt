package ame.project.kanae.overlay

import android.content.Context
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
import com.bumptech.glide.Glide

class TikTokNotificationOverlayManager(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null

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

    var isShowing = false
    private set

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

    private fun applyGain(gainMb: Int) {
        try {
            loudnessEnhancer?.setTargetGain(gainMb)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showNotification(userName: String, action: String, type: String, isDummy: Boolean = false, persistent: Boolean = false, giftIconUrl: String? = null) {
        if (rootView == null) {
            setupView()
        }

        tvUser?.text = userName
        tvAction?.text = action

        val imageUri = if (type == "gift") giftImageUri else shareImageUri
        val audioUri = if (type == "gift") giftAudioUri else shareAudioUri

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
                wm.addView(rootView, layoutParams)
                isShowing = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (persistent) {
            handler.removeCallbacks(hideRunnable)
        } else if (autoHideEnabled) {
            resetHideTimer()
        }
    }

    fun applyConfig(x: Int, y: Int, scale: Float, wDp: Int = 0, hDp: Int = 0) {
        if (rootView == null) setupView()
        val lp = layoutParams ?: return
        val view = rootView ?: return
        
        lp.x = x
        lp.y = y
        
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = scale
        view.scaleY = scale

        val density = context.resources.displayMetrics.density
        val baseW = if (wDp > 0) (wDp * density).toInt() else -2 // WRAP_CONTENT
        val baseH = if (hDp > 0) (hDp * density).toInt() else -2

        // Measure to get actual size if WRAP_CONTENT
        view.measure(
            if (baseW > 0) View.MeasureSpec.makeMeasureSpec(baseW, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            if (baseH > 0) View.MeasureSpec.makeMeasureSpec(baseH, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        val actualW = if (baseW > 0) baseW else view.measuredWidth
        val actualH = if (baseH > 0) baseH else view.measuredHeight

        lp.width = (actualW * scale).toInt()
        lp.height = (actualH * scale).toInt()
        
        gestureHelper?.let {
            it.currentScale = scale
            it.updateBaseSize(actualW, actualH)
        }
        
        try { wm.updateViewLayout(rootView, lp) } catch (_: Exception) {}
    }

    private fun setupView() {
        rootView = LayoutInflater.from(context).inflate(R.layout.overlay_tiktok_notification, null)
        ivImage = rootView?.findViewById(R.id.tiktok_notif_image)
        tvUser = rootView?.findViewById(R.id.tiktok_notif_user)
        tvAction = rootView?.findViewById(R.id.tiktok_notif_action)

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

        gestureHelper = OverlayGestureHelper(rootView!!, layoutParams!!, wm).apply {
            onInteraction = { resetHideTimer() }
        }
        rootView?.setOnTouchListener(gestureHelper)
    }

    fun hide() {
        if (isShowing && rootView != null) {
            try {
                wm.removeView(rootView)
                isShowing = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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

    private fun overlayWindowType(): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }
}
