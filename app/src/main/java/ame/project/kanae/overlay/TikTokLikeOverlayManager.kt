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
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import ame.project.kanae.R
import com.bumptech.glide.Glide
import java.util.Random

class TikTokLikeOverlayManager(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null
    
    private var bubbleContainer: FrameLayout? = null
    private var ivImage: ImageView? = null
    private var tvUser: TextView? = null
    private var tvCount: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    var isShowing = false
        private set
    private val displayDurationMs = 4000L
    private val random = Random()

    private val hideRunnable = Runnable { hide() }

    fun showLike(nickname: String, count: Int, profileUrl: String?, isDummy: Boolean = false) {
        handler.post {
            if (rootView == null) setupView()
            
            tvUser?.text = nickname
            tvCount?.text = if (isDummy) "Tapped (Preview)" else "Tapped x$count"
            
            profileUrl?.let {
                ivImage?.let { img -> 
                    Glide.with(context).load(it).circleCrop().into(img) 
                }
            } ?: run {
                ivImage?.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            if (!isShowing) {
                try {
                    wm.addView(rootView, layoutParams)
                    isShowing = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            if (!isDummy) {
                // Spawn bubbles based on count (but capped to avoid lag)
                val spawnCount = if (count > 5) 5 else if (count < 1) 1 else count
                for (i in 0 until spawnCount) {
                    spawnBubble()
                }

                handler.removeCallbacks(hideRunnable)
                handler.postDelayed(hideRunnable, displayDurationMs)
            }
        }
    }

    fun applyConfig(x: Int, y: Int, scale: Float, wDp: Int = 0, hDp: Int = 0) {
        if (rootView == null) setupView()
        val lp = layoutParams ?: return
        val view = rootView ?: return

        lp.x = x
        lp.y = y

        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = scale
        view.scaleY = scale

        val density = context.resources.displayMetrics.density
        val baseW = if (wDp > 0) (wDp * density).toInt() else -2 
        val baseH = if (hDp > 0) (hDp * density).toInt() else -2

        view.measure(
            if (baseW > 0) View.MeasureSpec.makeMeasureSpec(baseW, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            if (baseH > 0) View.MeasureSpec.makeMeasureSpec(baseH, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val actualW = if (baseW > 0) baseW else view.measuredWidth
        val actualH = if (baseH > 0) baseH else view.measuredHeight

        lp.width = (actualW * scale).toInt()
        lp.height = (actualH * scale).toInt()

        gestureHelper?.let {
            it.currentScale = scale
            it.updateBaseSize(actualW, actualH)
        }

        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    fun setCanvasMode(locked: Boolean, x: Int = 0, y: Int = 0) {
        gestureHelper?.locked = locked
        val lp = layoutParams ?: return
        val view = rootView ?: return
        if (locked) {
            lp.x = x; lp.y = y
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                      WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
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
                rootView = null // Force recreate on next show
            }
        }
    }

    private fun setupView() {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        rootView = LayoutInflater.from(themed).inflate(currentLayoutId, null)
        bubbleContainer = rootView?.findViewById(R.id.like_bubble_container)
        ivImage = rootView?.findViewById(R.id.like_user_image)
        tvUser = rootView?.findViewById(R.id.like_user_name)
        tvCount = rootView?.findViewById(R.id.like_count_text)

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

        gestureHelper = OverlayGestureHelper(rootView!!, layoutParams!!, wm)
        rootView?.setOnTouchListener(gestureHelper)
    }

    private fun spawnBubble() {
        val container = bubbleContainer ?: return
        val heart = ImageView(context).apply {
            setImageResource(R.drawable.ic_heart)
            val size = (30 + random.nextInt(20)).dpToPx()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            alpha = 0f
        }
        container.addView(heart)

        val startX = (random.nextFloat() - 0.5f) * 100f // range -50 to 50
        val endY = -(200 + random.nextInt(100)).dpToPx().toFloat()
        
        heart.translationX = startX
        
        val fadeIn = ObjectAnimator.ofFloat(heart, View.ALPHA, 0f, 1f).setDuration(200)
        val moveUp = ObjectAnimator.ofFloat(heart, View.TRANSLATION_Y, 0f, endY).setDuration(1500L + random.nextInt(1000))
        val fadeOut = ObjectAnimator.ofFloat(heart, View.ALPHA, 1f, 0f).setDuration(500)
        fadeOut.startDelay = moveUp.duration - 500
        
        val sway = ObjectAnimator.ofFloat(heart, View.TRANSLATION_X, startX, startX + (random.nextFloat() - 0.5f) * 60f)
        sway.duration = moveUp.duration
        
        val set = AnimatorSet()
        set.playTogether(fadeIn, moveUp, fadeOut, sway)
        set.interpolator = AccelerateDecelerateInterpolator()
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
        if (isShowing && rootView != null) {
            try {
                wm.removeView(rootView)
                isShowing = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
