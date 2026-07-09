package ame.project.kanae.canvas

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.card.MaterialCardView
import ame.project.kanae.R
import ame.project.kanae.service.PlayerForegroundService
import top.defaults.colorpicker.ColorPickerView

class CanvasActivity : AppCompatActivity() {

    enum class UIComponent(val title: String, val icon: String) {
        CHAT("Chat Bubble", "💬"),
        QUEUE("Queue Overlay", "⏳"),
        PLAYER("Music Player", "🎵"),
        LYRICS("Lyrics Card", "🎤"),
        NOTIF("Notification", "🔔"),
        JOIN("User Join", "👋"),
        LIKE("Like/Tap", "💖"),
        FOLLOW("Follow", "👤")
    }

    data class ThemeStyle(
        val name: String,
        val layoutId: Int,
        val backgroundId: Int,
        val category: UIComponent,
        val itemLayoutId: Int = 0
    )

    private var currentCategory = UIComponent.CHAT
    private val tempSelections = mutableMapOf<UIComponent, Pair<Int, Int>>()
    private var previewLayoutId: Int = 0
    private var previewBgId: Int = 0

    private val allStyles = listOf(
        ThemeStyle("Vertical", R.layout.item_chat_bubble, R.drawable.bg_chat_bubble, UIComponent.CHAT),
        ThemeStyle("Horizontal", R.layout.item_chat_bubble_horizontal, R.drawable.bg_chat_bubble, UIComponent.CHAT),
        ThemeStyle("Pill Vertical", R.layout.item_chat_bubble, R.drawable.bg_chat_bubble_pill, UIComponent.CHAT),
        ThemeStyle("Pill Horizontal", R.layout.item_chat_bubble_horizontal, R.drawable.bg_chat_bubble_pill, UIComponent.CHAT),
        ThemeStyle("Bordered", R.layout.item_chat_bubble, R.drawable.bg_chat_bubble_bordered, UIComponent.CHAT),
        ThemeStyle("Minimal", R.layout.item_chat_bubble, android.R.color.transparent, UIComponent.CHAT),
        ThemeStyle("Gradient", R.layout.item_chat_bubble, R.drawable.bg_chat_bubble_gradient, UIComponent.CHAT),
        ThemeStyle("Glass", R.layout.item_chat_bubble, R.drawable.bg_chat_bubble_glass, UIComponent.CHAT),
        ThemeStyle("Neon", R.layout.item_chat_bubble, R.drawable.bg_chat_bubble_neon, UIComponent.CHAT),
        ThemeStyle("Boxed", R.layout.item_chat_bubble_boxed, 0, UIComponent.CHAT),
        
        ThemeStyle("Queue Standard", R.layout.overlay_queue_layout, 0, UIComponent.QUEUE, R.layout.item_queue),
        ThemeStyle("Queue Modern", R.layout.overlay_queue_modern, 0, UIComponent.QUEUE, R.layout.item_queue_modern),
        ThemeStyle("Queue Glass", R.layout.overlay_queue_glass, 0, UIComponent.QUEUE, R.layout.item_queue_glass),
        ThemeStyle("Queue Neon", R.layout.overlay_queue_neon, 0, UIComponent.QUEUE, R.layout.item_queue_neon),
        ThemeStyle("Queue Card", R.layout.overlay_queue_card, 0, UIComponent.QUEUE, R.layout.item_queue_card),
        ThemeStyle("Queue Minimal", R.layout.overlay_queue_minimal, 0, UIComponent.QUEUE, R.layout.item_queue_minimal),
        ThemeStyle("Queue Sketch", R.layout.overlay_queue_sketch, 0, UIComponent.QUEUE, R.layout.item_queue_sketch),
        
        ThemeStyle("Player Standard", R.layout.overlay_layout, 0, UIComponent.PLAYER),
        ThemeStyle("Player Modern", R.layout.overlay_layout_modern, 0, UIComponent.PLAYER),
        ThemeStyle("Player Glass", R.layout.overlay_layout_glass, 0, UIComponent.PLAYER),
        ThemeStyle("Player Retro", R.layout.overlay_layout_retro, 0, UIComponent.PLAYER),
        ThemeStyle("Player Immersive", R.layout.overlay_layout_immersive, 0, UIComponent.PLAYER),
        ThemeStyle("Player Compact", R.layout.overlay_layout_compact, 0, UIComponent.PLAYER),
        
        ThemeStyle("Lyrics Standard", R.layout.overlay_lyrics_layout, 0, UIComponent.LYRICS),
        ThemeStyle("Lyrics Glass", R.layout.overlay_lyrics_glass, 0, UIComponent.LYRICS),
        ThemeStyle("Lyrics Neon", R.layout.overlay_lyrics_neon, 0, UIComponent.LYRICS),
        ThemeStyle("Lyrics Sketch", R.layout.overlay_lyrics_sketch, 0, UIComponent.LYRICS),
        ThemeStyle("Lyrics Minimal", R.layout.overlay_lyrics_minimal, 0, UIComponent.LYRICS),

        ThemeStyle("Notif Standard", R.layout.overlay_tiktok_notification, R.drawable.overlay_bg, UIComponent.NOTIF),
        
        ThemeStyle("Join Card", R.layout.overlay_tiktok_join, 0, UIComponent.JOIN),
        ThemeStyle("Join List", R.layout.overlay_tiktok_join_list, 0, UIComponent.JOIN),
        ThemeStyle("Join Pill", R.layout.overlay_tiktok_join_pill, 0, UIComponent.JOIN),
        
        ThemeStyle("Like Card", R.layout.overlay_tiktok_like, 0, UIComponent.LIKE),
        ThemeStyle("Like Compact", R.layout.overlay_tiktok_like_compact, 0, UIComponent.LIKE),
        ThemeStyle("Like Neon", R.layout.overlay_tiktok_like_neon, 0, UIComponent.LIKE),
        
        ThemeStyle("Follow Standard", R.layout.overlay_tiktok_follow, 0, UIComponent.FOLLOW),
        ThemeStyle("Follow Glass", R.layout.overlay_tiktok_follow_glass, 0, UIComponent.FOLLOW),
        ThemeStyle("Follow Neon", R.layout.overlay_tiktok_follow_neon, 0, UIComponent.FOLLOW),
        ThemeStyle("Follow Compact", R.layout.overlay_tiktok_follow_compact, 0, UIComponent.FOLLOW)
    )

