package lifeos.md.tapilot.accessibility

import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min

/** One finger track extracted from a [GestureDescription] stroke. */
internal class FingerTrack(val path: android.graphics.Path, val startMs: Long, val durationMs: Long, val fingerIndex: Int = -1) {
    private val measure = PathMeasure(path, false)
    val length: Float = measure.length
    private val pos = FloatArray(2)

    fun at(progress: Float): Pair<Float, Float> {
        measure.getPosTan(length * progress.coerceIn(0f, 1f), pos, null)
        return pos[0] to pos[1]
    }
}

/**
 * Draws Tapilot's own touch feedback: a fingertip that presses, rides a comet trail along swipes,
 * and shows two fingers for pinch. Purely visual; it never receives touches.
 */
internal class GestureEffectView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var tracks: List<FingerTrack> = emptyList()
    private var clock = 0L
    private var totalMs = 0L
    var onFinished: (() -> Unit)? = null
    var chinese = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val body = RectF()
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f); typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val animator = ValueAnimator().apply {
        interpolator = LinearInterpolator()
        addUpdateListener { clock = (it.animatedValue as Float).toLong(); invalidate() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { if (clock >= totalMs) onFinished?.invoke() }
        })
    }

    fun play(newTracks: List<FingerTrack>) {
        animator.cancel()
        tracks = newTracks
        totalMs = newTracks.maxOf { it.startMs + max(it.durationMs, MIN_VISUAL_MS) } + TAIL_MS
        animator.setFloatValues(0f, totalMs.toFloat())
        animator.duration = totalMs
        animator.start()
    }

    fun stop() { animator.cancel(); tracks = emptyList(); invalidate() }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animator.cancel() }

    override fun onDraw(canvas: Canvas) {
        tracks.forEach { track -> drawTrack(canvas, track, track.fingerIndex) }
    }

    private fun drawTrack(canvas: Canvas, track: FingerTrack, fingerIndex: Int) {
        val visual = max(track.durationMs, MIN_VISUAL_MS)
        val local = clock - track.startMs
        if (local < 0) return
        val moving = track.length > dp(12f)
        val progress = (local.toFloat() / visual).coerceIn(0f, 1f)
        val eased = progress * progress * (3 - 2 * progress)
        val (x, y) = track.at(if (moving) eased else 0f)
        val fade = if (local > visual) (1f - (local - visual).toFloat() / TAIL_MS).coerceIn(0f, 1f) else 1f
        val press = if (local < PRESS_MS) local / PRESS_MS.toFloat() else 1f

        // Comet trail: fading dots along the recent path.
        if (moving) for (k in 1..9) {
            val back = (eased - k * 0.035f).coerceAtLeast(0f)
            val (tx, ty) = track.at(back)
            val a = (150 * (1f - k / 10f) * fade).toInt()
            fill.color = Color.argb(a, 255, 255, 255)
            canvas.drawCircle(tx, ty, dp(15f) * (1f - k / 12f), fill)
        }
        // Tap / long-press ripple at the landing point.
        if (!moving) {
            val r = dp(18f) + dp(30f) * (local.toFloat() / (visual + TAIL_MS)).coerceIn(0f, 1f)
            ring.color = Color.argb((200 * (1f - local.toFloat() / (visual + TAIL_MS)).coerceIn(0f, 1f)).toInt(), 255, 255, 255)
            canvas.drawCircle(x, y, r, ring)
        }
        val scale = 0.82f + 0.18f * press
        val alpha = (235 * fade).toInt()
        canvas.save()
        canvas.translate(x, y)
        if (fingerIndex < 0) {
            canvas.rotate(-16f)
            drawHand(canvas, alpha, scale)
        } else {
            canvas.rotate(if (fingerIndex == 0) 28f else -22f)
            drawFinger(canvas, alpha, scale, thumb = fingerIndex == 0)
            canvas.rotate(if (fingerIndex == 0) -28f else 22f)
            drawLabel(canvas, if (fingerIndex == 0) (if (chinese) "拇指" else "Thumb") else (if (chinese) "食指" else "Index"), alpha)
        }
        canvas.restore()
        // Touch point pad.
        fill.color = Color.argb((alpha * 0.55f).toInt(), 0, 122, 255)
        canvas.drawCircle(x, y, dp(7f) * scale, fill)
    }

    /** Pointing hand: index finger tip at the origin, folded fingers and thumb on a palm below it. */
    private fun drawHand(canvas: Canvas, alpha: Int, scale: Float) {
        fun shapes(p: Paint) {
            body.set(-dp(16f), dp(60f), dp(40f), dp(128f)); canvas.drawRoundRect(body, dp(18f), dp(18f), p)
            canvas.drawCircle(dp(14f), dp(62f), dp(11f), p); canvas.drawCircle(dp(29f), dp(66f), dp(10f), p)
            body.set(-dp(30f), dp(78f), -dp(8f), dp(112f)); canvas.drawRoundRect(body, dp(10f), dp(10f), p)
            body.set(-dp(11f) * scale, 0f, dp(11f) * scale, dp(86f)); canvas.drawRoundRect(body, dp(11f), dp(11f), p)
        }
        canvas.save()
        canvas.translate(dp(3f), dp(5f))
        fill.color = Color.argb((alpha * 0.25f).toInt(), 0, 0, 0)
        shapes(fill)
        canvas.restore()
        fill.style = Paint.Style.FILL_AND_STROKE
        fill.strokeWidth = dp(3f)
        fill.color = Color.argb((alpha * 0.6f).toInt(), 110, 110, 118)
        shapes(fill)
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(alpha, 252, 252, 252)
        shapes(fill)
    }

    /** One bare finger for pinch: the thumb is a little wider and shorter than the index finger. */
    private fun drawFinger(canvas: Canvas, alpha: Int, scale: Float, thumb: Boolean) {
        val w = dp(if (thumb) 34f else 26f) * scale
        val h = dp(if (thumb) 76f else 92f)
        body.set(-w / 2f, 0f, w / 2f, h)
        canvas.save()
        canvas.translate(dp(2f), dp(3f))
        fill.color = Color.argb((alpha * 0.25f).toInt(), 0, 0, 0)
        canvas.drawRoundRect(body, w / 2f, w / 2f, fill)
        canvas.restore()
        fill.color = Color.argb(alpha, 252, 252, 252)
        canvas.drawRoundRect(body, w / 2f, w / 2f, fill)
        ring.color = Color.argb((alpha * 0.6f).toInt(), 110, 110, 118)
        ring.strokeWidth = dp(1.6f)
        canvas.drawRoundRect(body, w / 2f, w / 2f, ring)
        ring.strokeWidth = dp(2f)
    }

    private fun drawLabel(canvas: Canvas, text: String, alpha: Int) {
        label.color = Color.argb(alpha, 40, 40, 46)
        label.setShadowLayer(dp(3f), 0f, 0f, Color.argb(alpha, 255, 255, 255))
        canvas.drawText(text, dp(22f), -dp(14f), label)
    }

    private companion object {
        const val MIN_VISUAL_MS = 320L
        const val PRESS_MS = 120L
        const val TAIL_MS = 380L
    }
}

