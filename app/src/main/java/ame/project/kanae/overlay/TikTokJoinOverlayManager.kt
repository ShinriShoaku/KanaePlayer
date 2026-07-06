package ame.project.kanae.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.TextView
import ame.project.kanae.R
import ame.project.kanae.model.CustomTheme
import android.graphics.Color
import com.bumptech.glide.Glide

class TikTokJoinOverlayManager(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var windowView: android.widget.FrameLayout? = null
    private var contentView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null

    private var lastX: Int = 100
    private var lastY: Int = 200
    private var lastScale: Float = 1.0f

    var onPositionChanged: ((x: Int, y: Int, scale: Float) -> Unit)? = null
    
    private var ivImage: ImageView? = null
    private var tvUser: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    var isShowing = false
        private set
    private var displayDurationMs = 4000L
    private var currentTheme = CustomTheme()

    fun setDuration(seconds: Int) {
        displayDurationMs = seconds.toLong() * 1000L
    }

    private val hideRunnable = Runnable { hideWithAnimation() }

    fun applyTheme(theme: CustomTheme) {
        this.currentTheme = theme
        if (isShowing) {
            handler.post { applyThemeToView(contentView) }
        }
    }

    private fun applyThemeToView(view: View?) {
        val v = view ?: return
        val theme = currentTheme
        val bgAlpha = theme.alpha
        
        theme.bgPrimary?.let { color ->
            val colorWithAlpha = Color.argb(bgAlpha, Color.red(color), Color.green(color), Color.blue(color))
            v.background?.let { bg ->
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(bg.mutate())
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, colorWithAlpha)
                v.background = wrapped
            } ?: run {
                v.setBackgroundColor(colorWithAlpha)
            }
        } ?: run {
            v.background?.mutate()?.alpha = bgAlpha
        }
        
        theme.textPrimary?.let { color ->
            tvUser?.setTextColor(color)
        }
    }

    fun showJoin(nickname: String, profileUrl: String?, isDummy: Boolean = false, persistent: Boolean = false) {
        handler.post {
            if (windowView == null) setupView()
            
            tvUser?.text = if (isDummy) "$nickname (Preview)" else "$nickname joined"
            profileUrl?.let {
                ivImage?.let { img -> 
                    Glide.with(context).load(it).circleCrop().into(img) 
                }
            } ?: run {
                ivImage?.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            // Apply posisi dan measurement SETELAH teks di-set agar ukurannya pas
            applyConfigInternal(lastX, lastY, lastScale)

            if (!isShowing) {
                try {
                    wm.addView(windowView, layoutParams)
                    isShowing = true
                    startFadeIn()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                // Jika sudah tampil, batalkan animasi fade out yang mungkin sedang berjalan
                contentView?.clearAnimation()
                contentView?.alpha = 1f
                handler.removeCallbacks(hideRunnable)
            }
            
            if (persistent) {
                handler.removeCallbacks(hideRunnable)
            } else {
                resetHideTimer()
            }
        }
    }

    fun resetHideTimer() {
        if (!isShowing) return
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, displayDurationMs)
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
        val window = windowView ?: return
        val content = contentView ?: return
        val lp = layoutParams ?: return

        lp.x = x
        lp.y = y

        // Pivot di pojok kiri atas untuk zoom effect
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale

        // Selalu gunakan WRAP_CONTENT untuk Join Overlay agar text tidak terpotong
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val actualW = content.measuredWidth
        val actualH = content.measuredHeight

        // Ukuran jendela mengikuti hasil scale (zoom)
        lp.width = (actualW * scale).toInt().coerceAtLeast(1)
        lp.height = (actualH * scale).toInt().coerceAtLeast(1)

        gestureHelper?.let {
            it.currentScale = scale
            it.updateBaseSize(actualW, actualH)
        }

        try { wm.updateViewLayout(window, lp) } catch (_: Exception) {}
    }

    fun setCanvasMode(locked: Boolean, x: Int = 0, y: Int = 0) {
        gestureHelper?.locked = locked
        val lp = layoutParams ?: return
        val window = windowView ?: return
        if (locked) {
            lp.x = x; lp.y = y
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                      WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        try { wm.updateViewLayout(window, lp) } catch (_: Exception) {}
    }

    private var currentLayoutId: Int = R.layout.overlay_tiktok_join

    fun updateStyle(layoutId: Int) {
        if (currentLayoutId != layoutId) {
            currentLayoutId = layoutId
            if (isShowing) {
                handler.post {
                    val wasShowing = isShowing
                    hide()
                    if (wasShowing) showJoin("Preview", null, isDummy = true)
                }
            } else {
                windowView = null // Force recreate on next show
            }
        }
    }

    private fun setupView() {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        
        // Buat FrameLayout sebagai window root untuk menghindari clipping saat scale
        val container = android.widget.FrameLayout(themed)
        val content = LayoutInflater.from(themed).inflate(currentLayoutId, container, false)
        container.addView(content)
        
        windowView = container
        contentView = content

        ivImage = content.findViewById(R.id.join_user_image)
        tvUser = content.findViewById(R.id.join_user_text)

        applyThemeToView(content)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
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

    private fun startFadeIn() {
        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 500 }
        contentView?.startAnimation(fadeIn)
    }

    private fun hideWithAnimation() {
        if (!isShowing) return
        val fadeOut = AlphaAnimation(1f, 0f).apply { 
            duration = 500 
            setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(a: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
                override fun onAnimationEnd(a: android.view.animation.Animation?) {
                    hide()
                }
            })
        }
        contentView?.startAnimation(fadeOut)
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
        isShowing = false
        windowView = null
        contentView = null
        ivImage = null
        tvUser = null
        gestureHelper = null
        layoutParams = null
    }
}
