package com.dualcamerarecording.audio.view

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.log10

/**
 * Base for the two meter styles. Holds the animated level in [0,1]
 * (0 = -60 dBFS, 1 = 0 dBFS) and resolves theme colors.
 *
 * Animation is driven by a [ValueAnimator] with a 120 ms DecelerateInterpolator
 * so the needle/segments glide smoothly between samples instead of jumping
 * per buffer — this is what makes the meters "reliable, not jumpy/too fast".
 *
 * Adapted from MicGainLevelerApp MeterViewBase.
 */
abstract class MeterViewBase @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Animated level in [0,1] — subclasses draw from this in onDraw. */
    protected var animatedLevel = 0f
    private var animator: ValueAnimator? = null

    /** Live target in dB; kept so setLevel()/setValue() adapters can read it back. */
    protected var targetDb: Float = FLOOR_DB

    /** Resolve a theme attribute (e.g. R.attr.meterTrackColor) to a color int. */
    protected fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    /** Set the current level in dB; animates smoothly to the new level. */
    open fun setDb(db: Float) {
        targetDb = db
        val target = dbToLevel(db)
        if (target == animatedLevel) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animatedLevel, target).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animatedLevel = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Set the level immediately without animation. */
    open fun setDbImmediate(db: Float) {
        targetDb = db
        animator?.cancel()
        animatedLevel = dbToLevel(db)
        invalidate()
    }

    // --- Compatibility adapters for the existing Activity call sites ---
    // The old API took a dB value via setLevel(); keep it working by routing to setDb.
    fun setLevel(levelDb: Float) = setDb(levelDb)

    fun getLevel(): Float = animatedLevel

    companion object {
        const val FLOOR_DB = -60f
        const val HEADROOM_DB = 6f

        /** Maps a dB value (-Inf..0) to [0,1] over a -60..0 dB scale. */
        fun dbToLevel(db: Float): Float {
            if (db.isNaN()) return 0f
            if (db <= FLOOR_DB) return 0f
            if (db >= 0f) return 1f
            return ((db - FLOOR_DB) / (0f - FLOOR_DB)).coerceIn(0f, 1f)
        }

        fun linearToDb(linear: Float): Float {
            if (linear <= 0f) return Float.NEGATIVE_INFINITY
            return 20f * log10(linear)
        }
    }
}
