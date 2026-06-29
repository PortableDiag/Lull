package com.lull.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.min

/**
 * A circular volume knob. Drag around the dial (open at the bottom) to set the level.
 * Reports changes via [onValueChange] as a 0..1 fraction.
 */
class VolumeKnobView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val startAngle = 135f
    private val sweep = 270f

    private var value = 0.5f
    var onValueChange: ((Float) -> Unit)? = null

    private var trackColor = Color.parseColor("#33808080")
    private var progressColor = Color.parseColor("#5B86E5")
    private var knobColor = Color.WHITE

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()

    fun setColors(progress: Int, track: Int, knob: Int) {
        progressColor = progress; trackColor = track; knobColor = knob; invalidate()
    }

    /** Sets the displayed value without firing the listener. */
    fun setValue(fraction: Float) {
        value = fraction.coerceIn(0f, 1f); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(14f)
        val size = min(width, height).toFloat()
        val stroke = dp(10f)
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f - pad
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        trackPaint.strokeWidth = stroke
        trackPaint.color = trackColor
        canvas.drawArc(arcRect, startAngle, sweep, false, trackPaint)

        progressPaint.strokeWidth = stroke
        progressPaint.color = progressColor
        canvas.drawArc(arcRect, startAngle, sweep * value, false, progressPaint)

        val a = Math.toRadians((startAngle + sweep * value).toDouble())
        val kx = cx + radius * Math.cos(a).toFloat()
        val ky = cy + radius * Math.sin(a).toFloat()
        knobPaint.color = knobColor
        canvas.drawCircle(kx, ky, dp(9f), knobPaint)
        progressPaint.style = Paint.Style.FILL
        canvas.drawCircle(kx, ky, dp(4f), progressPaint)
        progressPaint.style = Paint.Style.STROKE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        var deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        if (deg < 0) deg += 360f
        // Shift into the [135, 405] arc space.
        var shifted = deg
        if (shifted < startAngle) shifted += 360f
        val frac = when {
            shifted in startAngle..(startAngle + sweep) -> (shifted - startAngle) / sweep
            // Inside the bottom gap: snap to the nearer end.
            deg in 45f..90f -> 1f
            else -> 0f
        }
        value = frac.coerceIn(0f, 1f)
        invalidate()
        onValueChange?.invoke(value)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
