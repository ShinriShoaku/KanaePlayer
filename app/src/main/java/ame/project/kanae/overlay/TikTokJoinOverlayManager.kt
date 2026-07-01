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
import com.bumptech.glide.Glide

class TikTokJoinOverlayManager(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureHelper: OverlayGestureHelper? = null
    
    private var ivImage: ImageView? = null
    private var tvUser: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    var isShowing = false
        private set
    private val displayDurationMs = 3000L

    private val hideRunnable = Runnable { hideWithAnimation() }

    fun showJoin(nickname: String, profileUrl: String?, isDummy: Boolean = false) {
        handler.post {
            if (rootView == null) setupView()
            
            tvUser?.text = if (isDummy) "$nickname (Preview)" else "$nickname joined"
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
                    startFadeIn()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                handler.removeCallbacks(hideRunnable)
            }
            
            if (!isDummy) {
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
                rootView = null // Force recreate on next show
            }
        }
    }

    private fun setupView() {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_YTTikTokPlayer)
        rootView = LayoutInflater.from(themed).inflate(currentLayoutId, null)
        ivImage = rootView?.findViewById(R.id.join_user_image)
        tvUser = rootView?.findViewById(R.id.join_user_text)

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

        gestureHelper = OverlayGestureHelper(rootView!!, layoutParams!!, wm)
        rootView?.setOnTouchListener(gestureHelper)
    }

    private fun startFadeIn() {
        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 500 }
        rootView?.startAnimation(fadeIn)
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
        rootView?.startAnimation(fadeOut)
    }

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
