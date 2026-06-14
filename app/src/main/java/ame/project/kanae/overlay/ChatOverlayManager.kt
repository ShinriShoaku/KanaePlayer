package ame.project.kanae.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import ame.project.kanae.R
import kotlinx.coroutines.CoroutineScope

class ChatOverlayManager(
    context: Context,
    private val scope: CoroutineScope,
    private var maxLines: Int = 5,
    private val onClose: () -> Unit
) {
    private val context = context.applicationContext
    private val wm = this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var chatContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null
    
    private var overlayWidth: Int = 300
    private var displayDurationMs: Long = 6000
    private val handler = Handler(Looper.getMainLooper())
    private var isTransparent = true

    var isShowing: Boolean = false
        private set

    fun show() {
        if (isShowing) return

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_chat_layout, null)
        rootView = view
        chatContainer = view.findViewById(R.id.chat_container)

        updateBackground()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            overlayWidth.dp,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = 16
            it.y = 500
        }
        layoutParams = params

        gestureHelper = OverlayGestureHelper(
            rootView = view,
            params = params,
            wm = wm,
            onSingleTap = null
        ).also { view.setOnTouchListener(it) }

        wm.addView(view, params)
        isShowing = true

        // Add placeholder text to help positioning
        addChat("System", "Chat Overlay Active (Drag me!)")
    }

    fun hide() {
        if (!isShowing) return
        handler.removeCallbacksAndMessages(null)
        rootView?.let {
            it.setOnTouchListener(null)
            runCatching { wm.removeView(it) }
        }
        rootView = null
        chatContainer = null
        layoutParams = null
        gestureHelper = null
        isShowing = false
    }

    fun setTransparent(transparent: Boolean) {
        this.isTransparent = transparent
        updateBackground()
    }

    private fun updateBackground() {
        rootView?.setBackgroundResource(if (isTransparent) 
            android.R.color.transparent 
        else 
            R.drawable.overlay_bg)
    }

    fun addChat(nickname: String, message: String, color: Int = 0xFFFFCC00.toInt()) {
        if (!isShowing) return
        val container = chatContainer ?: return

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val chatView = LayoutInflater.from(themed).inflate(R.layout.item_chat_bubble, container, false)
        
        val tvNick = chatView.findViewById<TextView>(R.id.tv_username)
        tvNick.text = nickname
        tvNick.setTextColor(color)

        chatView.findViewById<TextView>(R.id.tv_message).text = message

        // Fade in animation
        chatView.alpha = 0f
        container.addView(chatView)
        chatView.animate().alpha(1f).setDuration(300).start()

        // Check max lines
        if (container.childCount > maxLines) {
            val oldest = container.getChildAt(0)
            removeChatWithAnimation(container, oldest)
        }

        // Auto hide after set duration
        handler.postDelayed({
            if (chatView.parent != null) {
                removeChatWithAnimation(container, chatView)
            }
        }, displayDurationMs)
    }

    private fun removeChatWithAnimation(container: LinearLayout, view: View) {
        view.animate()
            .alpha(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    container.removeView(view)
                }
            })
            .start()
    }

    fun setMaxLines(lines: Int) {
        this.maxLines = lines
    }

    fun setDisplayDuration(seconds: Int) {
        this.displayDurationMs = seconds * 1000L
    }

    fun setOverlayWidth(widthDp: Int) {
        this.overlayWidth = widthDp
        val params = layoutParams ?: return
        val view = rootView ?: return
        params.width = widthDp.dp
        runCatching { wm.updateViewLayout(view, params) }
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()

    fun applyConfig(x: Int, y: Int, scale: Float) {
        val params = layoutParams ?: return
        val view = rootView ?: return
        params.x = x
        params.y = y
        view.scaleX = scale
        view.scaleY = scale
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun setCanvasMode(locked: Boolean, x: Int = 0, y: Int = 0) {
        gestureHelper?.locked = locked
        val params = layoutParams ?: return
        val view = rootView ?: return
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
}
