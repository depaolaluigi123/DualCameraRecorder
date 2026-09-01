package com.dualcamerarecording.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * Tap-to-focus reticle with animation.
 * Shows a pulsing circle at the tap position.
 */
class FocusReticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var focusX: Float = -1f
    private var focusY: Float = -1f
    private var isAnimating: Boolean = false
    private var animationProgress: Float = 0f

    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        interpolator = DecelerateInterpolator()
        addUpdateListener { animation ->
            animationProgress = animation.animatedValue as Float
            if (animationProgress >= 0.8f) {
                // Fade out then reset
                if (animationProgress >= 0.95f) {
                    isAnimating = false
                    animation.cancel()
                }
            }
            invalidate()
        }
    }

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = 0xFFFFFFFF.toInt()
    }

    /**
     * Show focus reticle at the given position (in view coordinates).
     */
    fun showFocusAt(x: Float, y: Float) {
        focusX = x
        focusY = y
        isAnimating = true
        animationProgress = 0f
        animator.cancel()
        animator.start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isAnimating || focusX < 0 || focusY < 0) return

        val radius = 80f * (0.5f + animationProgress * 0.5f)
        val alpha = (255 * (1 - animationProgress * 0.5f)).toInt().coerceIn(0, 255)
        paint.alpha = alpha

        canvas.drawCircle(focusX, focusY, radius, paint)

        // Draw crosshairs
        val crossSize = 30f
        canvas.drawLine(focusX - crossSize, focusY, focusX + crossSize, focusY, paint)
        canvas.drawLine(focusX, focusY - crossSize, focusX, focusY + crossSize, paint)
    }

    fun release() {
        animator.cancel()
    }
}
