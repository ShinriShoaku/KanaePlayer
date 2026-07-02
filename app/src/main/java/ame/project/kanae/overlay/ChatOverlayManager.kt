package ame.project.kanae.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import androidx.core.graphics.drawable.DrawableCompat
import ame.project.kanae.R
import ame.project.kanae.model.CustomTheme
import ame.project.kanae.model.TikTokEmote
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
    private var punchLayout: PunchThroughLayout? = null
    private var chatContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null
    
    private var overlayWidth: Int = 150
    private var displayDurationMs: Long = 6000
    private val handler = Handler(Looper.getMainLooper())
    private var isTransparent = true
    private var isDummyActive = false
    private var currentTextScale: Float = 1f
    private var currentLayoutId: Int = R.layout.item_chat_bubble
    private var currentBgId: Int = R.drawable.bg_chat_bubble
    private var currentTheme: CustomTheme = CustomTheme()
    private val activeChats = mutableListOf<View>()
    private val dummyAutoHideRunnable = Runnable { clearDummyChat() }

    var isShowing: Boolean = false
        private set

    private var visualPunchEnabled = false

    fun setVisualPunchEnabled(enabled: Boolean) {
        this.visualPunchEnabled = enabled
        punchLayout?.punchEnabled = enabled
        rootView?.setOnTouchListener(if (enabled) null else gestureHelper)
        rootView?.isClickable = !enabled
        rootView?.isFocusable = !enabled
    }

    fun show(x: Int = 16, y: Int = 500, scale: Float = 1f, width: Int = 150) {
        if (isShowing) return
        
        this.overlayWidth = width
        this.currentTextScale = scale

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_chat_layout, null)
        rootView = view
        chatContainer = view.findViewById(R.id.chat_container)

        val punch = PunchThroughLayout(themed).apply {
            punchEnabled = visualPunchEnabled
            // Use FrameLayout.LayoutParams for the inner view to avoid ClassCastException
            val lp = FrameLayout.LayoutParams(
                overlayWidth.dp,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(view, lp)
        }
        punchLayout = punch

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
            rootView = punch, // Use punch as the window root
            params = params,
            wm = wm,
            onSingleTap = null
        ).also { 
            it.currentScale = 1f // Visual scale remains 1
            it.updateBaseSize(baseW, 100) 
            if (visualPunchEnabled) view.setOnTouchListener(null) 
            else view.setOnTouchListener(it)
        }

        wm.addView(punch, params)
        isShowing = true

        // Force initial size sync
        view.post {
            if (isShowing) {
                syncWindowSize()
            }
        }

        // Add placeholder text to help positioning, auto-hide after 4s
        addDummyChat(autoHideMs = 4000)
    }

    fun addDummyChat(autoHideMs: Long = 0) {
        if (!isShowing) return
        handler.removeCallbacks(dummyAutoHideRunnable)
        
        if (!isDummyActive) {
            isDummyActive = true
            addChat("System", "Chat Overlay Active (Drag me!)", isDummy = true)
        }
        
        if (autoHideMs > 0) {
            handler.postDelayed(dummyAutoHideRunnable, autoHideMs)
        }
    }

    fun clearDummyChat() {
        handler.removeCallbacks(dummyAutoHideRunnable)
        if (!isDummyActive) return
        val container = chatContainer ?: return
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.tag == "dummy") {
                removeChatWithAnimation(container, child)
                break
            }
        }
        isDummyActive = false
    }

    fun hide() {
        if (!isShowing) return
        handler.removeCallbacksAndMessages(null)
        punchLayout?.let {
            runCatching { wm.removeView(it) }
        }
        rootView?.let {
            it.setOnTouchListener(null)
        }
        rootView = null
        punchLayout = null
        chatContainer = null
        layoutParams = null
        gestureHelper = null
        activeChats.clear()
        isDummyActive = false
        isShowing = false
    }

    fun setTransparent(transparent: Boolean) {
        this.isTransparent = transparent
        updateBackground()
    }

    private fun updateBackground() {
        val root = rootView ?: return
        if (isTransparent) {
            root.setBackgroundResource(android.R.color.transparent)
        } else {
            root.setBackgroundResource(R.drawable.overlay_bg)
            applyThemeToRoot()
        }
    }

    private fun applyThemeToRoot() {
        val root = rootView ?: return
        if (isTransparent) return
        
        val theme = currentTheme
        val bgAlpha = theme.alpha
        
        theme.bgPrimary?.let { color ->
            val colorWithAlpha = Color.argb(bgAlpha, Color.red(color), Color.green(color), Color.blue(color))
            root.background?.let { bg ->
                val wrapped = DrawableCompat.wrap(bg.mutate())
                DrawableCompat.setTint(wrapped, colorWithAlpha)
                root.background = wrapped
            } ?: run {
                root.setBackgroundColor(colorWithAlpha)
            }
        } ?: run {
            root.background?.mutate()?.alpha = bgAlpha
        }
    }

    fun updateStyle(layoutId: Int, bgId: Int) {
        this.currentLayoutId = layoutId
        this.currentBgId = bgId
        // Update existing dummy if any
        if (isDummyActive) {
            clearDummyChat()
            addDummyChat()
        }
    }

    fun applyTheme(theme: CustomTheme) {
        this.currentTheme = theme
        applyThemeToRoot()
        // Refresh existing chats if possible? 
        // For simplicity, we just apply to new ones, but we can clear dummy
        if (isDummyActive) {
            clearDummyChat()
            addDummyChat()
        }
    }

    fun addChat(nickname: String, message: String, color: Int = 0xFFFFCC00.toInt(), isDummy: Boolean = false, emotes: List<TikTokEmote> = emptyList()) {
        if (!isShowing) return
        val container = chatContainer ?: return

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val chatView = LayoutInflater.from(themed).inflate(currentLayoutId, container, false)
        if (isDummy) chatView.tag = "dummy"

        val tvNick = chatView.findViewById<TextView>(R.id.tv_username)
        val tvMsg = chatView.findViewById<TextView>(R.id.tv_message)
        val bubble = chatView.findViewById<View>(R.id.chat_bubble_container)

        tvNick?.text = nickname
        tvNick?.textSize = 12f * currentTextScale
        
        // Handle Emotes
        val ssb = SpannableStringBuilder(message)
        tvMsg?.text = ssb
        tvMsg?.textSize = 12f * currentTextScale

        if (emotes.isNotEmpty()) {
            emotes.forEach { emote ->
                Glide.with(context)
                    .asBitmap()
                    .load(emote.imageUrl)
                    .into(object : CustomTarget<android.graphics.Bitmap>() {
                        override fun onResourceReady(resource: android.graphics.Bitmap, transition: Transition<in android.graphics.Bitmap>?) {
                            val currentText = tvMsg?.text as? SpannableStringBuilder ?: SpannableStringBuilder(tvMsg?.text ?: "")
                            
                            val size = (18f * currentTextScale * context.resources.displayMetrics.density).toInt()
                            val drawable = BitmapDrawable(context.resources, resource)
                            drawable.setBounds(0, 0, size, size)
                            val span = ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
                            
                            var pos = emote.placeInComment
                            if (pos < 0) pos = 0
                            if (pos > currentText.length) pos = currentText.length
                            
                            if (pos == currentText.length) {
                                currentText.append(" ")
                            }
                            
                            try {
                                currentText.setSpan(span, pos, pos + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                tvMsg?.text = currentText
                            } catch (e: Exception) {}
                        }
                        override fun onLoadCleared(placeholder: Drawable?) {}
                    })
            }
        }

        // Apply Theme
        val theme = currentTheme
        val bgAlpha = theme.alpha

        // Background Logic
        if (currentBgId != 0 && currentBgId != android.R.color.transparent) {
            bubble?.setBackgroundResource(currentBgId)
        } else if (currentBgId == android.R.color.transparent) {
            bubble?.background = null
        }

        theme.bgPrimary?.let { color ->
            val colorWithAlpha = Color.argb(bgAlpha, Color.red(color), Color.green(color), Color.blue(color))
            bubble?.background?.let { bg ->
                val wrapped = DrawableCompat.wrap(bg.mutate())
                DrawableCompat.setTint(wrapped, colorWithAlpha)
                bubble.background = wrapped
            } ?: run {
                bubble?.setBackgroundColor(colorWithAlpha)
            }
        } ?: run {
            if (currentBgId != 0 && currentBgId != android.R.color.transparent) {
                val bgColor = if (isDummy) Color.argb(0x80, 0, 0, 0) else getBubbleColor(nickname)
                bubble?.background?.let { bg ->
                    val wrapped = DrawableCompat.wrap(bg.mutate())
                    DrawableCompat.setTint(wrapped, bgColor)
                    bubble.background = wrapped
                    bubble.background?.alpha = bgAlpha
                }
            } else {
                bubble?.background?.mutate()?.alpha = bgAlpha
            }
        }

        // Secondary Background (for boxed layout)
        if (currentLayoutId == R.layout.item_chat_bubble_boxed) {
            theme.bgSecondary?.let { color ->
                val colorWithAlpha = Color.argb(bgAlpha, Color.red(color), Color.green(color), Color.blue(color))
                tvNick?.background?.let { bg ->
                    val wrapped = DrawableCompat.wrap(bg.mutate())
                    DrawableCompat.setTint(wrapped, colorWithAlpha)
                    tvNick.background = wrapped
                } ?: run {
                    tvNick?.setBackgroundColor(colorWithAlpha)
                }
            } ?: run {
                tvNick?.background?.mutate()?.alpha = bgAlpha
            }
        }

        // Text Colors
        theme.textPrimary?.let { tvMsg?.setTextColor(it) }
        theme.textSecondary?.let { tvNick?.setTextColor(it) } 
            ?: theme.textPrimary?.let { tvNick?.setTextColor(it) }
            ?: run { if (currentBgId != 0) tvNick?.setTextColor(color) }

        // Fade in animation
        chatView.alpha = 0f
        
        if (isDummy) {
            // Remove old dummy if exists
            for (i in 0 until container.childCount) {
                if (container.getChildAt(i).tag == "dummy") {
                    container.removeViewAt(i)
                    break
                }
            }
            container.addView(chatView, 0) // Always at top
        } else {
            container.addView(chatView)
            activeChats.add(chatView)
        }
        
        chatView.animate().alpha(1f).setDuration(300).start()
        
        // Update window size to accommodate new message
        syncWindowSize()

        // Check max lines
        while (activeChats.size > maxLines) {
            val oldest = activeChats.removeAt(0)
            removeChatWithAnimation(container, oldest)
        }

        // Auto hide after set duration
        if (!isDummy) {
            handler.postDelayed({
                if (activeChats.remove(chatView)) {
                    removeChatWithAnimation(container, chatView)
                }
            }, displayDurationMs)
        }
    }

    private fun getBubbleColor(name: String): Int {
        val colors = intArrayOf(
            0xFF1A1A2E.toInt(), 0xFF16213E.toInt(), 0xFF0F3460.toInt(),
            0xFF533483.toInt(), 0xFF4E31AA.toInt(), 0xFF1A4D2E.toInt(),
            0xFF6D214F.toInt(), 0xFF1B1464.toInt(), 0xFF2C3E50.toInt(),
            0xFF8E44AD.toInt(), 0xFF2980B9.toInt(), 0xFF27AE60.toInt()
        )
        val index = Math.abs(name.hashCode()) % colors.size
        val baseColor = colors[index]
        // Make it semi-transparent (e.g., 0xCC = 204 alpha)
        return (baseColor and 0x00FFFFFF) or (0xCC shl 24)
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
        val container = chatContainer ?: return
        while (activeChats.size > maxLines) {
            val oldest = activeChats.removeAt(0)
            removeChatWithAnimation(container, oldest)
        }
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
        punchLayout?.let { runCatching { wm.updateViewLayout(it, params) } }
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
        punchLayout?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    fun applyConfig(x: Int, y: Int, scale: Float, width: Int = 0) {
        val params = layoutParams ?: return
        val view = punchLayout ?: return
        val content = rootView ?: return
        
        params.x = x
        params.y = y
        this.currentTextScale = scale

        // Reset visual scale - we use text scaling now
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = 1f
        content.scaleY = 1f
        
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
        val view = punchLayout ?: return
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
