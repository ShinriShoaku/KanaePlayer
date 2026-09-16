package ame.project.kanae.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.drawable.DrawableCompat
import ame.project.kanae.R
import ame.project.kanae.model.CustomTheme
import ame.project.kanae.accessibility.KanaeAccessibilityService
import java.util.*

enum class AudioCaptureMode { STANDARD, ACCESSIBILITY }

class VoiceOverlayManager(
    private val context: Context,
    private val onClose: () -> Unit
) {
    private var wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var punchLayout: PunchThroughLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null
    private var tvVoiceText: TextView? = null
    private var ivIndicator: ImageView? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isOnline = true
    private var isContinuous = true
    private var voiceLanguage: String = "id-ID"
    private var displayDurationMs: Long = 5000
    private var textSizeSp: Float = 16f

    private var lastX: Int = 100
    private var lastY: Int = 800
    private var lastScale: Float = 1f
    private var overlayWidth: Int = 300
    
    var onPositionChanged: ((x: Int, y: Int, scale: Float) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideTextRunnable = Runnable { 
        tvVoiceText?.text = ""
        syncWindowSize()
    }

    private var currentTheme: CustomTheme = CustomTheme()
    var isShowing: Boolean = false
        private set

    private var visualPunchEnabled = false
    var audioCaptureMode: AudioCaptureMode = AudioCaptureMode.STANDARD

    fun setVisualPunchEnabled(enabled: Boolean) {
        this.visualPunchEnabled = enabled
        punchLayout?.punchEnabled = enabled
        updateOverlayFlags()
    }

    private fun updateOverlayFlags() {
        val params = layoutParams ?: return
        if (visualPunchEnabled) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (isShowing) {
            runCatching { wm.updateViewLayout(punchLayout, params) }
        }
    }

    private val voiceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ame.project.kanae.VOICE_RESULT" -> {
                    val text = intent.getStringExtra("text")
                    if (text != null) updateText(text)
                    if (isOnline && isContinuous && isShowing) startListening()
                }
                "ame.project.kanae.VOICE_PARTIAL_RESULT" -> {
                    val text = intent.getStringExtra("text")
                    if (text != null) updateText(text)
                }
                "ame.project.kanae.VOICE_ERROR" -> {
                    isListening = false
                    if (isOnline && isContinuous && isShowing) {
                        handler.postDelayed({ startListening() }, 1000)
                    }
                }
            }
        }
    }

    fun show() {
        if (isShowing) return

        val filter = android.content.IntentFilter().apply {
            addAction("ame.project.kanae.VOICE_RESULT")
            addAction("ame.project.kanae.VOICE_PARTIAL_RESULT")
            addAction("ame.project.kanae.VOICE_ERROR")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(voiceReceiver, filter)
        }

        val themed = ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_voice_layout, null)
        rootView = view
        tvVoiceText = view.findViewById(R.id.tv_voice_text)
        ivIndicator = view.findViewById(R.id.iv_voice_indicator)

        tvVoiceText?.textSize = textSizeSp

        val punch = PunchThroughLayout(themed).apply {
            punchEnabled = visualPunchEnabled
            clipChildren = false
            clipToPadding = false
            addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        punchLayout = punch

        val service = KanaeAccessibilityService.instance
        val type = if (audioCaptureMode == AudioCaptureMode.ACCESSIBILITY && service != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // If in accessibility mode, use the service's window manager
        if (audioCaptureMode == AudioCaptureMode.ACCESSIBILITY && service != null) {
            wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } else {
            wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                   WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                   if (visualPunchEnabled) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }
        layoutParams = params

        gestureHelper = OverlayGestureHelper(
            rootView = punch,
            params = params,
            wm = wm,
            onSingleTap = null
        ).also {
            it.currentScale = lastScale
            it.onInteraction = {
                lastX = params.x
                lastY = params.y
                lastScale = it.currentScale
                onPositionChanged?.invoke(lastX, lastY, lastScale)
            }
            view.setOnTouchListener(it)
        }

        wm.addView(punch, params)
        isShowing = true
        
        applyTheme(currentTheme)
        syncWindowSize()

        if (isOnline) {
            startListening()
        }
    }

    fun hide() {
        if (!isShowing) return
        stopListening()
        handler.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(voiceReceiver) }
        punchLayout?.let { runCatching { wm.removeView(it) } }
        rootView = null
        punchLayout = null
        tvVoiceText = null
        ivIndicator = null
        isShowing = false
    }

    fun setOnline(online: Boolean) {
        this.isOnline = online
        if (isShowing) {
            if (online) startListening()
            else stopListening()
        }
    }

    fun setContinuous(continuous: Boolean) {
        this.isContinuous = continuous
    }

    fun setLanguage(lang: String) {
        this.voiceLanguage = lang
        if (isShowing && isOnline) {
            stopListening()
            startListening()
        }
    }

    fun setTextSize(size: Float) {
        this.textSizeSp = size
        tvVoiceText?.textSize = size
        syncWindowSize()
    }

    fun setDisplayDuration(seconds: Int) {
        this.displayDurationMs = seconds * 1000L
    }

    fun setOverlayWidth(widthDp: Int) {
        this.overlayWidth = widthDp
        syncWindowSize()
    }

    private fun startListening() {
        if (isListening) return

        if (audioCaptureMode == AudioCaptureMode.ACCESSIBILITY) {
            val service = KanaeAccessibilityService.instance
            if (service != null) {
                service.startListening(voiceLanguage)
                isListening = true
                ivIndicator?.visibility = View.VISIBLE
                return
            }
            // Fallback to standard if service not running? 
            // The user said: "Kalau user pilih mode accessibility, service ini delegate start/stop listening ke KanaeAccessibilityService, bukan jalanin SpeechRecognizer sendiri"
        }
        
        handler.post {
            try {
                if (speechRecognizer == null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                        speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    }
                    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                            ivIndicator?.visibility = View.VISIBLE
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isListening = false
                        }
                        override fun onError(error: Int) {
                            isListening = false
                            Log.e("VoiceOverlay", "Error: $error")
                            if (isOnline && isContinuous && isShowing) {
                                handler.postDelayed({ startListening() }, 1000)
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                updateText(matches[0])
                            }
                            if (isOnline && isContinuous && isShowing) {
                                startListening()
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                updateText(matches[0])
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguage)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLanguage)
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, voiceLanguage)

                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e("VoiceOverlay", "SpeechRecognizer start error", e)
            }
        }
    }

    private fun stopListening() {
        if (audioCaptureMode == AudioCaptureMode.ACCESSIBILITY) {
            KanaeAccessibilityService.instance?.stopListening()
        } else {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
        isListening = false
        ivIndicator?.visibility = View.GONE
    }

    private fun updateText(text: String) {
        tvVoiceText?.text = text
        syncWindowSize()
        handler.removeCallbacks(hideTextRunnable)
        if (displayDurationMs > 0) {
            handler.postDelayed(hideTextRunnable, displayDurationMs)
        }
    }

    private fun syncWindowSize() {
        applyConfig(lastX, lastY, lastScale, overlayWidth)
    }

    fun applyTheme(theme: CustomTheme) {
        this.currentTheme = theme
        val view = rootView?.findViewById<View>(R.id.voice_container) ?: return
        
        val bgAlpha = theme.alpha
        theme.bgPrimary?.let { color ->
            val colorWithAlpha = Color.argb(bgAlpha, Color.red(color), Color.green(color), Color.blue(color))
            view.background?.let { bg ->
                val wrapped = DrawableCompat.wrap(bg.mutate())
                DrawableCompat.setTint(wrapped, colorWithAlpha)
                view.background = wrapped
            }
        }
        
        theme.textPrimary?.let { tvVoiceText?.setTextColor(it) }
    }

    fun applyConfig(x: Int, y: Int, scale: Float, width: Int = 0) {
        this.lastX = x
        this.lastY = y
        this.lastScale = scale
        if (width > 0) this.overlayWidth = width

        val params = layoutParams ?: return
        val view = punchLayout ?: return
        val content = rootView ?: return

        params.x = x
        params.y = y
        
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale

        val density = context.resources.displayMetrics.density
        val maxWidthPx = (overlayWidth * density).toInt()

        content.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val baseW = content.measuredWidth
        val baseH = content.measuredHeight

        params.width = (baseW * scale).toInt().coerceAtLeast(1)
        params.height = (baseH * scale).toInt().coerceAtLeast(1)

        gestureHelper?.let {
            it.currentScale = scale
            it.updateBaseSize(baseW, baseH)
        }

        runCatching { wm.updateViewLayout(view, params) }
    }

    fun setCanvasMode(locked: Boolean, x: Int = 0, y: Int = 0) {
        gestureHelper?.locked = locked
        val params = layoutParams ?: return
        val view = punchLayout ?: return
        if (locked) {
            params.x = x; params.y = y
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    if (visualPunchEnabled) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
        }
        runCatching { wm.updateViewLayout(view, params) }
    }
}
