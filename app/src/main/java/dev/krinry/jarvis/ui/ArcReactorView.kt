package dev.krinry.jarvis.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * ArcReactorView — Authentic Iron Man Arc Reactor Holographic HUD View.
 *
 * Visual Components:
 * - High-tech dark titanium outer casing with luminous cyan ring
 * - 10 rotating electromagnetic segment teeth (like Tony Stark's Mark VII chest piece)
 * - Concentric glowing plasma rings
 * - Pure white-hot luminous center core
 * - Dynamic state-based animations: IDLE, LISTENING, THINKING, SPEAKING
 */
class ArcReactorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING
    }

    private var currentState = State.IDLE
    private var rotationAngle = 0f
    private var pulseAlpha = 0.8f
    private var pulseScale = 1.0f

    private var rotationAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    // Paints
    private val casingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0C141F")
    }

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#00F0FF")
    }

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFF")
    }

    private val glowBlurMask = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)

    init {
        startIdleAnimation()
    }

    fun setState(state: State) {
        if (currentState == state) return
        currentState = state

        rotationAnimator?.cancel()
        pulseAnimator?.cancel()

        when (state) {
            State.IDLE -> {
                segmentPaint.color = Color.parseColor("#00E5FF")
                outerRingPaint.color = Color.parseColor("#00F0FF")
                startIdleAnimation()
            }
            State.LISTENING -> {
                segmentPaint.color = Color.parseColor("#00FFFF")
                outerRingPaint.color = Color.parseColor("#80FFFF")
                startActiveAnimation(speedMs = 3000L)
            }
            State.THINKING -> {
                segmentPaint.color = Color.parseColor("#FFB300") // Stark gold overdrive
                outerRingPaint.color = Color.parseColor("#FFD54F")
                startActiveAnimation(speedMs = 1200L)
            }
            State.SPEAKING -> {
                segmentPaint.color = Color.parseColor("#00E5FF")
                outerRingPaint.color = Color.parseColor("#00F0FF")
                startSpeakingAnimation()
            }
        }
        invalidate()
    }

    private fun startIdleAnimation() {
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 12000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        pulseAnimator = ValueAnimator.ofFloat(0.6f, 0.95f).apply {
            duration = 1800L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulseAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startActiveAnimation(speedMs: Long) {
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = speedMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        pulseAnimator = ValueAnimator.ofFloat(0.85f, 1.15f).apply {
            duration = 450L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulseScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startSpeakingAnimation() {
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        pulseAnimator = ValueAnimator.ofFloat(0.7f, 1.2f).apply {
            duration = 300L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulseScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = Math.min(cx, cy) * 0.92f

        // 1. Dark titanium outer casing
        canvas.drawCircle(cx, cy, radius, casingPaint)

        // 2. Outer glowing ring
        outerRingPaint.alpha = (255 * pulseAlpha).toInt().coerceIn(40, 255)
        canvas.drawCircle(cx, cy, radius - 2f, outerRingPaint)

        // 3. Rotating 10 Arc Reactor segments / electromagnetic coils
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)

        val segmentRadius = radius * 0.76f
        val innerSegmentRadius = radius * 0.52f
        val segmentCount = 10
        val sweepAngle = 360f / segmentCount

        for (i in 0 until segmentCount) {
            val angleDeg = i * sweepAngle
            val rad = Math.toRadians(angleDeg.toDouble())

            val x1 = cx + (segmentRadius * Math.cos(rad)).toFloat()
            val y1 = cy + (segmentRadius * Math.sin(rad)).toFloat()
            val x2 = cx + (innerSegmentRadius * Math.cos(rad)).toFloat()
            val y2 = cy + (innerSegmentRadius * Math.sin(rad)).toFloat()

            val coilPaint = Paint(segmentPaint).apply {
                strokeWidth = radius * 0.09f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                alpha = (220 * pulseAlpha).toInt().coerceIn(30, 255)
            }
            canvas.drawLine(x1, y1, x2, y2, coilPaint)
        }
        canvas.restore()

        // 4. Concentric glowing plasma circle
        val coreRadius = radius * 0.44f * pulseScale
        innerGlowPaint.shader = RadialGradient(
            cx, cy, coreRadius,
            intArrayOf(
                Color.parseColor("#FFFFFF"),
                if (currentState == State.THINKING) Color.parseColor("#FFB300") else Color.parseColor("#00E5FF"),
                Color.parseColor("#003366"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 0.85f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, innerGlowPaint)

        // 5. White-hot center core
        val centerCoreRadius = radius * 0.18f
        corePaint.alpha = (255 * pulseAlpha).toInt().coerceIn(100, 255)
        canvas.drawCircle(cx, cy, centerCoreRadius, corePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rotationAnimator?.cancel()
        pulseAnimator?.cancel()
    }
}