    private var service: PlayerForegroundService? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as PlayerForegroundService.LocalBinder
            service = b.getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null; serviceBound = false
        }
    }

    private lateinit var tvSelectorTitle: TextView
    private lateinit var rvStyles: RecyclerView
    private lateinit var previewFrame: FrameLayout
    private lateinit var btnSaveAll: Button
    private lateinit var btnPickComponent: Button
    private lateinit var btnSelectCurrent: Button

    private lateinit var colorPartsContainer: LinearLayout
    private lateinit var sbPickerAlpha: SeekBar

    private lateinit var containerInlinePicker: View
    private lateinit var tvInlinePickerTitle: TextView
    private lateinit var btnCloseInlinePicker: Button
    private lateinit var wheelColorPicker: ColorPickerView
    private lateinit var pickerHeader: View

    private var activeColorKey: String? = null
    private var dX = 0f
    private var dY = 0f

    private val customColors = mutableMapOf<UIComponent, MutableMap<String, Int?>>()
    private val customAlphas = mutableMapOf<UIComponent, Int>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_canvas)

        tvSelectorTitle = findViewById(R.id.tv_selector_title)
        rvStyles        = findViewById(R.id.rv_chat_styles)
        previewFrame    = findViewById(R.id.preview_content_frame)
        btnSaveAll      = findViewById(R.id.canvas_btn_toggle_lock)
        btnPickComponent = findViewById(R.id.pick_custom_theme)
        btnSelectCurrent = findViewById(R.id.canvas_btn_save_temporary)

        colorPartsContainer = findViewById(R.id.container_color_parts)
        sbPickerAlpha      = findViewById(R.id.sb_canvas_alpha)

        containerInlinePicker = findViewById(R.id.container_inline_picker)
        tvInlinePickerTitle   = findViewById(R.id.tv_inline_picker_title)
        btnCloseInlinePicker  = findViewById(R.id.btn_close_inline_picker)
        wheelColorPicker      = findViewById(R.id.wheel_color_picker)
        pickerHeader          = findViewById(R.id.picker_header)

        setupDraggableLogic()
        
        // top.defaults.colorpicker usage:
        wheelColorPicker.subscribe { color, fromUser, _ ->
            if (fromUser) {
                activeColorKey?.let { key ->
                    val map = customColors.getOrPut(currentCategory) { mutableMapOf() }
                    map[key] = color
                    syncPreview()
                }
            }
        }

        setupPresets()

        btnCloseInlinePicker.setOnClickListener {
            containerInlinePicker.visibility = View.GONE
            activeColorKey = null
            updateColorBoxesUI()
        }

        setupAlphaLogic()
        loadCustomColors()

        rvStyles.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)

        val prefs = getSharedPreferences("ytplayer_prefs", Context.MODE_PRIVATE)
        UIComponent.entries.forEach { comp ->
            val layout = prefs.getInt("canvas_${comp.name.lowercase()}_layout", 0)
            val bg = prefs.getInt("canvas_${comp.name.lowercase()}_bg", 0)
            if (layout != 0) tempSelections[comp] = Pair(layout, bg)
        }

        btnPickComponent.setOnClickListener { showCategoryPicker() }
        
        btnSelectCurrent.setOnClickListener {
            if (previewLayoutId != 0) {
                tempSelections[currentCategory] = Pair(previewLayoutId, previewBgId)
                Toast.makeText(this, "${currentCategory.title} marked as (default)", Toast.LENGTH_SHORT).show()
                rvStyles.adapter?.notifyDataSetChanged()
            }
        }

        btnSaveAll.setOnClickListener { saveEverything() }

        bindService(
            Intent(this, PlayerForegroundService::class.java),
            connection, BIND_AUTO_CREATE
        )
        
        updateCategoryUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
    }

    private fun showCategoryPicker() {
        val popup = PopupMenu(this, btnPickComponent)
        UIComponent.entries.forEachIndexed { index, comp ->
            val isSelected = tempSelections.containsKey(comp)
            val marker = if (isSelected) " ✅" else ""
            popup.menu.add(0, index, index, "${comp.icon} ${comp.title}$marker")
        }
        popup.setOnMenuItemClickListener { item ->
            currentCategory = UIComponent.entries[item.itemId]
            updateCategoryUI()
            true
        }
        popup.show()
    }

    private fun updateCategoryUI() {
        tvSelectorTitle.text = "${currentCategory.icon} Choose ${currentCategory.title} Style"
        val filtered = allStyles.filter { it.category == currentCategory }
        
        val selection = tempSelections[currentCategory]
        // Defensive check: ensure the selection actually belongs to the current category
        val validSelection = selection?.let { sel -> 
            filtered.find { it.layoutId == sel.first && it.backgroundId == sel.second } 
        }
        
        previewLayoutId = validSelection?.layoutId ?: filtered.firstOrNull()?.layoutId ?: 0
        previewBgId = validSelection?.backgroundId ?: filtered.firstOrNull()?.backgroundId ?: 0

        // Reset picker state when changing category
        containerInlinePicker.visibility = View.GONE
        rvStyles.visibility = View.VISIBLE
        activeColorKey = null

        rvStyles.adapter = ThemeAdapter(filtered)
        sbPickerAlpha.progress = customAlphas[currentCategory] ?: 255
        updateColorBoxesUI()
        syncPreview()
    }

    private fun loadCustomColors() {
        val prefs = getSharedPreferences("ytplayer_prefs", Context.MODE_PRIVATE)
        UIComponent.entries.forEach { comp ->
            val map = mutableMapOf<String, Int?>()
            val baseKey = "canvas_${comp.name.lowercase()}_custom"
            
            map["bg_primary"] = if (prefs.contains("${baseKey}_bg")) prefs.getInt("${baseKey}_bg", 0) else null
            map["bg_secondary"] = if (prefs.contains("${baseKey}_bg_sec")) prefs.getInt("${baseKey}_bg_sec", 0) else null
            map["text_primary"] = if (prefs.contains("${baseKey}_text")) prefs.getInt("${baseKey}_text", 0) else null
            map["text_secondary"] = if (prefs.contains("${baseKey}_text_sec")) prefs.getInt("${baseKey}_text_sec", 0) else null
            
            customColors[comp] = map
            customAlphas[comp] = prefs.getInt("${baseKey}_alpha", 255)
        }
    }

    private fun updateColorBoxesUI() {
        colorPartsContainer.removeAllViews()
        val parts = mutableListOf<Pair<String, String>>()
        parts.add("Background" to "bg_primary")
        parts.add("Text" to "text_primary")
        
        if (previewLayoutId == R.layout.item_chat_bubble_boxed) {
            parts.add("User BG" to "bg_secondary")
            parts.add("User Text" to "text_secondary")
        }

        parts.forEach { (label, key) ->
            val boxView = layoutInflater.inflate(R.layout.item_color_box, colorPartsContainer, false)
            val colorView = boxView.findViewById<View>(R.id.view_color_preview)
            val labelView = boxView.findViewById<TextView>(R.id.tv_color_label)
            
            val currentColor = customColors[currentCategory]?.get(key) ?: Color.TRANSPARENT
            colorView.setBackgroundColor(if (currentColor == Color.TRANSPARENT) Color.GRAY else currentColor)
            labelView.text = label
            
            boxView.setOnClickListener {
                openPicker(label, key)
            }
            colorPartsContainer.addView(boxView)
        }
        
        val resetBtn = Button(this).apply {
            text = "Reset"
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * resources.displayMetrics.density).toInt()).apply {
                setMargins(16, 0, 0, 0)
            }
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.DKGRAY)
            setTextColor(Color.WHITE)
        }
        resetBtn.setOnClickListener {
            customColors[currentCategory]?.clear()
            customAlphas[currentCategory] = 255
            updateColorBoxesUI()
            syncPreview()
        }
        colorPartsContainer.addView(resetBtn)
    }

    private fun openPicker(title: String, key: String) {
        activeColorKey = key
        tvInlinePickerTitle.text = "🎨 Pick $title"
        containerInlinePicker.visibility = View.VISIBLE
        
        val currentColor = customColors[currentCategory]?.get(key) ?: Color.WHITE
        
        // FIX: Delay setting initial color until the view is laid out to avoid
        // "width and height must be > 0" crash in AlphaSliderView.onSizeChanged
        wheelColorPicker.post {
            if (activeColorKey == key) { // Ensure we are still picking the same color
                wheelColorPicker.setInitialColor(currentColor)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableLogic() {
        pickerHeader.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = containerInlinePicker.x - event.rawX
                    dY = containerInlinePicker.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    containerInlinePicker.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                }
            }
            true
        }
    }

    private fun setupPresets() {
        val presets = mapOf(
            R.id.preset_white to Color.WHITE,
            R.id.preset_black to Color.BLACK,
            R.id.preset_red to Color.RED,
            R.id.preset_green to Color.GREEN,
            R.id.preset_blue to Color.BLUE,
            R.id.preset_yellow to Color.YELLOW
        )
        presets.forEach { (id, color) ->
            findViewById<View>(id).setOnClickListener {
                wheelColorPicker.setInitialColor(color)
                activeColorKey?.let { key ->
                    val map = customColors.getOrPut(currentCategory) { mutableMapOf() }
                    map[key] = color
                    syncPreview()
                }
            }
        }
    }

    private fun setupAlphaLogic() {
        sbPickerAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    customAlphas[currentCategory] = progress
                    syncPreview()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun saveEverything() {
        val editor = getSharedPreferences("ytplayer_prefs", Context.MODE_PRIVATE).edit()
        
        UIComponent.entries.forEach { comp ->
            val stylesForComp = allStyles.filter { it.category == comp }
            val selection = tempSelections[comp]
            
            // Verify selection belongs to this category, otherwise use first available style
            val finalStyle = selection?.let { sel ->
                stylesForComp.find { it.layoutId == sel.first && it.backgroundId == sel.second }
            } ?: stylesForComp.firstOrNull()

            finalStyle?.let { s ->
                editor.putInt("canvas_${comp.name.lowercase()}_layout", s.layoutId)
                editor.putInt("canvas_${comp.name.lowercase()}_bg", s.backgroundId)
                
                // Update service immediately if bound
                when (comp) {
                    UIComponent.CHAT   -> service?.updateChatStyle(s.layoutId, s.backgroundId)
                    UIComponent.PLAYER -> service?.updatePlayerStyle(s.layoutId)
                    UIComponent.QUEUE  -> service?.updateQueueStyle(s.layoutId, s.itemLayoutId)
                    UIComponent.JOIN   -> service?.updateJoinStyle(s.layoutId)
                    UIComponent.LIKE   -> service?.updateLikeStyle(s.layoutId)
                    UIComponent.FOLLOW -> service?.updateFollowStyle(s.layoutId)
                    UIComponent.LYRICS -> service?.updateLyricsStyle(s.layoutId)
                    UIComponent.NOTIF  -> service?.updateNotifStyle(s.layoutId)
                }
            }
        }

        customColors.forEach { (comp, map) ->
            val baseKey = "canvas_${comp.name.lowercase()}_custom"
            map["bg_primary"]?.let { editor.putInt("${baseKey}_bg", it) } ?: editor.remove("${baseKey}_bg")
            map["bg_secondary"]?.let { editor.putInt("${baseKey}_bg_sec", it) } ?: editor.remove("${baseKey}_bg_sec")
            map["text_primary"]?.let { editor.putInt("${baseKey}_text", it) } ?: editor.remove("${baseKey}_text")
            map["text_secondary"]?.let { editor.putInt("${baseKey}_text_sec", it) } ?: editor.remove("${baseKey}_text_sec")
        }
        
        customAlphas.forEach { (comp, alpha) ->
            editor.putInt("canvas_${comp.name.lowercase()}_custom_alpha", alpha)
        }
        
        editor.apply()
        service?.updateCustomThemes()
        Toast.makeText(this, "All themes saved & applied!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun syncPreview() {
        previewFrame.removeAllViews()
        val inflater = LayoutInflater.from(this)
        
        if (previewLayoutId == 0) return

        try {
            val view = inflater.inflate(previewLayoutId, previewFrame, false)
            when (currentCategory) {
                UIComponent.CHAT -> {
                    view.findViewById<TextView>(R.id.tv_username)?.text = "Preview User"
                    view.findViewById<TextView>(R.id.tv_message)?.text = "Example chat message!"
                    val bubble = view.findViewById<View>(R.id.chat_bubble_container)
                    if (previewBgId != 0 && previewBgId != android.R.color.transparent) {
                        bubble?.setBackgroundResource(previewBgId)
                    } else if (previewBgId == android.R.color.transparent) {
                        bubble?.background = null
                    }
                }
                UIComponent.QUEUE -> {
                    view.findViewById<TextView>(R.id.overlay_queue_count_badge)?.text = "3"
                    view.findViewById<View>(R.id.overlay_queue_empty)?.visibility = View.GONE
                    view.layoutParams.width = (240 * resources.displayMetrics.density).toInt()
                }
                UIComponent.PLAYER -> {
                    view.findViewById<TextView>(R.id.overlay_title)?.text = "Example Playing Song"
                    view.layoutParams.width = (280 * resources.displayMetrics.density).toInt()
                }
                UIComponent.LYRICS -> {
                    view.findViewById<TextView>(R.id.overlay_lyrics_current)?.text = "Current lyrics line preview"
                    view.findViewById<TextView>(R.id.overlay_lyrics_prev)?.text = "Previous lyrics"
                    view.findViewById<TextView>(R.id.overlay_lyrics_next)?.text = "Next lyrics"
                    view.layoutParams.width = (280 * resources.displayMetrics.density).toInt()
                }
                UIComponent.NOTIF -> {
                    view.findViewById<TextView>(R.id.tiktok_notif_user)?.text = "Preview User"
                    view.findViewById<TextView>(R.id.tiktok_notif_action)?.text = "mengirim hadiah!"
                    view.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                UIComponent.JOIN -> {
                    view.findViewById<TextView>(R.id.join_user_text)?.text = "Preview User joined"
                    view.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                UIComponent.LIKE -> {
                    view.findViewById<TextView>(R.id.like_user_name)?.text = "Preview User"
                    view.findViewById<TextView>(R.id.like_count_text)?.text = "Tapped x99"
                    view.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.layoutParams.height = (200 * resources.displayMetrics.density).toInt()
                }
                UIComponent.FOLLOW -> {
                    view.findViewById<TextView>(R.id.follow_user_text)?.text = "Preview User followed"
                    view.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            previewFrame.addView(view)
            applyCustomColors(view)
        } catch (e: Exception) {
            val errorTv = TextView(this)
            errorTv.text = "Preview error"
            previewFrame.addView(errorTv)
        }
    }

    private fun applyCustomColors(view: View) {
        val map = customColors[currentCategory] ?: mutableMapOf()
        val bgAlpha = customAlphas[currentCategory] ?: 255
        
        val bgPrimary = map["bg_primary"]
        val bgSecondary = map["bg_secondary"]
        val textPrimary = map["text_primary"]
        val textSecondary = map["text_secondary"]

        when (currentCategory) {
            UIComponent.CHAT -> {
                val isBoxed = previewLayoutId == R.layout.item_chat_bubble_boxed
                val bubble = view.findViewById<View>(R.id.chat_bubble_container)
                val userTag = view.findViewById<View>(R.id.tv_username)
                val tvMsg = view.findViewById<TextView>(R.id.tv_message)

                bgPrimary?.let { color ->
                    val colorWithAlpha = Color.argb(bgAlpha, Color.red(color), Color.green(color), Color.blue(color))
                    bubble?.background?.let { bg ->
                        val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(bg.mutate())
                        androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, colorWithAlpha)
                        bubble.background = wrapped
                    } ?: run {
                        bubble?.setBackgroundColor(colorWithAlpha)
                    }
                } ?: run {
                    bubble?.background?.mutate()?.alpha = bgAlpha
                }

                if (isBoxed) {
                    bgSecondary?.let { color ->
                        val colorWithAlpha = Color.argb(bgAlpha, Color.red(color), Color.green(color), Color.blue(color))
                        userTag?.background?.let { bg ->
                            val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(bg.mutate())
                            androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, colorWithAlpha)
                            userTag.background = wrapped
                        } ?: run {
                            userTag?.setBackgroundColor(colorWithAlpha)
                        }
                    } ?: run {
                        userTag?.background?.mutate()?.alpha = bgAlpha
                    }
                }

                textPrimary?.let { tvMsg?.setTextColor(it) }
                textSecondary?.let { (userTag as? TextView)?.setTextColor(it) } ?: textPrimary?.let { (userTag as? TextView)?.setTextColor(it) }
            }
            UIComponent.QUEUE, UIComponent.PLAYER, UIComponent.LYRICS, UIComponent.NOTIF, UIComponent.JOIN, UIComponent.LIKE, UIComponent.FOLLOW -> {
                bgPrimary?.let { color ->
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
                
                val textIds = listOf(
                    R.id.overlay_queue_count_badge, R.id.overlay_queue_empty, R.id.overlay_title,
                    R.id.overlay_lyrics_current, R.id.overlay_lyrics_prev, R.id.overlay_lyrics_next,
                    R.id.overlay_lyrics_prefix, R.id.overlay_lyrics_suffix,
                    R.id.overlay_lyrics_time,
                    R.id.tiktok_notif_user, R.id.tiktok_notif_action,
                    R.id.join_user_text, R.id.like_user_name, R.id.like_count_text,
                    R.id.follow_user_text
                )
                textIds.forEach { id -> textPrimary?.let { view.findViewById<TextView>(id)?.setTextColor(it) } }
            }
        }
    }

    private inner class ThemeAdapter(
        private val styles: List<ThemeStyle>
    ) : RecyclerView.Adapter<ThemeAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v.findViewById(R.id.card_style)
            val name: TextView = v.findViewById(R.id.tv_style_name)
            val previewContainer: FrameLayout = v.findViewById(R.id.preview_container)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            layoutInflater.inflate(R.layout.item_chat_style_preview, parent, false)
        )

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = styles[pos]
            val picked = tempSelections[currentCategory]
            val isPicked = picked != null && picked.first == s.layoutId && picked.second == s.backgroundId
            h.name.text = if (isPicked) "${s.name} (default)" else s.name
            
            h.previewContainer.removeAllViews()
            try {
                val view = layoutInflater.inflate(s.layoutId, h.previewContainer, false)
                
                when (s.category) {
                    UIComponent.CHAT -> {
                        view.findViewById<TextView>(R.id.tv_username)?.text = "User"
                        view.findViewById<TextView>(R.id.tv_message)?.text = "Sample..."
                        val bubble = view.findViewById<View>(R.id.chat_bubble_container)
                        if (s.backgroundId != 0 && s.backgroundId != android.R.color.transparent) {
                            bubble?.setBackgroundResource(s.backgroundId)
                        } else if (s.backgroundId == android.R.color.transparent) {
                            bubble?.background = null
                        }
                    }
                    UIComponent.QUEUE -> {
                        view.findViewById<TextView>(R.id.overlay_queue_count_badge)?.text = "5"
                        view.findViewById<View>(R.id.overlay_queue_empty)?.visibility = View.GONE
                        view.layoutParams.width = (260 * resources.displayMetrics.density).toInt()
                    }
                    UIComponent.PLAYER -> {
                        view.findViewById<TextView>(R.id.overlay_title)?.text = "Example Playing Song"
                        view.findViewById<View>(R.id.overlay_expanded_section)?.visibility = View.VISIBLE
                        view.layoutParams.width = (260 * resources.displayMetrics.density).toInt()
                    }
                    UIComponent.LYRICS -> {
                        view.findViewById<TextView>(R.id.overlay_lyrics_current)?.text = "Lyrics..."
                        view.findViewById<View>(R.id.overlay_lyrics_prev)?.visibility = View.GONE
                        view.findViewById<View>(R.id.overlay_lyrics_next)?.visibility = View.GONE
                    }
                    UIComponent.NOTIF -> {
                        view.findViewById<TextView>(R.id.tiktok_notif_user)?.text = "User"
                        view.findViewById<TextView>(R.id.tiktok_notif_action)?.text = "Action..."
                    }
                    UIComponent.JOIN -> {
                        view.findViewById<TextView>(R.id.join_user_text)?.text = "User joined"
                    }
                    UIComponent.LIKE -> {
                        view.findViewById<TextView>(R.id.like_user_name)?.text = "User"
                        view.findViewById<TextView>(R.id.like_count_text)?.text = "x99"
                    }
                    UIComponent.FOLLOW -> {
                        view.findViewById<TextView>(R.id.follow_user_text)?.text = "User followed"
                    }
                }

                val isWide = s.category != UIComponent.CHAT && s.category != UIComponent.JOIN && s.category != UIComponent.LIKE
                val designWidth = if (isWide) (260 * resources.displayMetrics.density).toInt() 
                                 else ViewGroup.LayoutParams.WRAP_CONTENT
                
                val lp = FrameLayout.LayoutParams(designWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.gravity = android.view.Gravity.CENTER
                view.layoutParams = lp

                val scale = if (isWide) 0.35f else 0.6f
                view.scaleX = scale
                view.scaleY = scale
                
                h.previewContainer.addView(view)
            } catch (e: Exception) {
                val err = TextView(h.itemView.context)
                err.text = "Preview"
                err.textSize = 9f
                err.gravity = android.view.Gravity.CENTER
                h.previewContainer.addView(err)
            }
            
            val isPreviewed = s.layoutId == previewLayoutId && s.backgroundId == previewBgId
            h.card.strokeWidth = if (isPreviewed) (2 * resources.displayMetrics.density).toInt() else 0
            
            h.itemView.setOnClickListener {
                previewLayoutId = s.layoutId
                previewBgId = s.backgroundId
                updateColorBoxesUI()
                notifyDataSetChanged()
                syncPreview()
            }
        }

        override fun getItemCount() = styles.size
    }
}
