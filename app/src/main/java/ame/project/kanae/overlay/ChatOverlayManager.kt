package ame.project.kanae.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ame.project.kanae.R
import ame.project.kanae.db.ChatDatabaseHelper
import ame.project.kanae.model.CustomTheme
import ame.project.kanae.model.TikTokEmote
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
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
    private var chatRecycler: RecyclerView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null

    private var lastX: Int = 16
    private var lastY: Int = 500
    private var lastScale: Float = 1f
    private var overlayWidth: Int = 150
    
    var onPositionChanged: ((x: Int, y: Int, scale: Float) -> Unit)? = null
    private var displayDurationMs: Long = 6000
    private var isAlwaysShow = false
    private var isHistoryEnabled = false
    private var isTikTokConnected = false
    private val handler = Handler(Looper.getMainLooper())
    private var isTransparent = true
    private var isDummyActive = false
    private var isUserInteracting = false
    private val resetInteractionRunnable = Runnable { isUserInteracting = false }
    private var currentTextScale: Float = 1f
    private var currentLayoutId: Int = R.layout.item_chat_bubble
    private var currentBgId: Int = R.drawable.bg_chat_bubble
    private var currentTheme: CustomTheme = CustomTheme()
    private var isDummyPersistent = false
    private val dbHelper = ChatDatabaseHelper(context)
    private var historyPage = 0
    private val pageSize = 20
    private var isLoadingHistory = false
    private var hasMoreHistory = true

    // Internal data: Always keep up to 50 for history
    private val chatList = mutableListOf<ChatMessage>()
    private var chatAdapter: ChatAdapter? = null
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

        val layoutName = try { context?.resources?.getResourceEntryName(currentLayoutId) ?: "unknown" } catch(_: Exception) { "ID_$currentLayoutId" }
        Log.i("Kanae_Overlay", "[OVERLAY_DEBUG] Attempting to show Chat Overlay with Layout: $layoutName")

        if (currentLayoutId == 0) {
            Log.e("Kanae_Overlay", "[OVERLAY_DEBUG] FATAL: Layout ID is 0. Overlay will not appear!")
        }

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_chat_layout, null)
        rootView = view
        chatRecycler = view.findViewById(R.id.chat_recycler)

        chatAdapter = ChatAdapter()
        chatRecycler?.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
            itemAnimator = DefaultItemAnimator().apply {
                removeDuration = 500
                addDuration = 300
            }

            // Fix: Detect when user is scrolling manually
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                        isUserInteracting = true
                        handler.removeCallbacks(resetInteractionRunnable)
                    } else {
                        // Wait 3 seconds of idle before re-enabling auto-scroll
                        handler.removeCallbacks(resetInteractionRunnable)
                        handler.postDelayed(resetInteractionRunnable, 3000)
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (isHistoryEnabled && !isLoadingHistory && hasMoreHistory) {
                        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                        if (layoutManager != null && layoutManager.findFirstVisibleItemPosition() <= 2) {
                            loadMoreHistory()
                        }
                    }
                }
            })

            // Attach the gesture helper to the RecyclerView to allow dragging from chat items
            setOnTouchListener { v, event ->
                // First, handle our internal interaction state
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        isUserInteracting = true
                        handler.removeCallbacks(resetInteractionRunnable)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(resetInteractionRunnable)
                        handler.postDelayed(resetInteractionRunnable, 3000)
                    }
                }
                
                // Then let the gesture helper process the touch for long-press drag
                gestureHelper?.onTouch(v, event) ?: false
            }
        }

        val punch = PunchThroughLayout(themed).apply {
            punchEnabled = visualPunchEnabled
            clipChildren = false
            clipToPadding = false
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(view, lp)
        }
        punchLayout = punch

        updateBackground()

        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = 1f
        view.scaleY = 1f

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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
            
            // Dragging only AFTER long press if history is enabled to avoid conflict with scrolling
            it.dragOnlyAfterLongPress = isHistoryEnabled
            
            if (visualPunchEnabled) view.setOnTouchListener(null)
            else view.setOnTouchListener(it)
        }

        wm.addView(punch, params)
        isShowing = true
        
        view.post {
            if (isShowing) {
                applyConfig(lastX, lastY, lastScale, overlayWidth)
            }
        }

        if (isAlwaysShow || !isTikTokConnected) {
            addDummyChat(durationMs = 0)
        } else {
            addDummyChat(durationMs = 4000)
        }
    }

    fun setTikTokConnected(connected: Boolean) {
        this.isTikTokConnected = connected
        if (!connected && isShowing && chatList.isEmpty()) {
            addDummyChat(durationMs = 0)
        } else if (connected && isDummyActive && !isAlwaysShow) {
            // Auto hide dummy when connected if always show is off
            addDummyChat(durationMs = 4000)
        }
    }

    fun addDummyChat(durationMs: Long = 5000) {
        if (!isShowing) return
        handler.removeCallbacks(dummyAutoHideRunnable)
        if (durationMs == 0L) isDummyPersistent = true
        
        if (!isDummyActive) {
            isDummyActive = true
            // Use a stable nickname "System" or "Preview" to keep colors consistent
            addChat("Preview User", "Chat Overlay Active (Waiting for messages)", isDummy = true)
        }
        
        // Logic fix: Only auto-hide if TikTok is connected AND AlwaysShow is false AND not persistent
        if (durationMs > 0 && !isAlwaysShow && isTikTokConnected) {
            handler.removeCallbacks(dummyAutoHideRunnable)
            handler.postDelayed(dummyAutoHideRunnable, durationMs)
        }
    }

    fun clearDummyChat(isStartingRealChat: Boolean = false) {
        handler.removeCallbacks(dummyAutoHideRunnable)
        if (!isStartingRealChat) isDummyPersistent = false
        
        if (!isDummyActive) return
        val index = chatList.indexOfFirst { it.isDummy }
        if (index != -1) {
            chatList.removeAt(index)
            chatAdapter?.notifyDataSetChanged()
        }
        isDummyActive = false
        
        // Bug Fix: Jika "Selalu Tampil" aktif ATAU belum connect ATAU persistent, kembalikan dummy "Waiting" 
        // Tapi jangan re-add jika sedang mau nambah chat beneran
        if (!isStartingRealChat && (isAlwaysShow || !isTikTokConnected || isDummyPersistent) && chatList.isEmpty() && isShowing) {
            addDummyChat(if (isDummyPersistent || !isTikTokConnected) 0 else 4000)
        }
        syncWindowSize()
    }

    fun clearChats() {
        handler.removeCallbacksAndMessages(null)
        chatList.clear()
        dbHelper.clearHistory()
        chatAdapter?.notifyDataSetChanged()
        isDummyActive = false
        if (isAlwaysShow) {
            addDummyChat(durationMs = 0)
        }
        syncWindowSize()
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
        chatRecycler = null
        layoutParams = null
        gestureHelper = null
        chatList.clear()
        isDummyActive = false
        isShowing = false
    }

    fun setTransparent(transparent: Boolean) {
        this.isTransparent = transparent
        updateBackground()
    }

    fun setAlwaysShow(alwaysShow: Boolean) {
        val changed = this.isAlwaysShow != alwaysShow
        this.isAlwaysShow = alwaysShow
        if (changed && isShowing) {
            if (alwaysShow && chatList.isEmpty()) {
                addDummyChat(durationMs = 0)
            } else if (!alwaysShow) {
                if (isDummyActive && (isTikTokConnected || isDummyPersistent)) {
                    addDummyChat(durationMs = 4000)
                }
                
                // If turned off, existing messages should start hiding
                if (!isHistoryEnabled) {
                    val now = System.currentTimeMillis()
                    chatList.toList().forEach { msg ->
                        if (msg.isDummy) return@forEach
                        val remaining = (msg.timestamp + displayDurationMs) - now
                        handler.postDelayed({
                            if (isAlwaysShow || isHistoryEnabled) return@postDelayed
                            val index = chatList.indexOf(msg)
                            if (index != -1) {
                                chatList.removeAt(index)
                                chatAdapter?.notifyDataSetChanged()
                                if ((isAlwaysShow || !isTikTokConnected || isDummyPersistent) && chatList.isEmpty()) addDummyChat(0)
                                syncWindowSize()
                            }
                        }, if (remaining > 0) remaining else 0L)
                    }
                }
            }
        }
        chatAdapter?.notifyDataSetChanged()
        syncWindowSize()
    }

    fun setHistoryEnabled(enabled: Boolean) {
        this.isHistoryEnabled = enabled
        gestureHelper?.dragOnlyAfterLongPress = enabled
        
        if (enabled) {
            loadInitialHistory()
        } else {
            // Reset pagination state
            historyPage = 0
            hasMoreHistory = true
            // Re-limit to maxLines for live view
            if (chatList.size > maxLines) {
                val toRemove = chatList.size - maxLines
                repeat(toRemove) { 
                    if (chatList.isNotEmpty()) chatList.removeAt(0) 
                }
            }
        }
        
        chatAdapter?.notifyDataSetChanged()
        syncWindowSize()
        // Auto scroll to bottom when history opened
        if (enabled) {
            chatRecycler?.post {
                chatAdapter?.let { 
                    chatRecycler?.scrollToPosition(it.itemCount - 1)
                }
            }
        }
    }

    private fun loadInitialHistory() {
        if (isLoadingHistory) return
        isLoadingHistory = true
        historyPage = 0
        hasMoreHistory = true
        
        // Use a background thread/scope if possible, but SQLite is usually fast enough for small queries
        // Since we are in an overlay, we'll just do it simply.
        val history = dbHelper.getChatHistory(pageSize, 0)
        chatList.clear()
        chatList.addAll(history)
        isLoadingHistory = false
        if (history.size < pageSize) hasMoreHistory = false
        historyPage++
    }

    private fun loadMoreHistory() {
        if (isLoadingHistory || !hasMoreHistory) return
        isLoadingHistory = true
        
        val offset = historyPage * pageSize
        val moreHistory = dbHelper.getChatHistory(pageSize, offset)
        
        if (moreHistory.isEmpty()) {
            hasMoreHistory = false
            isLoadingHistory = false
            return
        }

        chatList.addAll(0, moreHistory)
        chatAdapter?.notifyItemRangeInserted(0, moreHistory.size)
        
        if (moreHistory.size < pageSize) hasMoreHistory = false
        historyPage++
        isLoadingHistory = false
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
        chatAdapter?.notifyDataSetChanged()
        if (isDummyActive) {
            clearDummyChat()
            addDummyChat()
        }
    }

    fun applyTheme(theme: CustomTheme) {
        this.currentTheme = theme
        applyThemeToRoot()
        if (isDummyActive) {
            clearDummyChat()
            addDummyChat()
        }
    }

    fun addChat(nickname: String, message: String, color: Int = 0xFFFFCC00.toInt(), isDummy: Boolean = false, emotes: List<TikTokEmote> = emptyList()) {
        if (!isShowing) return

        if (!isDummy && isDummyActive) {
            clearDummyChat(isStartingRealChat = true)
        }

        val newMessage = ChatMessage(
            id = System.nanoTime(),
            nickname = nickname,
            message = message,
            color = color,
            emotes = emotes,
            isDummy = isDummy
        )

        if (isDummy) {
            val oldDummyIndex = chatList.indexOfFirst { it.isDummy }
            if (oldDummyIndex != -1) {
                chatList.removeAt(oldDummyIndex)
            }
            chatList.add(0, newMessage)
            chatAdapter?.notifyDataSetChanged()
            isDummyActive = true
        } else {
            dbHelper.insertChat(newMessage)
            chatList.add(newMessage)
            chatAdapter?.notifyDataSetChanged()
            
            // Auto scroll to new message only if user is NOT interacting
            if (!isUserInteracting) {
                chatRecycler?.post {
                    chatAdapter?.let {
                        chatRecycler?.smoothScrollToPosition(it.itemCount - 1)
                    }
                }
            }

            // Auto hide timer only if NOT in history mode and NOT in always show mode
            if (!isAlwaysShow && !isHistoryEnabled) {
                handler.postDelayed({
                    if (isAlwaysShow || isHistoryEnabled) return@postDelayed
                    val index = chatList.indexOf(newMessage)
                    if (index != -1) {
                        chatList.removeAt(index)
                        chatAdapter?.notifyDataSetChanged()
                        if ((isAlwaysShow || !isTikTokConnected || isDummyPersistent) && chatList.isEmpty()) addDummyChat(0)
                        syncWindowSize()
                    }
                }, displayDurationMs)
            }
        }

        // Limit data to maxLines for normal, in history mode we keep what's loaded + new
        if (!isHistoryEnabled) {
            while (chatList.size > maxLines) {
                chatList.removeAt(0)
                chatAdapter?.notifyDataSetChanged()
            }
        } else {
            // Optional: limit history in memory to e.g. 500 items to keep it light
            if (chatList.size > 500) {
                chatList.removeAt(0)
                chatAdapter?.notifyDataSetChanged()
            }
        }

        if (stickerAnimationEnabled && !isDummy) {
            var stickerSpawnCount = 0
            val maxStickerSpawnPerChat = 2
            emotes.forEach { emote ->
                if (stickerSpawnCount < maxStickerSpawnPerChat) {
                    stickerSpawnCount++
                    spawnSticker(emote.imageUrl)
                }
            }
        }

        syncWindowSize()
    }

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvNick = itemView.findViewById<TextView>(R.id.tv_username)
        private val tvMsg = itemView.findViewById<TextView>(R.id.tv_message)
        private val bubble = itemView.findViewById<View>(R.id.chat_bubble_container)

        fun bind(item: ChatMessage) {
            tvNick?.text = item.nickname
            tvNick?.textSize = 12f
            val ssb = SpannableStringBuilder(item.message)
            tvMsg?.text = ssb
            tvMsg?.textSize = 12f

            if (item.emotes.isNotEmpty()) {
                item.emotes.forEach { emote ->
                    Glide.with(itemView.context)
                        .asBitmap()
                        .load(emote.imageUrl)
                        .into(object : CustomTarget<android.graphics.Bitmap>() {
                            override fun onResourceReady(resource: android.graphics.Bitmap, transition: Transition<in android.graphics.Bitmap>?) {
                                val currentText = tvMsg?.text as? SpannableStringBuilder ?: SpannableStringBuilder(tvMsg?.text ?: "")
                                val size = (18f * itemView.context.resources.displayMetrics.density).toInt()
                                val drawable = BitmapDrawable(itemView.context.resources, resource)
                                drawable.setBounds(0, 0, size, size)
                                val span = ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
                                var pos = emote.placeInComment
                                if (pos < 0) pos = 0
                                if (pos > currentText.length) pos = currentText.length
                                if (pos == currentText.length) currentText.append(" ")
                                try {
                                    currentText.setSpan(span, pos, pos + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    tvMsg?.text = currentText
                                } catch (e: Exception) {}
                            }
                            override fun onLoadCleared(placeholder: Drawable?) {}
                        })
                }
            }

            if (currentBgId != 0 && currentBgId != android.R.color.transparent) {
                bubble?.setBackgroundResource(currentBgId)
            } else if (currentBgId == android.R.color.transparent) {
                bubble?.background = null
            }

            val theme = currentTheme
            val isBoxed = try {
                context?.resources?.getResourceEntryName(currentLayoutId) == "item_chat_bubble_boxed"
            } catch (_: Exception) {
                currentLayoutId == R.layout.item_chat_bubble_boxed
            }

            // 1. Handle Primary Background (Bubble/Message)
            theme.bgPrimary?.let { color ->
                val colorWithAlpha = Color.argb(theme.alpha, Color.red(color), Color.green(color), Color.blue(color))
                bubble?.background?.let { bg ->
                    val wrapped = DrawableCompat.wrap(bg.mutate())
                    DrawableCompat.setTint(wrapped, colorWithAlpha)
                    bubble.background = wrapped
                } ?: run {
                    bubble?.setBackgroundColor(colorWithAlpha)
                }
            } ?: run {
                // If Boxed style, only apply alpha to the existing white background. 
                // DO NOT apply the dark dummy color logic here.
                if (isBoxed) {
                    bubble?.background?.mutate()?.alpha = theme.alpha
                } else {
                    // Fallback for non-boxed styles: Use random colors or dummy grey
                    val bgColor = if (item.isDummy) Color.argb(0x80, 0, 0, 0) else getBubbleColor(item.nickname)
                    
                    if (currentBgId != 0 && currentBgId != android.R.color.transparent) {
                        bubble?.background?.let { bg ->
                            val wrapped = DrawableCompat.wrap(bg.mutate())
                            DrawableCompat.setTint(wrapped, bgColor)
                            bubble.background = wrapped
                            bubble.background?.alpha = theme.alpha
                        }
                    }
                }
            }

            // 2. Handle Secondary Background (Username Tag - especially for Boxed style)
            if (isBoxed) {
                theme.bgSecondary?.let { color ->
                    val colorWithAlpha = Color.argb(theme.alpha, Color.red(color), Color.green(color), Color.blue(color))
                    tvNick?.background?.let { bg ->
                        val wrapped = DrawableCompat.wrap(bg.mutate())
                        DrawableCompat.setTint(wrapped, colorWithAlpha)
                        tvNick.background = wrapped
                    } ?: run {
                        tvNick?.setBackgroundColor(colorWithAlpha)
                    }
                } ?: run {
                    // Reset or default alpha if no custom color
                    tvNick?.background?.mutate()?.alpha = theme.alpha
                }
            }

            // 3. Handle Text Colors
            theme.textPrimary?.let { tvMsg?.setTextColor(it) }
            theme.textSecondary?.let { tvNick?.setTextColor(it) }
                ?: theme.textPrimary?.let { tvNick?.setTextColor(it) }
                ?: run { 
                    if (isBoxed) tvNick?.setTextColor(Color.WHITE)
                    else if (currentBgId != 0) tvNick?.setTextColor(item.color) 
                }
        }
    }

    inner class ChatAdapter : RecyclerView.Adapter<ChatViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
            val themed = android.view.ContextThemeWrapper(parent.context, R.style.Theme_YTTikTokPlayer)
            
            val layoutName = try { parent.context.resources.getResourceEntryName(currentLayoutId) } catch(_: Exception) { "ERROR" }
            Log.d("Kanae_Overlay", "[OVERLAY_DEBUG] Inflating Chat Bubble: $layoutName")

            val view = LayoutInflater.from(themed).inflate(currentLayoutId, parent, false)
            return ChatViewHolder(view)
        }
        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            val displayList = if (isHistoryEnabled) chatList else chatList.takeLast(maxLines)
            if (position >= 0 && position < displayList.size) {
                holder.bind(displayList[position])
            }
        }
        override fun getItemCount(): Int {
            return if (isHistoryEnabled) chatList.size else chatList.size.coerceAtMost(maxLines)
        }
    }

    data class ChatMessage(
        val id: Long,
        val nickname: String,
        val message: String,
        val color: Int,
        val emotes: List<TikTokEmote>,
        val isDummy: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )

    private fun getBubbleColor(nickname: String): Int {
        val colors = intArrayOf(
            0xFF1A1A2E.toInt(), 0xFF16213E.toInt(), 0xFF0F3460.toInt(),
            0xFF533483.toInt(), 0xFF4E31AA.toInt(), 0xFF1A4D2E.toInt(),
            0xFF6D214F.toInt(), 0xFF1B1464.toInt(), 0xFF2C3E50.toInt(),
            0xFF8E44AD.toInt(), 0xFF2980B9.toInt(), 0xFF27AE60.toInt()
        )
        val index = Math.abs(nickname.hashCode()) % colors.size
        val baseColor = colors[index]
        return (baseColor and 0x00FFFFFF) or (0xCC shl 24)
    }

    fun setMaxLines(lines: Int) {
        this.maxLines = lines
        chatAdapter?.notifyDataSetChanged()
        syncWindowSize()
    }

    fun setDisplayDuration(seconds: Int) {
        this.displayDurationMs = seconds * 1000L
    }

    private var previewBox: View? = null
    private var isPreviewingWidth = false
    private var lastWidth: Int = 0

    fun showWidthPreview(widthDp: Int) {
        if (!isShowing) return
        isPreviewingWidth = true
        val density = context.resources.displayMetrics.density
        val finalWidthDp = if (widthDp > 0 && widthDp < 200) 200 else widthDp
        val widthPx = (finalWidthDp * density).toInt()

        if (previewBox == null) {
            previewBox = View(context).apply {
                val strokeW = (2 * density).toInt()
                val dashW = (8 * density).toInt()
                val dashG = (4 * density).toInt()
                val drawable = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(strokeW, Color.parseColor("#FF9800"), dashW.toFloat(), dashG.toFloat())
                    setColor(Color.parseColor("#26FF9800"))
                    cornerRadius = (8 * density)
                }
                background = drawable
            }
            punchLayout?.addView(previewBox)
        }
        
        previewBox?.visibility = View.VISIBLE
        previewBox?.bringToFront()
        
        val lp = previewBox?.layoutParams as? FrameLayout.LayoutParams
        if (lp != null) {
            lp.width = if (widthPx > 0) widthPx else FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.START
            previewBox?.layoutParams = lp
        }

        applyConfig(lastX, lastY, lastScale, widthDp)
    }

    fun hideWidthPreview() {
        isPreviewingWidth = false
        previewBox?.visibility = View.GONE
        applyConfig(lastX, lastY, lastScale, overlayWidth)
    }

    fun setOverlayWidth(widthDp: Int) {
        this.overlayWidth = widthDp
        applyConfig(lastX, lastY, lastScale, widthDp)
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()

    private fun syncWindowSize() {
        applyConfig(lastX, lastY, lastScale, overlayWidth)
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
        val finalWidth = if (overlayWidth > 0 && overlayWidth < 200) 200 else overlayWidth
        val maxWidthPx = if (finalWidth > 0) (finalWidth * density).toInt() else 0

        val widthSpec = if (maxWidthPx > 0) {
            View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST)
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }

        chatRecycler?.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
        
        val maxHPx = (350 * density).toInt()
        if (isHistoryEnabled) {
            content.measure(
                widthSpec,
                View.MeasureSpec.makeMeasureSpec(maxHPx, View.MeasureSpec.AT_MOST)
            )
            if (content.measuredHeight >= maxHPx) {
                chatRecycler?.layoutParams?.height = maxHPx
            }
        } else {
            content.measure(
                widthSpec,
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }

        val baseW = content.measuredWidth
        val baseH = content.measuredHeight + (12 * density).toInt() // Extra space to prevent bottom cut-off

        val contentLp = content.layoutParams as? FrameLayout.LayoutParams
        if (contentLp != null) {
            contentLp.width = baseW
            contentLp.height = baseH
            content.layoutParams = contentLp
        }

        var effectiveBaseW = baseW
        if (isPreviewingWidth && maxWidthPx > 0) {
            effectiveBaseW = maxOf(effectiveBaseW, maxWidthPx)
        }

        params.width  = (effectiveBaseW * scale).toInt().coerceAtLeast(1)
        params.height = (baseH * scale).toInt().coerceAtLeast(1)

        gestureHelper?.let {
            it.currentScale = scale
            it.updateBaseSize(baseW, baseH)
        }

        runCatching { wm.updateViewLayout(view, params) }
        updateAnimationWindowPos()
    }

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
        } catch (e: Exception) {}
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
            val windowWidthPx = (animLayoutParams?.width?.takeIf { it > 0 }) ?: (overlayWidth.dp)
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
