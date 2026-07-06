package ame.project.kanae.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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

    private var lastX: Int = 16
    private var lastY: Int = 500
    private var lastScale: Float = 1f
    private var overlayWidth: Int = 150
    
    var onPositionChanged: ((x: Int, y: Int, scale: Float) -> Unit)? = null
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

    // Animation Window for Stickers
    private var animWindowView: ViewGroup? = null
    private var animBubbleContainer: FrameLayout? = null
    private var animLayoutParams: WindowManager.LayoutParams? = null
    private var isAnimShowing = false
    private var stickerAnimationEnabled = true
    private val random = java.util.Random()

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

    fun show(x: Int = lastX, y: Int = lastY, scale: Float = lastScale, width: Int = overlayWidth) {
        if (isShowing) return

        this.lastX = x
        this.lastY = y
        this.lastScale = scale
        this.overlayWidth = width
        this.currentTextScale = scale

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_chat_layout, null)
        rootView = view
        chatContainer = view.findViewById(R.id.chat_container)

        val punch = PunchThroughLayout(themed).apply {
            punchEnabled = visualPunchEnabled
            clipChildren = false
            clipToPadding = false
            // Use FrameLayout.LayoutParams for the inner view to avoid ClassCastException
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
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

        val baseW = FrameLayout.LayoutParams.WRAP_CONTENT
        val params = WindowManager.LayoutParams(
            baseW,
            WindowManager.LayoutParams.WRAP_CONTENT, // Start with wrap_content
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = lastX
            it.y = lastY
        }
        layoutParams = params

        gestureHelper = OverlayGestureHelper(
            rootView = punch, // Use punch as the window root
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
            if (visualPunchEnabled) view.setOnTouchListener(null)
            else view.setOnTouchListener(it)
        }

        wm.addView(punch, params)
        isShowing = true
        
        // Apply scaling immediately after adding view
        view.post {
            if (isShowing) {
                applyConfig(lastX, lastY, lastScale, overlayWidth)
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
        hideAnimationWindow()
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

        // Batasi jumlah animasi sticker yang terbang per 1 chat, walaupun
        // pesannya berisi banyak emote (mis. orang spam sticker yang sama
        // berulang kali dalam 1 comment). Gambar emote di teks tetap tampil
        // semua, hanya animasi "terbang"-nya yang dibatasi.
        var stickerSpawnCount = 0
        val maxStickerSpawnPerChat = 2

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val chatView = LayoutInflater.from(themed).inflate(currentLayoutId, container, false)
        if (isDummy) chatView.tag = "dummy"

        val tvNick = chatView.findViewById<TextView>(R.id.tv_username)
        val tvMsg = chatView.findViewById<TextView>(R.id.tv_message)
        val bubble = chatView.findViewById<View>(R.id.chat_bubble_container)

        tvNick?.text = nickname
        tvNick?.textSize = 12f

        // Handle Emotes
        val ssb = SpannableStringBuilder(message)
        tvMsg?.text = ssb
        tvMsg?.textSize = 12f

        if (emotes.isNotEmpty()) {
            emotes.forEach { emote ->
                Glide.with(context)
                    .asBitmap()
                    .load(emote.imageUrl)
                    .into(object : CustomTarget<android.graphics.Bitmap>() {
                        override fun onResourceReady(resource: android.graphics.Bitmap, transition: Transition<in android.graphics.Bitmap>?) {
                            val currentText = tvMsg?.text as? SpannableStringBuilder ?: SpannableStringBuilder(tvMsg?.text ?: "")

                            val size = (18f * context.resources.displayMetrics.density).toInt()
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

                                // Trigger sticker animation if enabled, dibatasi
                                // maksimal 2 animasi per chat agar tidak spam.
                                if (stickerAnimationEnabled && !isDummy && stickerSpawnCount < maxStickerSpawnPerChat) {
                                    stickerSpawnCount++
                                    spawnSticker(emote.imageUrl)
                                }
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

        // Gunakan pengukuran otomatis (seperti Join/Follow overlay)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        val baseW = view.measuredWidth
        val baseH = view.measuredHeight
        val scale = view.scaleX 

        // FIX: Tetapkan ukuran layout params child ke ukuran aslinya
        val contentLp = view.layoutParams as? FrameLayout.LayoutParams
        if (contentLp != null) {
            contentLp.width = baseW
            contentLp.height = baseH
            view.layoutParams = contentLp
        }

        params.width = (baseW * scale).toInt().coerceAtLeast(1)
        params.height = (baseH * scale).toInt().coerceAtLeast(1)

        gestureHelper?.updateBaseSize(baseW, baseH)
        punchLayout?.let { runCatching { wm.updateViewLayout(it, params) } }
        updateAnimationWindowPos()
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
        
        // Pivot di pojok kiri atas
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale

        // Selalu gunakan WRAP_CONTENT agar ukurannya pas dengan konten (seperti Join/Like overlay)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val baseW = content.measuredWidth
        val baseH = content.measuredHeight

        // FIX: Paksa ukuran content tetap di ukuran aslinya
        val contentLp = content.layoutParams as? FrameLayout.LayoutParams
        if (contentLp != null) {
            contentLp.width = baseW
            contentLp.height = baseH
            content.layoutParams = contentLp
        }

        // Update ukuran jendela sesuai scale
        params.width  = (baseW * scale).toInt().coerceAtLeast(1)
        params.height = (baseH * scale).toInt().coerceAtLeast(1)

        // Sinkronkan ke gesture helper
        gestureHelper?.let {
            it.currentScale = scale
            it.updateBaseSize(baseW, baseH)
        }

        runCatching { wm.updateViewLayout(view, params) }
        updateAnimationWindowPos()
    }

    // --- Sticker Animation Logic ---

    fun setStickerAnimationEnabled(enabled: Boolean) {
        this.stickerAnimationEnabled = enabled
        if (!enabled) hideAnimationWindow()
    }

    private fun showAnimationWindow() {
        if (!stickerAnimationEnabled || isAnimShowing) return
        if (animWindowView == null) setupAnimationWindow()

        try {
            updateAnimationWindowPos()
            wm.addView(animWindowView, animLayoutParams)
            isAnimShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateAnimationWindowPos() {
        val lp = layoutParams ?: return
        val alp = animLayoutParams ?: return
        val window = animWindowView ?: return

        alp.x = lp.x
        val density = context.resources.displayMetrics.density
        val baseAnimH = (250 * density).toInt()

        alp.width = lp.width
        alp.height = baseAnimH
        alp.y = lp.y - alp.height

        if (isAnimShowing) {
            try { wm.updateViewLayout(window, alp) } catch (_: Exception) {}
        }
    }

    private fun setupAnimationWindow() {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.item_sticker_bubble, null, false) as ViewGroup
        animWindowView = root
        animBubbleContainer = root.findViewById(R.id.sticker_bubble_container)

        animLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun hideAnimationWindow() {
        if (isAnimShowing && animWindowView != null) {
            try { wm.removeView(animWindowView) } catch (_: Exception) {}
        }
        isAnimShowing = false
        animWindowView = null
        animBubbleContainer = null
        animLayoutParams = null
    }

    private fun spawnSticker(imageUrl: String) {
        handler.post {
            if (!isAnimShowing) showAnimationWindow()
            val container = animBubbleContainer ?: return@post

            val sticker = ImageView(context).apply {
                val size = (60 + random.nextInt(20)).dp
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                alpha = 0f
                scaleX = 0.5f
                scaleY = 0.5f
            }
            container.addView(sticker)

            Glide.with(context).load(imageUrl).into(sticker)

            // Jitter horizontal dibuat proporsional terhadap lebar window
            // sticker (yang mengikuti lebar chat overlay), bukan angka dp
            // tetap. Kalau pakai angka tetap, di overlay yang sempit
            // (overlayWidth kecil) jitter-nya jadi kegedean relatif dan
            // sticker keliatan sering geser ke pinggir, bukan center.
            val windowWidthPx = (animLayoutParams?.width?.takeIf { it > 0 })
                ?: (overlayWidth.dp)
            val maxJitter = windowWidthPx * 0.18f
            val startX = (random.nextFloat() - 0.5f) * maxJitter
            val endX = startX + (random.nextFloat() - 0.5f) * (maxJitter * 0.5f)
            val endY = -(150 + random.nextInt(100)).dp.toFloat()
            val duration = 2000L + random.nextInt(1000)
            val rotation = (random.nextFloat() - 0.5f) * 40f

            sticker.translationX = startX

            val fadeIn = ObjectAnimator.ofFloat(sticker, View.ALPHA, 0f, 1f).setDuration(400)
            val fadeOut = ObjectAnimator.ofFloat(sticker, View.ALPHA, 1f, 0f).setDuration(500)
            fadeOut.startDelay = duration - 500

            val scaleUpX = ObjectAnimator.ofFloat(sticker, View.SCALE_X, 0.5f, 1f).setDuration(500)
            val scaleUpY = ObjectAnimator.ofFloat(sticker, View.SCALE_Y, 0.5f, 1f).setDuration(500)

            val moveUp = ObjectAnimator.ofFloat(sticker, View.TRANSLATION_Y, 0f, endY).setDuration(duration)
            val sway = ObjectAnimator.ofFloat(sticker, View.TRANSLATION_X, startX, endX).setDuration(duration)
            val spin = ObjectAnimator.ofFloat(sticker, View.ROTATION, 0f, rotation).setDuration(duration)

            val set = AnimatorSet()
            set.playTogether(fadeIn, fadeOut, scaleUpX, scaleUpY, moveUp, sway, spin)
            set.interpolator = AccelerateDecelerateInterpolator()
            set.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    container.removeView(sticker)
                }
            })
            set.start()
        }
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
        updateAnimationWindowPos()
    }
}