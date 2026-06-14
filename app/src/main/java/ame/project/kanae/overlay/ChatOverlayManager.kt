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
    private var isDummyActive = false
    private var currentTextScale: Float = 1f

    var isShowing: Boolean = false
        private set

    fun show(x: Int = 16, y: Int = 500, scale: Float = 1f, width: Int = 300) {
        if (isShowing) return
        
        this.overlayWidth = width
        this.currentTextScale = scale

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_chat_layout, null)
        rootView = view
        chatContainer = view.findViewById(R.id.chat_container)

        updateBackground()

        // Reset scaling on the root view itself - we will scale text instead
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = 1f
        view.scaleY = 1f

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val baseW = overlayWidth.dp
        val params = WindowManager.LayoutParams(
            baseW,
            WindowManager.LayoutParams.WRAP_CONTENT, // Start with wrap_content
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = x
            it.y = y
        }
        layoutParams = params

        gestureHelper = OverlayGestureHelper(
            rootView = view,
            params = params,
            wm = wm,
            onSingleTap = null
        ).also { 
            it.currentScale = 1f // Visual scale remains 1
            it.updateBaseSize(baseW, 100) 
            view.setOnTouchListener(it) 
        }

        wm.addView(view, params)
        isShowing = true

        // Force initial size sync
        view.post {
            if (isShowing) {
                syncWindowSize()
            }
        }

        // Add placeholder text to help positioning
        addDummyChat()
    }

    private fun addDummyChat() {
        if (!isShowing) return
        isDummyActive = true
        addChat("System", "Chat Overlay Active (Drag me!)", isDummy = true)
    }

    private fun clearDummyChat() {
        if (!isDummyActive) return
        val container = chatContainer ?: return
        container.removeAllViews()
        isDummyActive = false
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

    fun addChat(nickname: String, message: String, color: Int = 0xFFFFCC00.toInt(), isDummy: Boolean = false) {
        if (!isShowing) return
        val container = chatContainer ?: return

        // If a real chat comes in and dummy is active, clear the dummy first
        if (!isDummy && isDummyActive) {
            clearDummyChat()
        }

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val chatView = LayoutInflater.from(themed).inflate(R.layout.item_chat_bubble, container, false)
        
        val tvNick = chatView.findViewById<TextView>(R.id.tv_username)
        tvNick.text = nickname
        tvNick.setTextColor(color)
        tvNick.textSize = 12f * currentTextScale

        val tvMsg = chatView.findViewById<TextView>(R.id.tv_message)
        tvMsg.text = message
        tvMsg.textSize = 12f * currentTextScale

        // Fade in animation
        chatView.alpha = 0f
        // Add new chat at the bottom for normal flow
        container.addView(chatView)
        chatView.animate().alpha(1f).setDuration(300).start()
        
        // Update window size to accommodate new message
        syncWindowSize()

        // Check max lines - remove oldest which is at the top (index 0)
        if (container.childCount > maxLines) {
            val oldest = container.getChildAt(0)
            removeChatWithAnimation(container, oldest)
        }

        // Auto hide after set duration
        if (!isDummy) {
            handler.postDelayed({
                if (chatView.parent != null) {
                    removeChatWithAnimation(container, chatView)
                }
            }, displayDurationMs)
        }
    }

    private fun removeChatWithAnimation(container: LinearLayout, view: View) {
        view.animate()
            .alpha(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    container.removeView(view)
                    syncWindowSize() // Update window size after removal
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
        
        // Update root view internal layout params as well
        view.layoutParams?.let { 
            it.width = widthDp.dp
            view.layoutParams = it
        }

        params.width = widthDp.dp
        runCatching { wm.updateViewLayout(view, params) }
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()

    private fun syncWindowSize() {
        val view = rootView ?: return
        val params = layoutParams ?: return
        
        val baseW = overlayWidth.dp
        
        // Sync root view layout params width
        view.layoutParams?.let {
            if (it.width != baseW) {
                it.width = baseW
                view.layoutParams = it
            }
        }

        view.measure(
            View.MeasureSpec.makeMeasureSpec(baseW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val baseH = view.measuredHeight

        params.width = baseW
        params.height = baseH
        
        gestureHelper?.updateBaseSize(baseW, baseH)
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun applyConfig(x: Int, y: Int, scale: Float, width: Int = 0) {
        val params = layoutParams ?: return
        val view = rootView ?: return
        
        params.x = x
        params.y = y
        this.currentTextScale = scale

        // Reset visual scale - we use text scaling now
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        
        // Update window size agar mencakup seluruh layout
        val dp = context!!.resources.displayMetrics.density
        val baseW = if (width > 0) {
            this.overlayWidth = width
            (width * dp).toInt()
        } else {
            overlayWidth.dp
        }
        
        // Sync root view layout params width
        view.layoutParams?.let {
            it.width = baseW
            view.layoutParams = it
        }

        // Apply scale to ALL existing messages
        val container = chatContainer
        if (container != null) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                child.findViewById<TextView>(R.id.tv_username)?.textSize = 12f * scale
                child.findViewById<TextView>(R.id.tv_message)?.textSize = 12f * scale
            }
        }

        view.measure(
            View.MeasureSpec.makeMeasureSpec(baseW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val baseH = view.measuredHeight

        params.width = baseW
        params.height = baseH
        
        // Sinkronkan ke gesture helper
        gestureHelper?.let {
            it.currentScale = 1f
            it.updateBaseSize(baseW, baseH)
        }

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
