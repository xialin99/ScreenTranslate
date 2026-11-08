package com.screentranslate.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 可拖动悬浮球：自定义触摸、300ms 双击窗口、三种视觉状态。
 *
 * 触摸流程：
 * - UP（未移动）→ 等 350ms，期间若无新 DOWN → 单击
 * - 350ms 内有新 DOWN 且距上次 UP ≤ 300ms → 双击（取消等待中的单击）
 * - MOVE 超过 8px → 拖动模式，取消单击/长按
 * - 长按 500ms 且未移动 → 长按回调
 */
class FloatingBallView(context: Context, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        fun onTap()
        fun onDoubleTap()
        fun onLongPress()
        fun onDrag(dx: Int, dy: Int)
    }

    enum class BallState { IDLE, TRANSLATING, HAS_CARDS }

    // ---------- 绘制 ----------

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var pulseAnimator: ValueAnimator? = null
    private var pulseFraction = 1.0f

    @Volatile
    var ballState: BallState = BallState.IDLE
        set(value) {
            if (field == value) return
            field = value
            handler.post { applyStateVisuals() }
        }

    // ---------- 触摸状态 ----------

    private var downRawX = 0f
    private var downRawY = 0f
    private var hasMoved = false
    private var longPressPosted = false
    private var tapPosted = false
    private var lastUpTime = 0L

    init {
        setWillNotDraw(false)
        bgPaint.color = BALL_IDLE
        bgPaint.alpha = 235

        labelView = TextView(context).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            text = "译"
        }
        addView(
            labelView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                hasMoved = false
                cancelPendingLongPress()

                // 如果在 300ms 内有上一个 UP，这次是双击
                if (tapPosted && event.eventTime - lastUpTime <= DOUBLE_TAP_WINDOW_MS) {
                    cancelTap()
                    listener.onDoubleTap()
                    return true
                }

                // 取消上次还没触发的单击（新 DOWN 意味着上一次不是双击就是废弃）
                cancelTap()

                // 500ms 未移动 → 长按
                longPressPosted = true
                handler.postAtTime(
                    { onLongPressDetected() },
                    LONG_PRESS_TOKEN,
                    event.downTime + LONG_PRESS_MS
                )
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX) {
                    if (!hasMoved) {
                        hasMoved = true
                        cancelPendingLongPress()
                        cancelTap()
                    }
                    listener.onDrag(dx.toInt(), dy.toInt())
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                cancelPendingLongPress()
                lastUpTime = event.eventTime
                if (!hasMoved) {
                    // 抬起后等 TAP_TIMEOUT_MS，期间若没新 DOWN 就当单击
                    tapPosted = true
                    handler.postAtTime(
                        { onTapDetected() },
                        TAP_TOKEN,
                        event.eventTime + TAP_TIMEOUT_MS
                    )
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                cancelTap()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun onTapDetected() {
        if (hasMoved) return
        tapPosted = false
        listener.onTap()
    }

    private fun onLongPressDetected() {
        if (hasMoved) return
        longPressPosted = false
        cancelTap()
        runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        listener.onLongPress()
    }

    // ---------------------------------------------------------------- 视觉

    private fun applyStateVisuals() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseFraction = 1.0f

        when (ballState) {
            BallState.IDLE -> {
                bgPaint.color = BALL_IDLE
                labelView.text = "译"
            }
            BallState.TRANSLATING -> {
                bgPaint.color = BALL_TRANSLATING
                labelView.text = "…"
                startPulse()
            }
            BallState.HAS_CARDS -> {
                bgPaint.color = BALL_HAS_CARDS
                labelView.text = "✕"
            }
        }
        invalidate()
    }

    private fun startPulse() {
        pulseAnimator = ValueAnimator.ofFloat(0.6f, 1.0f).apply {
            duration = 600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                pulseFraction = anim.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = minOf(width, height) / 2f - dpFloat(2f)
        val cx = width / 2f
        val cy = height / 2f

        val scale = if (ballState == BallState.TRANSLATING) pulseFraction else 1.0f
        val scaledRadius = radius * scale
        canvas.drawCircle(cx, cy, scaledRadius, bgPaint)
    }

    // ---------------------------------------------------------------- 工具

    private fun cancelTap() {
        if (tapPosted) {
            handler.removeCallbacksAndMessages(TAP_TOKEN)
            tapPosted = false
        }
    }

    // 命名避开 android.view.View.cancelPendingLongPress()（父类公开方法，不可收窄可见性）
    private fun cancelPendingLongPress() {
        if (longPressPosted) {
            handler.removeCallbacksAndMessages(LONG_PRESS_TOKEN)
            longPressPosted = false
        }
    }

    private fun dpFloat(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        /** 抬起后等多久确认是单击（必须 > DOUBLE_TAP_WINDOW_MS，否则双击的第二次
         *  DOWN 到达时单击回调可能已先触发） */
        private const val TAP_TIMEOUT_MS = 350L

        /** 两次 DOWN 间隔小于此值且上一次还有未触发的单击 → 双击 */
        private const val DOUBLE_TAP_WINDOW_MS = 300L

        private const val LONG_PRESS_MS = 500L

        /** 拖动阈值：移动超过此距离算拖动，8 raw pixels ≈ 2-3dp */
        private const val DRAG_THRESHOLD_PX = 8f

        private val TAP_TOKEN = Any()
        private val LONG_PRESS_TOKEN = Any()

        private const val BALL_IDLE = 0xFF3B82F6.toInt()       // 蓝色 — 待翻译
        private const val BALL_TRANSLATING = 0xFFF59E0B.toInt() // 橙色 — 翻译中
        private const val BALL_HAS_CARDS = 0xFF10B981.toInt()   // 绿色 — 已有译文（点此清除）
    }
}