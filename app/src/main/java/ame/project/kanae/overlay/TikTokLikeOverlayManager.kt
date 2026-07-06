package ame.project.kanae.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import ame.project.kanae.R
import ame.project.kanae.model.CustomTheme
import android.graphics.Color
import com.bumptech.glide.Glide
import java.util.Random

class TikTokLikeOverlayManager(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var windowView: FrameLayout? = null
    private var contentView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null

    // Separate window for animations (inflated from item_like_bubble.xml)
    private var animWindowView: ViewGroup? = null
    private var animBubbleContainer: FrameLayout? = null
    private var animLayoutParams: WindowManager.LayoutParams? = null
    private var isAnimShowing = false
    private var animationEnabled = true

    // Simpan posisi terakhir agar tidak reset saat hide/show
    private var lastX: Int = 50
    private var lastY: Int = 500
    private var lastScale: Float = 1.0f

    /** Callback saat user menggeser overlay secara manual */
    var onPositionChanged: ((x: Int, y: Int, scale: Float) -> Unit)? = null

    private var ivImage: ImageView? = null
    private var tvUser: TextView? = null
    private var tvCount: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    var isShowing = false
        private set
    private var displayDurationMs = 4000L
    private var currentTheme = CustomTheme()

    fun setDuration(seconds: Int) {
        displayDurationMs = seconds.toLong() * 1000L
    }

    private val random = Random()
    private val heartTints = intArrayOf(
        0xFFFF4081.toInt(), // pink
        0xFFFF1744.toInt(), // red
        0xFFFF80AB.toInt(), // light pink
        0xFFFF6E40.toInt()  // orange-red
    )

    private val hideRunnable = Runnable { hide() }

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
            tvCount?.setTextColor(color)
        }
    }

    fun showLike(nickname: String, count: Int, profileUrl: String?, isDummy: Boolean = false, persistent: Boolean = false) {
        handler.post {
            if (windowView == null) setupView()

            tvUser?.text = nickname
            tvCount?.text = if (isDummy) "Tapped (Preview)" else "Tapped x$count"

            // Window HARUS sudah attach ke WindowManager sebelum kita panggil
            // updateViewLayout (di dalam applyConfigInternal), kalau tidak
            // panggilan itu gagal diam-diam dan ukuran window jadi salah.
            if (!isShowing) {
                try {
                    wm.addView(windowView, layoutParams)
                    isShowing = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            loadProfileImage(profileUrl)

            // Apply posisi dan measurement awal (perkiraan, sebelum gambar
            // profil selesai di-load). loadProfileImage() akan memanggil
            // applyConfigInternal lagi setelah gambar benar-benar siap,
            // supaya window ikut resize dan gambar+nama tidak terpotong.
            applyConfigInternal(lastX, lastY, lastScale)

            // Spawn bubbles based on count (but capped to avoid lag)
            if (!isDummy && animationEnabled) {
                showAnimationWindow()
                val spawnCount = if (count > 5) 5 else if (count < 1) 1 else count
                for (i in 0 until spawnCount) {
                    handler.postDelayed({ spawnBubble() }, i * 120L + random.nextInt(60))
                }
            }

            if (persistent) {
                handler.removeCallbacks(hideRunnable)
            } else {
                resetHideTimer()
            }
        }
    }

    private fun loadProfileImage(profileUrl: String?) {
        val img = ivImage ?: return

        if (profileUrl.isNullOrEmpty()) {
            img.setImageResource(android.R.drawable.ic_menu_gallery)
            return
        }

        Glide.with(context)
            .load(profileUrl)
            .circleCrop()
            .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: com.bumptech.glide.load.engine.GlideException?,
                    model: Any?,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    // Gambar gagal dimuat: tetap resize window supaya
                    // nama/count tidak ikut kepotong.
                    handler.post { applyConfigInternal(lastX, lastY, lastScale) }
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                    dataSource: com.bumptech.glide.load.DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    // Gambar baru selesai dimuat (async) SETELAH window
                    // pertama kali di-measure/resize. Resize ulang di sini
                    // supaya window mengikuti ukuran konten yang sudah
                    // termasuk gambar profil, bukan ukuran lama (kosong).
                    handler.post { applyConfigInternal(lastX, lastY, lastScale) }
                    return false
                }
            })
            .into(img)
    }

    fun setAnimationEnabled(enabled: Boolean) {
        this.animationEnabled = enabled
        if (!enabled) hideAnimationWindow()
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

        // Selalu gunakan WRAP_CONTENT untuk Like Overlay agar text tidak terpotong
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

        try {
            wm.updateViewLayout(window, lp)
            updateAnimationWindowPos()
        } catch (_: Exception) {}
    }

    private fun showAnimationWindow() {
        if (!animationEnabled || isAnimShowing) return
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

        // Positioned above the card, same X and same scaled width
        alp.x = lp.x
        // We want it to be above the card. The card height is lp.height
        // Let's give the animation window a fixed height of 250dp scaled
        val density = context.resources.displayMetrics.density
        val baseAnimH = (250 * density).toInt()

        alp.width = lp.width
        alp.height = (baseAnimH * lastScale).toInt()
        alp.y = lp.y - alp.height

        if (isAnimShowing) {
            try { wm.updateViewLayout(window, alp) } catch (_: Exception) {}
        }
    }

    private fun setupAnimationWindow() {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)

        // Inflate the dedicated bubble layout instead of building it in code
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.item_like_bubble, null, false) as ViewGroup
        animWindowView = root
        animBubbleContainer = root.findViewById(R.id.like_bubble_container)

        animLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
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

    private var currentLayoutId: Int = R.layout.overlay_tiktok_like

    fun updateStyle(layoutId: Int) {
        if (currentLayoutId != layoutId) {
            currentLayoutId = layoutId
            if (isShowing) {
                handler.post {
                    val wasShowing = isShowing
                    hide()
                    if (wasShowing) showLike("Preview", 1, null, isDummy = true)
                }
            } else {
                windowView = null // Force recreate on next show
            }
        }
    }

    private fun setupView() {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)

        // Buat FrameLayout sebagai window root untuk menghindari clipping saat scale
        val container = FrameLayout(themed)
        val content = LayoutInflater.from(themed).inflate(currentLayoutId, container, false)
        container.addView(content)

        windowView = container
        contentView = content

        ivImage = content.findViewById(R.id.like_user_image)
        tvUser = content.findViewById(R.id.like_user_name)
        tvCount = content.findViewById(R.id.like_count_text)

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
            x = 50
            y = 500
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

    private fun spawnBubble() {
        val container = animBubbleContainer ?: return
        val heart = ImageView(context).apply {
            setImageResource(R.drawable.ic_heart)
            setColorFilter(heartTints[random.nextInt(heartTints.size)], android.graphics.PorterDuff.Mode.SRC_IN)
            val size = (26 + random.nextInt(22)).dpToPx()
            // We scale the heart size by lastScale too
            val scaledSize = (size * lastScale).toInt().coerceAtLeast(1)
            layoutParams = FrameLayout.LayoutParams(scaledSize, scaledSize).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            alpha = 0f
            scaleX = 0.3f
            scaleY = 0.3f
        }
        container.addView(heart)

        val startX = (random.nextFloat() - 0.5f) * 90f * lastScale
        val midX1 = startX + (random.nextFloat() - 0.5f) * 60f * lastScale
        val midX2 = startX + (random.nextFloat() - 0.5f) * 60f * lastScale
        val endX = startX + (random.nextFloat() - 0.5f) * 40f * lastScale
        val endY = -(190 + random.nextInt(90)).dpToPx().toFloat() * lastScale
        val duration = 1300L + random.nextInt(700)
        val rotation = (random.nextFloat() - 0.5f) * 30f

        heart.translationX = startX

        // Pop-in "bounce" like TikTok hearts, instead of a flat fade
        val popScaleX = ObjectAnimator.ofFloat(heart, View.SCALE_X, 0.3f, 1.15f, 1f).setDuration(320)
        val popScaleY = ObjectAnimator.ofFloat(heart, View.SCALE_Y, 0.3f, 1.15f, 1f).setDuration(320)
        popScaleX.interpolator = android.view.animation.OvershootInterpolator(2.5f)
        popScaleY.interpolator = android.view.animation.OvershootInterpolator(2.5f)

        val fadeIn = ObjectAnimator.ofFloat(heart, View.ALPHA, 0f, 1f).setDuration(180)
        val fadeOut = ObjectAnimator.ofFloat(heart, View.ALPHA, 1f, 0f).setDuration(350)
        fadeOut.startDelay = duration - 350

        val moveUp = ObjectAnimator.ofFloat(heart, View.TRANSLATION_Y, 0f, endY).setDuration(duration)
        moveUp.interpolator = android.view.animation.DecelerateInterpolator(1.2f)

        // Gentle S-curve wiggle instead of one straight sway
        val sway = ObjectAnimator.ofFloat(heart, View.TRANSLATION_X, startX, midX1, midX2, endX).setDuration(duration)
        sway.interpolator = AccelerateDecelerateInterpolator()

        val spin = ObjectAnimator.ofFloat(heart, View.ROTATION, 0f, rotation).setDuration(duration)
        spin.interpolator = AccelerateDecelerateInterpolator()

        val set = AnimatorSet()
        set.playTogether(popScaleX, popScaleY, fadeIn, moveUp, fadeOut, sway, spin)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                container.removeView(heart)
            }
        })
        set.start()
    }

    private fun Int.dpToPx(): Int = (this * context.resources.displayMetrics.density).toInt()

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        hideAnimationWindow()
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
        tvCount = null
        gestureHelper = null
        layoutParams = null
    }
}