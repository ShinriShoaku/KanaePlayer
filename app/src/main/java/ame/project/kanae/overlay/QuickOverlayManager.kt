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
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ame.project.kanae.R
import ame.project.kanae.opengl.AnimationEffect
import ame.project.kanae.opengl.QuickAnimationView
import com.bumptech.glide.Glide

data class SoundButtonConfig(
    val id: String,
    val label: String,
    val audioUri: String?,
    var reactionText: String? = null,
    val reactionImageUri: String? = null,
    val layoutType: Int = 0,
    val autoHide: Boolean = true,
    val audioDurationMs: Long = 0,
    var posX: Int = 0,
    var posY: Int = 0,
    var scale: Float = 1.0f,
    val mappingType: Int = 0 // 0: Sound, 1: Overlay, 2: Animation
)

class QuickOverlayManager(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var panelExpanded: View? = null
    private var btnMinimized: View? = null
    private var rvButtons: RecyclerView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null

    private var isExpanded = false
    private var lastMinimizedX = 0
    private var lastMinimizedY = 500
    
    private var panelGravity = Gravity.CENTER
    private var autoHideEnabled = true
    
    private var soundButtons = mutableListOf<SoundButtonConfig>()
    var onSoundClicked: ((SoundButtonConfig) -> Unit)? = null
    var onPositionUpdated: ((String, Int, Int) -> Unit)? = null
    var onTextUpdated: ((String, String) -> Unit)? = null
    var onScaleUpdated: ((String, Float) -> Unit)? = null

    private var activeReactions = mutableMapOf<String, ReactionState>()
    private var adjustmentToolView: View? = null
    private var adjustingButtonId: String? = null

    private class ReactionState(
        val wrapper: FrameLayout,
        val content: View,
        val lp: WindowManager.LayoutParams,
        val gestureHelper: OverlayGestureHelper
    )

    fun setAutoHide(enabled: Boolean) {
        autoHideEnabled = enabled
    }

    fun showAnimation(config: SoundButtonConfig) {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val animView = QuickAnimationView(themed)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(animView, params)
            val effects = AnimationEffect.entries
            val selectedEffect = if (config.layoutType in effects.indices) effects[config.layoutType] else AnimationEffect.BURST
            animView.startAnimation(selectedEffect)
            animView.postDelayed({ try { wm.removeView(animView) } catch (_: Exception) {} }, 3000)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun setPosition(position: String) {
        panelGravity = when (position.uppercase()) {
            "TOP" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            "BOTTOM" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.CENTER
        }
        if (isExpanded) updateExpandedPosition()
    }

    fun updateButtons(list: List<SoundButtonConfig>) {
        soundButtons.clear()
        soundButtons.addAll(list)
        rvButtons?.adapter?.notifyDataSetChanged()
        rootView?.findViewById<View>(R.id.tv_empty_hint)?.visibility = if (soundButtons.isEmpty()) View.VISIBLE else View.GONE
    }

    fun show() {
        if (rootView != null) return
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_quick_sound, null)
        rootView = view
        btnMinimized = view.findViewById(R.id.btn_minimized)
        panelExpanded = view.findViewById(R.id.panel_expanded)
        rvButtons = view.findViewById(R.id.rv_sound_buttons)
        val spanCount = context.resources.getInteger(R.integer.quick_overlay_span_count)
        rvButtons?.layoutManager = GridLayoutManager(themed, spanCount)
        rvButtons?.adapter = SoundButtonAdapter()
        view.findViewById<View>(R.id.btn_close).setOnClickListener { toggleExpand() }
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = lastMinimizedX; y = lastMinimizedY }
        gestureHelper = OverlayGestureHelper(view, layoutParams!!, wm, onSingleTap = { toggleExpand() }).also { 
            it.onInteraction = { if (!isExpanded) snapToEdge() }
            btnMinimized?.setOnTouchListener(it)
        }
        wm.addView(view, layoutParams)
    }

    fun showReaction(config: SoundButtonConfig, isAdjustMode: Boolean = false) {
        if (config.reactionText == null && config.reactionImageUri == null) return
        
        activeReactions[config.id]?.let {
            val shouldToggleOff = !isAdjustMode || adjustingButtonId == config.id
            if (shouldToggleOff) {
                try { wm.removeView(it.wrapper) } catch (_: Exception) {}
                activeReactions.remove(config.id)
                if (adjustingButtonId == config.id) {
                    hideAdjustmentTools(); adjustingButtonId = null
                    onPositionUpdated?.invoke(config.id, config.posX, config.posY)
                }
                rvButtons?.adapter?.notifyDataSetChanged()
                return
            } else {
                try { wm.removeView(it.wrapper) } catch (_: Exception) {}
                activeReactions.remove(config.id)
            }
        }

        if (isAdjustMode) {
            adjustingButtonId = config.id
            showAdjustmentTools(config)
        }

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val container = FrameLayout(themed)
        val content = LayoutInflater.from(themed).inflate(R.layout.overlay_quick_reaction, container, false)
        container.addView(content)
        container.clipChildren = false
        container.clipToPadding = false

        val tv = content.findViewById<TextView>(R.id.reaction_text)
        val iv = content.findViewById<ImageView>(R.id.reaction_image)
        val card = content as com.google.android.material.card.MaterialCardView
        content.findViewById<View>(R.id.btn_edit_text).visibility = View.GONE

        // Restore layout styles
        when (config.layoutType) {
            1 -> { // Sketch
                card.setCardBackgroundColor(android.graphics.Color.WHITE)
                card.strokeColor = android.graphics.Color.BLACK
                card.strokeWidth = (4 * context.resources.displayMetrics.density).toInt()
                card.cardElevation = 0f
                tv.setTextColor(android.graphics.Color.BLACK)
            }
            2 -> { // Glass
                card.setCardBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
                card.strokeColor = android.graphics.Color.parseColor("#66FFFFFF")
                card.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
                card.cardElevation = 0f
                tv.setTextColor(android.graphics.Color.WHITE)
            }
            3 -> { // Transparent
                card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                card.strokeWidth = 0
                card.cardElevation = 0f
                tv.setTextColor(android.graphics.Color.WHITE)
                // Optional: Beri bayangan teks agar tetap terbaca di background terang
                tv.setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
            }
            else -> tv.setTextColor(android.graphics.Color.WHITE)
        }

        if (config.reactionText != null) {
            tv.visibility = View.VISIBLE
            tv.text = config.reactionText
        }
        if (config.reactionImageUri != null) {
            iv.visibility = View.VISIBLE
            Glide.with(context).load(config.reactionImageUri).into(iv)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (isAdjustMode) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (config.posX != 0) config.posX else 100
            y = if (config.posY != 0) config.posY else 200
        }

        val reactionGestureHelper = OverlayGestureHelper(container, lp, wm).apply {
            currentScale = config.scale
            onInteraction = {
                config.posX = lp.x
                config.posY = lp.y
                config.scale = currentScale
                onPositionUpdated?.invoke(config.id, lp.x, lp.y)
                onScaleUpdated?.invoke(config.id, currentScale)
                updateToolPosition(lp.x, lp.y)
            }
        }
        
        if (isAdjustMode) {
            container.setOnTouchListener(reactionGestureHelper)
        }

        try {
            wm.addView(container, lp)
            val state = ReactionState(container, content, lp, reactionGestureHelper)
            activeReactions[config.id] = state
            
            content.post { applyScaleInternal(state, config.scale) }
            
            rvButtons?.adapter?.notifyDataSetChanged()
            if (config.autoHide && !isAdjustMode) {
                val delay = if (config.audioDurationMs > 0) config.audioDurationMs else 10000L
                container.postDelayed({
                    try { wm.removeView(container); activeReactions.remove(config.id)
                        rvButtons?.adapter?.notifyDataSetChanged()
                    } catch (_: Exception) {}
                }, delay)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun applyScaleInternal(state: ReactionState, scale: Float) {
        state.content.pivotX = 0f
        state.content.pivotY = 0f
        state.content.scaleX = scale
        state.content.scaleY = scale

        state.content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = state.content.measuredWidth
        val h = state.content.measuredHeight

        if (w > 0 && h > 0) {
            state.gestureHelper.currentScale = scale
            state.gestureHelper.updateBaseSize(w, h)
            
            // Re-apply extra buffer because MaterialCardView needs it for stroke/shadow
            val buffer = (16 * context.resources.displayMetrics.density).toInt()
            state.lp.width += buffer
            state.lp.height += buffer
            try { wm.updateViewLayout(state.wrapper, state.lp) } catch (_: Exception) {}
        }
    }

    fun applyScale(config: SoundButtonConfig) {
        val state = activeReactions[config.id] ?: return
        applyScaleInternal(state, config.scale)
        onScaleUpdated?.invoke(config.id, config.scale)
    }

    private fun snapToEdge() {
        if (isExpanded) return
        val lp = layoutParams ?: return
        val screenWidth = context.resources.displayMetrics.widthPixels
        lp.x = if (lp.x < screenWidth / 2) 0 else screenWidth - (btnMinimized?.width ?: 0)
        lastMinimizedX = lp.x; lastMinimizedY = lp.y
        try { wm.updateViewLayout(rootView, lp) } catch (_: Exception) {}
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        btnMinimized?.visibility = if (isExpanded) View.GONE else View.VISIBLE
        panelExpanded?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        updateExpandedPosition()
    }

    private fun updateExpandedPosition() {
        layoutParams?.let { lp ->
            val density = context.resources.displayMetrics.density
            if (isExpanded) {
                val spanCount = context.resources.getInteger(R.integer.quick_overlay_span_count)
                (rvButtons?.layoutManager as? GridLayoutManager)?.spanCount = spanCount
                val panelWidthRes = context.resources.getDimensionPixelSize(R.dimen.quick_overlay_panel_width)
                lastMinimizedX = lp.x; lastMinimizedY = lp.y
                lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or 
                           WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                lp.width = if (panelWidthRes > 0) panelWidthRes else WindowManager.LayoutParams.MATCH_PARENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                lp.gravity = panelGravity
                lp.x = 0; lp.y = if (panelGravity and Gravity.TOP == Gravity.TOP) (60 * density).toInt() 
                       else if (panelGravity and Gravity.BOTTOM == Gravity.BOTTOM) (20 * density).toInt() else 0
            } else {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = lastMinimizedX; lp.y = lastMinimizedY
                snapToEdge()
            }
            try { wm.updateViewLayout(rootView, lp) } catch (_: Exception) {}
        }
    }

    fun hide() {
        rootView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        rootView = null
    }

    fun isShowing(): Boolean = rootView != null

    private fun showEditDialog(config: SoundButtonConfig, tv: TextView?) {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val et = android.widget.EditText(themed).apply {
            setText(config.reactionText ?: "")
            setTextColor(android.graphics.Color.WHITE)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(themed)
            .setTitle("Edit Reaction Text").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val newText = et.text.toString()
                tv?.text = newText; tv?.visibility = if (newText.isEmpty()) View.GONE else View.VISIBLE
                config.reactionText = newText
                onTextUpdated?.invoke(config.id, newText)
            }
            .setNegativeButton("Cancel", null).create().apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                show()
            }
    }

    private fun showAdjustmentTools(config: SoundButtonConfig) {
        hideAdjustmentTools()
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        val layout = android.widget.LinearLayout(themed).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_lyrics_minimal)
            setPadding(16, 8, 16, 8); gravity = Gravity.CENTER
        }
        val btnMinus = ImageButton(themed).apply {
            setImageResource(android.R.drawable.btn_minus); setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { config.scale = (config.scale - 0.1f).coerceAtLeast(0.5f); applyScale(config) }
        }
        val btnPlus = ImageButton(themed).apply {
            setImageResource(android.R.drawable.ic_input_add); setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { config.scale = (config.scale + 0.1f).coerceAtMost(3.0f); applyScale(config) }
        }
        val btnEdit = ImageButton(themed).apply {
            setImageResource(android.R.drawable.ic_menu_edit); setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener {
                val state = activeReactions[config.id]
                val tv = state?.content?.findViewById<TextView>(R.id.reaction_text)
                showEditDialog(config, tv)
            }
        }
        layout.addView(btnMinus); layout.addView(btnPlus); layout.addView(btnEdit)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = config.posX; y = config.posY - 150 }
        try { wm.addView(layout, params); adjustmentToolView = layout } catch (e: Exception) { e.printStackTrace() }
    }

    private fun updateToolPosition(x: Int, y: Int) {
        adjustmentToolView?.let { tool ->
            val lp = tool.layoutParams as WindowManager.LayoutParams
            lp.x = x; lp.y = y - 150
            try { wm.updateViewLayout(tool, lp) } catch (_: Exception) {}
        }
    }

    private fun hideAdjustmentTools() {
        adjustmentToolView?.let { try { wm.removeView(it) } catch (_: Exception) {}; adjustmentToolView = null }
    }

    private inner class SoundButtonAdapter : RecyclerView.Adapter<SoundButtonAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sound_button, parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val cfg = soundButtons[position]
            holder.tvLabel.text = cfg.label
            val isActive = activeReactions.containsKey(cfg.id)
            val isAdjusting = adjustingButtonId == cfg.id
            val card = holder.itemView as com.google.android.material.card.MaterialCardView
            if (isAdjusting) {
                card.setCardBackgroundColor(android.graphics.Color.parseColor("#4DFFD700"))
                card.strokeColor = android.graphics.Color.parseColor("#FFD700")
            } else if (isActive) {
                card.setCardBackgroundColor(android.graphics.Color.parseColor("#4D00FF00"))
                card.strokeColor = android.graphics.Color.GREEN
            } else {
                card.setCardBackgroundColor(android.graphics.Color.parseColor("#26FFFFFF"))
                card.strokeColor = android.graphics.Color.parseColor("#33FFFFFF")
            }
            holder.itemView.setOnClickListener {
                if (adjustingButtonId != null && adjustingButtonId != cfg.id) return@setOnClickListener
                when (cfg.mappingType) {
                    0 -> onSoundClicked?.invoke(cfg)
                    1 -> showReaction(cfg)
                    2 -> showAnimation(cfg)
                }
            }
            holder.itemView.setOnLongClickListener {
                if (cfg.reactionText != null || cfg.reactionImageUri != null) {
                    showReaction(cfg, isAdjustMode = true)
                }
                true
            }
        }
        override fun getItemCount() = soundButtons.size
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvLabel: TextView = v.findViewById(R.id.tv_sound_label)
        }
    }
}
