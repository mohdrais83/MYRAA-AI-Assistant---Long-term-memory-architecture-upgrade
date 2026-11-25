package com.myraa.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class TitanCoreOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellEdgePaint = strokePaint(dp(1.2f))
    private val orbitPaint = strokePaint(dp(1.5f))
    private val orbitHighlightPaint = strokePaint(dp(0.7f))
    private val effectPaint = strokePaint(dp(2f))
    private val coreGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reflectionPaint = strokePaint(dp(1.1f))
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPath = Path()
    private val orbitBounds = RectF()
    private val thinkingBounds = RectF()
    private val reflectionBounds = RectF()
    private val particles = arrayOf(
        floatArrayOf(-1.48f, -0.46f, 0.8f),
        floatArrayOf(-1.27f, 0.51f, 0.55f),
        floatArrayOf(-0.88f, -1.02f, 0.65f),
        floatArrayOf(-0.58f, 1.22f, 0.45f),
        floatArrayOf(0.64f, -1.18f, 0.55f),
        floatArrayOf(1.17f, -0.67f, 0.7f),
        floatArrayOf(1.44f, 0.35f, 0.5f),
        floatArrayOf(0.92f, 1.02f, 0.6f),
    )

    private var orbRadius = 0f
    private var shellShader: Shader? = null
    private var shellEdgeShader: Shader? = null
    private var haloShader: Shader? = null
    private var orbitalShader: Shader? = null
    private var coreShader: Shader? = null
    private var activityVisible = false
    private var state = VoiceInteractionState.IDLE
    private var lastFrameTime = 0L
    private var phaseSeconds = 0.0
    private var frameAnimated = false
    private var transitionProgress = 1f
    private var transitionFromColor = stateTint(VoiceInteractionState.IDLE)
    private var transitionToColor = transitionFromColor

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        contentDescription = context.getString(
            R.string.orb_accessibility_description,
            context.getString(R.string.voice_state_idle).lowercase(Locale.ROOT),
        )
    }

    fun setInteractionState(nextState: VoiceInteractionState) {
        if (state == nextState) return
        transitionFromColor = blend(transitionFromColor, transitionToColor, eased(transitionProgress))
        state = nextState
        transitionToColor = stateTint(nextState)
        transitionProgress = if (canAnimate()) 0f else 1f
        lastFrameTime = 0L
        contentDescription = context.getString(
            R.string.orb_accessibility_description,
            context.getString(stateLabel(nextState)).lowercase(Locale.ROOT),
        )
        invalidate()
    }

    fun setActivityVisible(visible: Boolean) {
        if (activityVisible == visible) return
        activityVisible = visible
        lastFrameTime = 0L
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val centerX = width / 2f
        val centerY = height / 2f
        orbRadius = min(width * 0.24f, height * 0.34f)
        shellShader = RadialGradient(
            centerX - orbRadius * 0.28f,
            centerY - orbRadius * 0.38f,
            orbRadius * 1.72f,
            intArrayOf(
                Color.argb(198, 48, 91, 139),
                Color.argb(204, 19, 43, 78),
                Color.argb(224, 8, 20, 42),
                Color.argb(240, 5, 13, 30),
            ),
            floatArrayOf(0f, 0.42f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        haloShader = RadialGradient(
            centerX,
            centerY,
            orbRadius * 1.6f,
            intArrayOf(Color.argb(74, 25, 142, 255), Color.argb(18, 19, 91, 192), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        orbitalShader = LinearGradient(
            centerX - orbRadius * 1.6f,
            centerY,
            centerX + orbRadius * 1.6f,
            centerY,
            intArrayOf(
                Color.rgb(48, 59, 73),
                Color.rgb(203, 213, 223),
                Color.rgb(92, 111, 130),
                Color.rgb(227, 188, 104),
                Color.rgb(66, 78, 91),
            ),
            floatArrayOf(0f, 0.25f, 0.52f, 0.76f, 1f),
            Shader.TileMode.CLAMP,
        )
        shellEdgeShader = LinearGradient(
            centerX - orbRadius,
            centerY - orbRadius,
            centerX + orbRadius,
            centerY + orbRadius,
            intArrayOf(
                Color.argb(218, 164, 213, 255),
                Color.argb(54, 42, 87, 136),
                Color.argb(142, 151, 190, 220),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        coreShader = LinearGradient(
            centerX - orbRadius * 0.39f,
            centerY - orbRadius * 0.42f,
            centerX + orbRadius * 0.39f,
            centerY + orbRadius * 0.42f,
            intArrayOf(Color.rgb(255, 246, 198), Color.rgb(255, 202, 72), Color.rgb(227, 127, 24)),
            null,
            Shader.TileMode.CLAMP,
        )
        shellPaint.shader = shellShader
        coreGlowPaint.shader = RadialGradient(
            centerX,
            centerY,
            orbRadius * 0.55f,
            intArrayOf(Color.argb(228, 255, 202, 82), Color.argb(95, 255, 168, 42), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameTime = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        lastFrameTime = 0L
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrameTime = 0L
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        lastFrameTime = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val animated = canAnimate()
        frameAnimated = animated
        val now = SystemClock.uptimeMillis()
        if (animated) {
            val delta = if (lastFrameTime == 0L) 0.0 else
                ((now - lastFrameTime).coerceAtLeast(0L).coerceAtMost(MAX_FRAME_DELTA_MS) / 1000.0)
            phaseSeconds += delta
            if (transitionProgress < 1f) {
                transitionProgress = (transitionProgress + (delta / TRANSITION_SECONDS).toFloat()).coerceAtMost(1f)
            }
            lastFrameTime = now
        } else {
            lastFrameTime = 0L
            if (ValueAnimator.areAnimatorsEnabled().not()) transitionProgress = 1f
        }

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = orbRadius
        if (radius <= 0f) return

        drawOuterHalo(canvas, centerX, centerY, radius)
        drawParticles(canvas, centerX, centerY, radius)
        drawOrbit(canvas, centerX, centerY, radius, 34f, 1.52f, 0.36f, false)
        drawOrbit(canvas, centerX, centerY, radius, -49f, 1.43f, 0.57f, false)
        drawStateEffects(canvas, centerX, centerY, radius)
        drawShell(canvas, centerX, centerY, radius)
        drawOrbit(canvas, centerX, centerY, radius, 78f, 1.37f, 0.3f, true)
        drawStateCoreEffect(canvas, centerX, centerY, radius)
        drawCore(canvas, centerX, centerY, radius)

        if (animated && (isContinuousState(state) || transitionProgress < 1f)) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawOuterHalo(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val tint = blend(transitionFromColor, transitionToColor, eased(transitionProgress))
        val glowAlpha = when (state) {
            VoiceInteractionState.LISTENING, VoiceInteractionState.THINKING -> 36
            VoiceInteractionState.SPEAKING -> 48
            VoiceInteractionState.ERROR, VoiceInteractionState.PERMISSION_REQUIRED -> 18
            else -> 24
        }
        canvas.drawCircle(cx, cy, radius * 1.12f, coreGlowPaint)
        shellTintPaint.shader = haloShader
        shellTintPaint.color = Color.argb(glowAlpha, Color.red(tint), Color.green(tint), Color.blue(tint))
        canvas.drawCircle(cx, cy, radius * 1.34f, shellTintPaint)
        shellTintPaint.shader = null
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        particlePaint.color = Color.argb(104, 119, 207, 255)
        particles.forEachIndexed { index, particle ->
            val twinkle = if (frameAnimated) {
                (0.72 + 0.28 * sin(phaseSeconds * 1.3 + index * 0.91)).toFloat()
            } else {
                0.8f
            }
            particlePaint.alpha = (104 * twinkle).toInt()
            canvas.drawCircle(
                cx + particle[0] * radius,
                cy + particle[1] * radius,
                dp(particle[2]),
                particlePaint,
            )
        }
    }

    private fun drawOrbit(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        angle: Float,
        horizontalScale: Float,
        verticalScale: Float,
        foreground: Boolean,
    ) {
        val spin = if (state == VoiceInteractionState.THINKING && foreground) {
            (phaseSeconds * 7.5).toFloat()
        } else {
            0f
        }
        val alpha = if (foreground) 214 else 182
        orbitPaint.shader = orbitalShader
        orbitPaint.alpha = alpha
        orbitHighlightPaint.shader = orbitalShader
        orbitHighlightPaint.alpha = if (foreground) 202 else 125

        val save = canvas.save()
        canvas.rotate(angle + spin, cx, cy)
        orbitBounds.set(
            cx - radius * horizontalScale,
            cy - radius * verticalScale,
            cx + radius * horizontalScale,
            cy + radius * verticalScale,
        )
        canvas.drawOval(orbitBounds, orbitPaint)
        canvas.drawArc(orbitBounds, 204f, 38f, false, orbitHighlightPaint)
        drawOrbitTicks(canvas, cx, cy, radius, horizontalScale, verticalScale)
        canvas.restoreToCount(save)
        orbitPaint.alpha = 255
        orbitHighlightPaint.alpha = 255
    }

    private fun drawOrbitTicks(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        horizontalScale: Float,
        verticalScale: Float,
    ) {
        reflectionPaint.shader = orbitalShader
        reflectionPaint.alpha = 210
        for (index in 0 until 8) {
            val angle = index * (PI / 4.0)
            val x = cx + (radius * horizontalScale * kotlin.math.cos(angle)).toFloat()
            val y = cy + (radius * verticalScale * kotlin.math.sin(angle)).toFloat()
            val dx = (kotlin.math.cos(angle) * dp(3f)).toFloat()
            val dy = (kotlin.math.sin(angle) * dp(3f)).toFloat()
            canvas.drawLine(x - dx, y - dy, x + dx, y + dy, reflectionPaint)
        }
        reflectionPaint.shader = null
    }

    private fun drawStateEffects(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        when (state) {
            VoiceInteractionState.LISTENING -> drawListeningWaves(canvas, cx, cy, radius)
            VoiceInteractionState.SPEAKING -> drawSpeakingPulse(canvas, cx, cy, radius)
            VoiceInteractionState.THINKING -> drawThinkingEnergy(canvas, cx, cy, radius)
            else -> Unit
        }
    }

    private fun drawListeningWaves(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val phase = (phaseSeconds / LISTENING_WAVE_SECONDS).toFloat()
        repeat(3) { index ->
            val progress = (phase + index / 3f) % 1f
            effectPaint.shader = null
            effectPaint.color = Color.argb(
                ((1f - progress) * 186f).toInt(),
                42,
                174,
                255,
            )
            effectPaint.strokeWidth = dp(if (index == 0) 2f else 1.25f)
            canvas.drawCircle(cx, cy, radius * (1.12f + progress * 0.56f), effectPaint)
        }
    }

    private fun drawSpeakingPulse(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val pulse = ((sin(phaseSeconds * 2.0 * PI / SPEAKING_PULSE_SECONDS) + 1.0) * 0.5).toFloat()
        effectPaint.shader = null
        effectPaint.color = Color.argb((92 + pulse * 120).toInt(), 57, 178, 255)
        effectPaint.strokeWidth = dp(1.7f + pulse * 1.1f)
        canvas.drawCircle(cx, cy, radius * (1.11f + pulse * 0.13f), effectPaint)
        effectPaint.color = Color.argb((74 + pulse * 122).toInt(), 255, 193, 72)
        effectPaint.strokeWidth = dp(2.2f)
        canvas.drawCircle(cx, cy, radius * (0.48f + pulse * 0.07f), effectPaint)
    }

    private fun drawThinkingEnergy(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val rotation = (phaseSeconds * 46.0).toFloat()
        val save = canvas.save()
        canvas.rotate(rotation, cx, cy)
        effectPaint.shader = orbitalShader
        effectPaint.alpha = 225
        effectPaint.strokeWidth = dp(2f)
        val energyBounds = RectF(
            cx - radius * 0.8f,
            cy - radius * 0.35f,
            cx + radius * 0.8f,
            cy + radius * 0.35f,
        )
        canvas.drawArc(energyBounds, 198f, 128f, false, effectPaint)
        canvas.restoreToCount(save)
        val secondSave = canvas.save()
        canvas.rotate(-rotation * 0.68f, cx, cy)
        effectPaint.shader = null
        effectPaint.color = Color.argb(220, 48, 181, 255)
        effectPaint.strokeWidth = dp(1.4f)
        thinkingBounds.set(
            cx - radius * 0.67f,
            cy - radius * 0.5f,
            cx + radius * 0.67f,
            cy + radius * 0.5f,
        )
        canvas.drawArc(
            thinkingBounds,
            18f,
            88f,
            false,
            effectPaint,
        )
        canvas.restoreToCount(secondSave)
        effectPaint.alpha = 255
    }

    private fun drawShell(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        shellPaint.shader = shellShader
        canvas.drawCircle(cx, cy, radius, shellPaint)
        val tint = blend(transitionFromColor, transitionToColor, eased(transitionProgress))
        shellTintPaint.shader = null
        shellTintPaint.color = Color.argb(24, Color.red(tint), Color.green(tint), Color.blue(tint))
        canvas.drawCircle(cx, cy, radius, shellTintPaint)

        shellEdgePaint.shader = shellEdgeShader
        canvas.drawCircle(cx, cy, radius, shellEdgePaint)
        shellEdgePaint.shader = null

        reflectionPaint.shader = null
        reflectionPaint.color = Color.argb(108, 183, 226, 255)
        reflectionPaint.strokeWidth = dp(1.4f)
        reflectionBounds.set(
            cx - radius * 0.88f,
            cy - radius * 0.91f,
            cx + radius * 0.88f,
            cy + radius * 0.91f,
        )
        canvas.drawArc(
            reflectionBounds,
            205f,
            62f,
            false,
            reflectionPaint,
        )
        reflectionPaint.color = Color.argb(50, 201, 230, 255)
        reflectionPaint.strokeWidth = dp(3f)
        reflectionBounds.set(
            cx - radius * 0.72f,
            cy - radius * 0.78f,
            cx + radius * 0.72f,
            cy + radius * 0.78f,
        )
        canvas.drawArc(
            reflectionBounds,
            214f,
            34f,
            false,
            reflectionPaint,
        )
        drawGlassFacet(canvas, cx, cy, radius)
    }

    private fun drawGlassFacet(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        orbPath.reset()
        orbPath.moveTo(cx - radius * 0.73f, cy - radius * 0.03f)
        orbPath.lineTo(cx - radius * 0.46f, cy - radius * 0.62f)
        orbPath.lineTo(cx - radius * 0.12f, cy - radius * 0.73f)
        orbPath.lineTo(cx - radius * 0.48f, cy - radius * 0.12f)
        orbPath.close()
        reflectionPaint.style = Paint.Style.FILL
        reflectionPaint.color = Color.argb(23, 148, 212, 255)
        canvas.drawPath(orbPath, reflectionPaint)
        reflectionPaint.style = Paint.Style.STROKE
    }

    private fun drawStateCoreEffect(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val motion = frameAnimated
        val pulse = when (state) {
            VoiceInteractionState.IDLE -> if (motion) {
                ((sin(phaseSeconds * 2.0 * PI / IDLE_PULSE_SECONDS) + 1.0) * 0.5).toFloat()
            } else {
                0.5f
            }
            VoiceInteractionState.SPEAKING -> if (motion) {
                ((sin(phaseSeconds * 2.0 * PI / SPEAKING_PULSE_SECONDS) + 1.0) * 0.5).toFloat()
            } else {
                0.7f
            }
            else -> 0.5f
        }
        val radiusScale = if (state == VoiceInteractionState.IDLE || state == VoiceInteractionState.SPEAKING) {
            0.86f + pulse * 0.14f
        } else {
            0.9f
        }
        coreGlowPaint.alpha = (158 + pulse * 90).toInt()
        canvas.drawCircle(cx, cy, radius * 0.56f * radiusScale, coreGlowPaint)
        coreGlowPaint.alpha = 255
    }

    private fun drawCore(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val pulse = if (
            (state == VoiceInteractionState.IDLE || state == VoiceInteractionState.SPEAKING) &&
            frameAnimated
        ) {
            ((sin(phaseSeconds * 2.0 * PI / if (state == VoiceInteractionState.IDLE) IDLE_PULSE_SECONDS else SPEAKING_PULSE_SECONDS) + 1.0) * 0.5).toFloat()
        } else {
            0.5f
        }
        val scale = 0.86f + pulse * 0.14f
        val tip = radius * 0.42f * scale
        val notch = radius * 0.095f * scale
        val halfWidth = radius * 0.39f * scale
        orbPath.reset()
        orbPath.moveTo(cx, cy - tip)
        orbPath.lineTo(cx + notch, cy - notch)
        orbPath.lineTo(cx + halfWidth, cy)
        orbPath.lineTo(cx + notch, cy + notch)
        orbPath.lineTo(cx, cy + tip)
        orbPath.lineTo(cx - notch, cy + notch)
        orbPath.lineTo(cx - halfWidth, cy)
        orbPath.lineTo(cx - notch, cy - notch)
        orbPath.close()

        corePaint.shader = coreShader
        canvas.drawPath(orbPath, corePaint)
        corePaint.shader = null
        corePaint.style = Paint.Style.STROKE
        corePaint.strokeWidth = dp(0.8f)
        corePaint.color = Color.argb(235, 255, 243, 181)
        canvas.drawPath(orbPath, corePaint)
        corePaint.style = Paint.Style.FILL
    }

    private fun canAnimate(): Boolean =
        activityVisible && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()

    private fun isContinuousState(current: VoiceInteractionState): Boolean =
        current == VoiceInteractionState.IDLE ||
            current == VoiceInteractionState.LISTENING ||
            current == VoiceInteractionState.THINKING ||
            current == VoiceInteractionState.SPEAKING

    private fun stateTint(current: VoiceInteractionState): Int = when (current) {
        VoiceInteractionState.LISTENING -> Color.rgb(35, 146, 255)
        VoiceInteractionState.THINKING -> Color.rgb(76, 158, 218)
        VoiceInteractionState.SPEAKING -> Color.rgb(226, 173, 68)
        VoiceInteractionState.ERROR, VoiceInteractionState.PERMISSION_REQUIRED -> Color.rgb(193, 78, 83)
        else -> Color.rgb(79, 119, 168)
    }

    private fun stateLabel(current: VoiceInteractionState): Int = when (current) {
        VoiceInteractionState.IDLE -> R.string.voice_state_idle
        VoiceInteractionState.LISTENING -> R.string.voice_state_listening
        VoiceInteractionState.PROCESSING -> R.string.voice_state_processing
        VoiceInteractionState.THINKING -> R.string.voice_state_thinking
        VoiceInteractionState.SPEAKING -> R.string.voice_state_speaking
        VoiceInteractionState.ERROR -> R.string.voice_state_error
        VoiceInteractionState.CANCELLED -> R.string.voice_state_cancelled
        VoiceInteractionState.PERMISSION_REQUIRED -> R.string.voice_state_permission
    }

    private fun strokePaint(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun dp(value: Float): Float = value * density

    private fun eased(value: Float): Float = value * value * (3f - 2f * value)

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val ratio = fraction.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt(),
        )
    }

    private companion object {
        const val MAX_FRAME_DELTA_MS = 50L
        const val TRANSITION_SECONDS = 0.28
        const val IDLE_PULSE_SECONDS = 3.6
        const val LISTENING_WAVE_SECONDS = 1.65
        const val SPEAKING_PULSE_SECONDS = 0.72
    }
}
