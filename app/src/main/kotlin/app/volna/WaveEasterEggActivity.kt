package app.volna

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class WaveEasterEggActivity : Activity() {
    private lateinit var scene: WaveScene
    private var progress = 1f
    private var animation: ValueAnimator? = null
    private var motion = true

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        motion = Prefs.get(this).getBoolean("motion", true) && ValueAnimator.areAnimatorsEnabled()
        progress = if (motion) state?.getFloat("wave_progress", 0f) ?: 0f else 1f
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        scene = WaveScene(this) { finish() }
        setContentView(scene)
        scene.seek(progress)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        if (progress >= 1f) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            progress = 1f
            scene.seek(progress)
            return
        }
        animation = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = ((1f - progress) * 7_600).roundToLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                scene.seek(progress)
            }
            start()
        }
    }

    override fun onPause() {
        animation?.cancel()
        animation = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat("wave_progress", progress)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(if (motion) android.R.anim.fade_in else 0, if (motion) android.R.anim.fade_out else 0)
    }
}

private class WaveScene(context: Context, close: () -> Unit) : FrameLayout(context) {
    private val water = WaveArtwork(context)
    private val closeButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_x)
        imageTintList = ColorStateList.valueOf(Color.rgb(217, 236, 255))
        contentDescription = "Закрыть"
        scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(0x3357AEFF),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x94121E32.toInt())
                setStroke(dp(1), 0x3D9ABCE5)
            },
            null
        )
        setOnClickListener { close() }
    }

    init {
        addView(water, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(closeButton, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            marginEnd = dp(16)
            topMargin = dp(16)
        })
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 28) {
                val cutout = insets.displayCutout
                val trailing = if (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                    cutout?.safeInsetLeft ?: 0 else cutout?.safeInsetRight ?: 0
                (closeButton.layoutParams as LayoutParams).apply {
                    marginEnd = dp(16) + trailing
                    topMargin = dp(16) + (cutout?.safeInsetTop ?: 0)
                }
                closeButton.requestLayout()
            }
            insets
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    fun seek(progress: Float) = water.seek(progress)
}

internal fun ease(from: Float, to: Float, value: Float): Float {
    val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