class GestureEffectController(
    private val service: MidsceneAccessibilityService,
    private val windowManager: WindowManager,
    private val handler: Handler,
) : AutoCloseable {
    private var view: GestureEffectView? = null
    private var unavailable = false

    /** Shows the fingers for [description]; failures here must never affect the real gesture. */
    fun show(description: GestureDescription) {
        runCatching {
            val tracks = (0 until description.strokeCount).map { i ->
                val stroke = description.getStroke(i)
                FingerTrack(android.graphics.Path(stroke.path), stroke.startTime, stroke.duration, if (description.strokeCount == 2) i else -1)
            }
            if (tracks.isEmpty()) return
            handler.post { ensureView()?.play(tracks) }
        }.onFailure { Log.w(LOG_TAG, "Gesture effect skipped", it) }
    }

    /** Plays a tap, a swipe and a two-finger zoom so the effect can be seen without running a task. */
    fun preview() {
        handler.post {
            val metrics = service.resources.displayMetrics
            val w = metrics.widthPixels.toFloat()
            val h = metrics.heightPixels.toFloat()
            val d = metrics.density
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                android.graphics.Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
            val cx = w / 2f
            val cy = h / 2f
            ensureView()?.play(listOf(
                FingerTrack(line(cx, h * .42f, cx, h * .42f), 0, 120),
                FingerTrack(line(cx, h * .68f, cx, h * .32f), 1100, 520),
                FingerTrack(line(cx - 30 * d, cy + 30 * d, cx - 150 * d, cy + 150 * d), 2600, 700, 0),
                FingerTrack(line(cx + 30 * d, cy - 30 * d, cx + 150 * d, cy - 150 * d), 2600, 700, 1),
            ))
        }
    }

    /** Removes the fingers immediately, e.g. before a screenshot is captured. */
    fun clear() {
        handler.post { removeView() }
    }

    override fun close() = removeView()

    private fun ensureView(): GestureEffectView? {
        view?.let { return it }
        if (unavailable) return null
        val created = GestureEffectView(service)
        created.onFinished = { removeView() }
        created.chinese = lifeos.md.tapilot.AppLanguage.resolved(service) == "zh"
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        try {
            windowManager.addView(created, params)
        } catch (error: RuntimeException) {
            unavailable = true
            Log.w(LOG_TAG, "Gesture effect is unavailable", error)
            return null
        }
        view = created
        return created
    }

    private fun removeView() {
        val current = view ?: return
        view = null
        current.stop()
        runCatching { windowManager.removeView(current) }
            .onFailure { Log.w(LOG_TAG, "Unable to remove gesture effect", it) }
    }

    private companion object { const val LOG_TAG = "TapilotGestureEffect" }
}
