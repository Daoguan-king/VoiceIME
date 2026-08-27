package com.voiceime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max

/**
 * 录音电平动画：一排圆角竖条，随实时音量起伏并带流动相位。
 * - 录音中：电平条跟随 RMS 音量，动态流动；
 * - 未录音：显示一排静止的低矮小条。
 */
class LevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val barCount = 24
    private val levels = FloatArray(barCount) { IDLE_LEVEL }

    @Volatile
    private var target = IDLE_LEVEL

    @Volatile
    private var recording = false

    private var animator: ValueAnimator? = null

    fun setRecording(recording: Boolean) {
        this.recording = recording
        if (recording) {
            target = IDLE_TARGET
            startAnimator()
        } else {
            target = IDLE_LEVEL
            stopAnimator()
            levels.fill(IDLE_LEVEL)
            invalidate()
        }
    }

    /** 实时音量 0..1，由录音线程每帧喂入 */
    fun setLevel(level: Float) {
        if (!recording) return
        target = (level.coerceIn(0f, 1f) * TARGET_RATIO + TARGET_FLOOR).coerceAtMost(1f)
    }

    private fun startAnimator() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val phase = it.animatedValue as Float
                for (i in levels.indices) {
                    // 流动相位 + 平滑追赶音量
                    val wave = ((phase * WAVE_SPEED + i * PHASE_STEP) % 1f)
                    val noise = abs(wave - 0.5f) * NOISE_AMPLITUDE + NOISE_FLOOR
                    val desired = (target * noise).coerceIn(MIN_BAR_LEVEL, 1f)
                    levels[i] += (desired - levels[i]) * SMOOTHING
                }
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gap = w / (barCount * GAP_RATIO)
        val barW = gap * BAR_WIDTH_RATIO
        val maxH = h * MAX_HEIGHT_RATIO

        // 整组电平条水平居中
        val clusterW = barCount * gap
        val startX = (w - clusterW) / 2f

        barPaint.shader = LinearGradient(
            0f, h, 0f, 0f,
            BAR_COLOR, BAR_COLOR_TOP, Shader.TileMode.CLAMP,
        )
        for (i in levels.indices) {
            val x = startX + gap * 0.5f + i * gap
            val bh = max(MIN_BAR_HEIGHT_PX, levels[i] * maxH)
            val y = (h - bh) / 2f
            canvas.drawRoundRect(x, y, x + barW, y + bh, barW / 2f, barW / 2f, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimator()
    }

    companion object {
        private const val IDLE_LEVEL = 0.08f
        private const val IDLE_TARGET = 0.25f
        private const val TARGET_RATIO = 0.9f
        private const val TARGET_FLOOR = 0.1f
        private const val ANIMATION_DURATION_MS = 120L
        private const val WAVE_SPEED = 6f
        private const val PHASE_STEP = 0.55f
        private const val NOISE_AMPLITUDE = 1.2f
        private const val NOISE_FLOOR = 0.15f
        private const val MIN_BAR_LEVEL = 0.08f
        private const val SMOOTHING = 0.3f
        private const val GAP_RATIO = 1.5f
        private const val BAR_WIDTH_RATIO = 0.6f
        private const val MAX_HEIGHT_RATIO = 0.92f
        private const val MIN_BAR_HEIGHT_PX = 3f
        private const val BAR_COLOR = 0xFF8A5CF6.toInt()
        private const val BAR_COLOR_TOP = 0xFFB39DFF.toInt()
    }
}
