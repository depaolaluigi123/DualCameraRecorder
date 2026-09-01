package com.dualcamerarecording.audio.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import com.dualcamerarecording.R
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Analog VU / tape-style meter: an arc scale plate with ticks and a pivoting needle.
 * Theme colors are resolved from the theme attributes (meterTrackColor, meterSafeColor,
 * meterWarnColor, meterPeakColor, meterNeedleColor, meterScaleColor).
 *
 * Adapted from MicGainLevelerApp AnalogMeterView.
 */
class AnalogMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MeterViewBase(context, attrs, defStyleAttr) {

    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val safePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcBounds = RectF()

    /**
     * Minimum needle level (in [0,1]) so the tip never drops below the horizontal
     * bottom edge of the visible meter. Computed in onSizeChanged.
     */
    private var minNeedleLevel = 0f

    init {
        reloadThemeColors()
    }

    fun reloadThemeColors() {
        trackPaint.color = resolveThemeColor(R.attr.meterTrackColor)
        safePaint.color = resolveThemeColor(R.attr.meterSafeColor)
        warnPaint.color = resolveThemeColor(R.attr.meterWarnColor)
        peakPaint.color = resolveThemeColor(R.attr.meterPeakColor)
        needlePaint.color = resolveThemeColor(R.attr.meterNeedleColor)
        scalePaint.color = resolveThemeColor(R.attr.meterScaleColor)
        platePaint.color = resolveThemeColor(R.attr.meterTrackColor)
        invalidate()
    }

    /** Adapter kept for the existing Activity call sites (takes a dB value). */
    fun setValue(valueDb: Float) {
        setDb(valueDb.coerceIn(FLOOR_DB, 0f))
    }

    companion object {
        private const val START_ANGLE = 150f
        private const val SWEEP_ANGLE = 240f
        private const val PIVOT_REL_Y = 0.92f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = min(w, h) * 0.42f
        val pivotX = w / 2f
        val pivotY = h * PIVOT_REL_Y
        arcBounds.set(pivotX - radius, pivotY - radius, pivotX + radius, pivotY + radius)
        val stroke = min(w, h) * 0.035f
        trackPaint.strokeWidth = stroke
        safePaint.strokeWidth = stroke
        warnPaint.strokeWidth = stroke
        peakPaint.strokeWidth = stroke
        platePaint.strokeWidth = stroke * 0.5f
        needlePaint.strokeWidth = stroke * 0.6f

        val needleLen = radius - min(w, h) * 0.16f
        val sinMax = (h - pivotY) / needleLen
        minNeedleLevel = if (sinMax >= 1f) 0f
        else ((180f - Math.toDegrees(asin(sinMax.toDouble())).toFloat()) - START_ANGLE) / SWEEP_ANGLE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pivotX = w / 2f
        val pivotY = h * PIVOT_REL_Y
        val radius = min(w, h) * 0.42f

        // Background plate arc (thin track)
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, platePaint)

        // Color zones along the arc: 0..0.7 safe, 0.7..0.85 warn, 0.85..1 peak.
        drawZone(canvas, 0f, 0.7f, safePaint)
        drawZone(canvas, 0.7f, 0.85f, warnPaint)
        drawZone(canvas, 0.85f, 1f, peakPaint)

        // Tick marks + scale numbers
        scalePaint.textSize = min(w, h) * 0.085f
        scalePaint.textAlign = Paint.Align.CENTER
        val ticks = listOf(
            -60 to "-60", -50 to "-50", -40 to "-40",
            -30 to "-30", -20 to "-20", -10 to "-10",
            -6 to "-6", 0 to "0"
        )
        for ((db, label) in ticks) {
            val t = dbToLevel(db.toFloat())
            val angle = START_ANGLE + t * SWEEP_ANGLE
            val rad = Math.toRadians(angle.toDouble())
            val r1 = radius
            val r2 = radius - min(w, h) * 0.06f
            val cos = cos(rad).toFloat()
            val sin = sin(rad).toFloat()
            canvas.drawLine(
                pivotX + cos * r1, pivotY + sin * r1,
                pivotX + cos * r2, pivotY + sin * r2, scalePaint
            )
            val r3 = radius - min(w, h) * 0.13f
            canvas.drawText(label, pivotX + cos * r3, pivotY + sin * r3, scalePaint)
        }

        // Needle — clamp to minNeedleLevel so the tip stays above the bottom line.
        val needleAngle = START_ANGLE + max(animatedLevel, minNeedleLevel) * SWEEP_ANGLE
        val rad = Math.toRadians(needleAngle.toDouble())
        val nx = pivotX + cos(rad) * (radius - min(w, h) * 0.16f)
        val ny = pivotY + sin(rad) * (radius - min(w, h) * 0.16f)
        canvas.drawLine(pivotX, pivotY, nx.toFloat(), ny.toFloat(), needlePaint)
        // Pivot cap
        canvas.drawCircle(pivotX, pivotY, min(w, h) * 0.04f, needlePaint)
    }

    private fun drawZone(canvas: Canvas, fromT: Float, toT: Float, paint: Paint) {
        val start = START_ANGLE + fromT * SWEEP_ANGLE
        val sweep = (toT - fromT) * SWEEP_ANGLE
        if (sweep > 0f) canvas.drawArc(arcBounds, start, sweep, false, paint)
    }
}
