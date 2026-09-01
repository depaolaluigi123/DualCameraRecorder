package com.dualcamerarecording.audio.view

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import com.dualcamerarecording.R

/**
 * DAW-style segmented level meter. Vertical stack of lit segments; green / yellow / red
 * zones based on the dB scale, with a peak-hold marker that holds 1s then decays.
 * Theme colors are resolved from the theme attributes.
 *
 * In [brighterMode] (used in the fullscreen preview window where the meters are
 * small and sit on top of camera video) segments are drawn wider, with a soft
 * glow halo behind each lit segment, so the level is readable against the moving
 * picture. Default rendering is unchanged.
 *
 * Adapted from MicGainLevelerApp DigitalMeterView.
 */
class DigitalMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MeterViewBase(context, attrs, defStyleAttr) {

    private val segmentCount = 22

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val safePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val segRect = RectF()
    private val glowRect = RectF()

    private var peakHoldLevel = 0f
    private var peakHoldAtMs = 0L

    /**
     * When true, segments are drawn wider and lit segments get a soft glow halo
     * behind them so the meter stays readable on top of the fullscreen video.
     */
    private var brighterMode = false

    init {
        reloadThemeColors()
    }

    fun reloadThemeColors() {
        trackPaint.color = resolveThemeColor(R.attr.meterTrackColor)
        safePaint.color = resolveThemeColor(R.attr.meterSafeColor)
        warnPaint.color = resolveThemeColor(R.attr.meterWarnColor)
        peakPaint.color = resolveThemeColor(R.attr.meterPeakColor)
        invalidate()
    }

    /**
     * Toggle brighter rendering for small / on-top-of-video use (e.g. the
     * fullscreen preview window). Wider lit segments + a colored glow halo make
     * the level easier to read against the live picture.
     */
    fun setBrighterMode(enabled: Boolean) {
        if (brighterMode == enabled) return
        brighterMode = enabled
        invalidate()
    }

    override fun setDbImmediate(db: Float) {
        val level = dbToLevel(db)
        if (level > peakHoldLevel) {
            peakHoldLevel = level
            peakHoldAtMs = SystemClock.uptimeMillis()
        }
        super.setDbImmediate(db)
    }

    override fun setDb(db: Float) {
        val level = dbToLevel(db)
        if (level > peakHoldLevel) {
            peakHoldLevel = level
            peakHoldAtMs = SystemClock.uptimeMillis()
        }
        super.setDb(db)
    }

    /** Adapter kept for the existing Activity call sites (takes a dB value). */
    fun setLevelWithText(levelDb: Float, showText: Boolean = true) {
        setDb(levelDb.coerceIn(FLOOR_DB, 0f))
    }

    /** Reset the peak-hold marker (used when a new recording starts). */
    fun resetPeakHold() {
        peakHoldLevel = 0f
        peakHoldAtMs = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Decay peak hold: hold 1.0s then fall at ~0.25 level/s.
        val now = SystemClock.uptimeMillis()
        if (peakHoldLevel > 0f && now - peakHoldAtMs > 1000L) {
            val dt = (now - peakHoldAtMs - 1000L).coerceAtLeast(0L) / 1000f
            peakHoldLevel = (peakHoldLevel - dt * 0.25f).coerceAtLeast(0f)
            peakHoldAtMs = now - 1000L
        }

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // In brighter mode use slightly tighter gaps so the wider segments fill
        // the meter more completely.
        val gapScale = if (brighterMode) 1.4f else 1f
        val gap = 2f * (w / 24f).coerceIn(1f, 3f) * gapScale
        val segH = (h - gap * (segmentCount - 1)) / segmentCount
        val segW = if (brighterMode) w * 0.92f else w * 0.7f
        val xc = w / 2f

        val lit = (animatedLevel * segmentCount).toInt().coerceIn(0, segmentCount)

        // In brighter mode attach a soft glow halo to lit segments so they pop
        // against the live video behind the meter.
        if (brighterMode) {
            val haloRadius = (segH * 0.9f).coerceAtLeast(3f)
            glowPaint.maskFilter = BlurMaskFilter(haloRadius, BlurMaskFilter.Blur.NORMAL)
        } else {
            glowPaint.maskFilter = null
        }

        for (i in 0 until segmentCount) {
            val fromBottom = i
            val yTop = h - (fromBottom + 1) * segH - fromBottom * gap
            segRect.set(xc - segW / 2f, yTop, xc + segW / 2f, yTop + segH)
            val litThis = fromBottom < lit
            val ratio = i.toFloat() / (segmentCount - 1)
            val paint = if (ratio >= 0.86f) peakPaint else if (ratio >= 0.7f) warnPaint else safePaint
            if (litThis) {
                if (brighterMode) {
                    // Halo: a slightly bigger, soft-blurred copy of the segment in
                    // the same color — this is what makes the meter look "lit up"
                    // against the background. Draw the halo first, then the solid
                    // segment on top.
                    val pad = segH * 0.35f
                    glowRect.set(
                        segRect.left - pad,
                        segRect.top - pad,
                        segRect.right + pad,
                        segRect.bottom + pad
                    )
                    glowPaint.color = paint.color
                    canvas.drawRoundRect(glowRect, segH, segH, glowPaint)
                }
                canvas.drawRoundRect(segRect, segH / 3f, segH / 3f, paint)
            } else {
                canvas.drawRoundRect(segRect, segH / 3f, segH / 3f, trackPaint)
            }
        }

        // Peak-hold marker
        if (peakHoldLevel > 0f) {
            val yPeak = h - peakHoldLevel * h
            val markerW = segW + 6f
            peakPaint.style = Paint.Style.FILL
            canvas.drawRect(xc - markerW / 2f, yPeak - 2f, xc + markerW / 2f, yPeak + 2f, peakPaint)
            peakPaint.style = Paint.Style.FILL_AND_STROKE
        }
    }
}
