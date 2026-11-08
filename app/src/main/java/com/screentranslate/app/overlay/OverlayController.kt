package com.screentranslate.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.screentranslate.app.settings.SettingsStore
import com.screentranslate.app.translate.TranslatedSegment

/**
 * 悬浮层：
 * - 悬浮球（可拖动，单击/双击/长按，三种颜色表示状态）
 * - 译文卡片容器：普通模式下铺满全屏且触摸穿透；对照模式下窗口收缩到所有卡片的外接矩形，
 *   卡片本身可点击，外接矩形内的空白也会拦截触摸（其余屏幕区域仍可操作底层 App）
 * - 截屏前隐藏自己，避免把自己截进识别画面
 */
class OverlayController(
    context: Context,
    private val settings: SettingsStore,
    initialWidth: Int,
    initialHeight: Int,
    private val callbacks: BallCallbacks
) {

    interface BallCallbacks {
        fun onTap()
        fun onDoubleTap()
        fun onLongPress()
    }

    private val appContext = context.applicationContext
    private val windowManager: WindowManager =
        appContext.getSystemService(WindowManager::class.java)
    private val density: Float = appContext.resources.displayMetrics.density

    private var screenWidth = initialWidth
    private var screenHeight = initialHeight

    private var ballView: FloatingBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var ballSize = 0

    private var cardContainer: FrameLayout? = null
    private var containerParams: WindowManager.LayoutParams? = null

    /** 与 lastSegments 一一对应的卡片视图，供「对照模式」的触摸层回调 */
    private val cardViews = ArrayList<TranslationCardView>()

    /**
     * 「对照模式」的触摸层：每张卡片一个独立的小窗口，尺寸严格等于卡片矩形。
     * 不能用「一个可触摸的大窗口」——窗口可触摸时，落在这个窗口内但没被卡片吃掉的触摸
     * 会被直接丢弃，不会传给底层 App；卡片分散时外接矩形几乎等于整屏，界面就点不动了。
     * 拆成一卡一窗后，只有卡片本身那几块矩形拦截触摸，其余屏幕照常操作。
     */
    private val touchTargets = ArrayList<TouchTarget>()

    private class TouchTarget(
        val view: View,
        val params: WindowManager.LayoutParams
    )

    private var lastSegments: List<TranslatedSegment> = emptyList()
    private var renderGeneration = 0

    @Volatile
    private var globalShowOriginal = false

    private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        addBall()
        addCardContainer()
    }

    fun detach() {
        attached = false
        renderGeneration++
        ballView?.let { runCatching { windowManager.removeView(it) } }
        cardContainer?.let { runCatching { windowManager.removeView(it) } }
        clearTouchTargets()
        ballView = null
        ballParams = null
        cardContainer = null
        containerParams = null
        cardViews.clear()
        lastSegments = emptyList()
    }

    // ------------------------------------------------------------------ 悬浮球

    private fun addBall() {
        if (ballView != null) return
        ballSize = dp(56f)
        val view = FloatingBallView(appContext, object : FloatingBallView.Listener {
            override fun onTap() = callbacks.onTap()
            override fun onDoubleTap() = callbacks.onDoubleTap()
            override fun onLongPress() = callbacks.onLongPress()
            override fun onDrag(dx: Int, dy: Int) = moveBall(dx, dy)
        })
        val params = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - ballSize - dp(12f)).coerceAtLeast(0)
            y = (screenHeight / 3).coerceAtLeast(0)
        }
        windowManager.addView(view, params)
        ballView = view
        ballParams = params
    }

    private fun moveBall(dx: Int, dy: Int) {
        val view = ballView ?: return
        val params = ballParams ?: return
        val maxX = (screenWidth - ballSize).coerceAtLeast(0)
        val maxY = (screenHeight - ballSize).coerceAtLeast(0)
        val newX = (params.x + dx).coerceIn(0, maxX)
        val newY = (params.y + dy).coerceIn(0, maxY)
        if (newX == params.x && newY == params.y) return
        params.x = newX
        params.y = newY
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun updateBallState(state: FloatingBallView.BallState) {
        ballView?.ballState = state
    }

    val currentBallState: FloatingBallView.BallState
        get() = ballView?.ballState ?: FloatingBallView.BallState.IDLE

    // ------------------------------------------------------------------ 卡片层

    private fun addCardContainer() {
        if (cardContainer != null) return
        val view = FrameLayout(appContext)
        view.clipChildren = false
        // 窗口尺寸/位置变化后重新对齐卡片（getLocationOnScreen 才是准确原点）
        view.addOnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
            changedView.post { repositionCards() }
        }
        val params = WindowManager.LayoutParams(
            screenWidth,
            screenHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // 卡片层永远不可触摸：触摸一律穿透到底层 App。
                // 需要点击卡片时，由 applyTouchTargets 在卡片位置叠一层独立的小窗口。
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        windowManager.addView(view, params)
        cardContainer = view
        containerParams = params
    }

    /** 按 OCR 坐标原位铺卡片；对照模式下额外叠一层只覆盖卡片的触摸窗口 */
    fun render(segments: List<TranslatedSegment>) {
        lastSegments = segments
        val container = cardContainer ?: return
        val generation = ++renderGeneration
        // 段数变了就先撤掉旧卡片位置的触摸层，避免这一帧里旧窗口挡住已经不存在的位置
        if (cardViews.size != segments.size) clearTouchTargets()
        applyWindowBounds(segments)
        container.post {
            if (generation != renderGeneration) return@post
            rebuildCards(segments)
        }
    }

    private fun applyWindowBounds(segments: List<TranslatedSegment>) {
        val container = cardContainer ?: return
        val params = containerParams ?: return
        // 卡片层始终铺满全屏且不可触摸；视觉上靠卡片自身的不透明底色盖住原文
        val bounds = Rect(0, 0, screenWidth, screenHeight)
        if (params.x != bounds.left || params.y != bounds.top ||
            params.width != bounds.width() || params.height != bounds.height()
        ) {
            params.x = bounds.left
            params.y = bounds.top
            params.width = bounds.width()
            params.height = bounds.height()
            runCatching { windowManager.updateViewLayout(container, params) }
        }
        val wantFlags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (params.flags != wantFlags) {
            params.flags = wantFlags
            runCatching { windowManager.updateViewLayout(container, params) }
        }
        syncTouchTargets(segments)
    }

    private fun rebuildCards(segments: List<TranslatedSegment>) {
        val container = cardContainer ?: return
        container.removeAllViews()
        cardViews.clear()
        if (segments.isEmpty()) return
        segments.forEach { segment ->
            val card = TranslationCardView(appContext).apply {
                originalText = segment.original
                translatedText = segment.translation
                showingTranslation = !globalShowOriginal
                bindText()
            }
            val lines = segment.lineCount.coerceAtLeast(1)
            val cardWidth = segment.box.width().coerceAtLeast(dp(28f))
            val cardHeight = segment.box.height().coerceAtLeast(dp(16f))
            val textSizePx = ((cardHeight.toFloat() / lines) * 0.66f)
                .coerceIn(dp(10f).toFloat(), dp(40f).toFloat())
            card.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            card.maxLines = lines
            card.ellipsize = TextUtils.TruncateAt.END
            card.layoutParams = FrameLayout.LayoutParams(cardWidth, cardHeight)
            card.setOnClickListener { card.toggle() }
            container.addView(card)
            cardViews.add(card)
        }
        repositionCards()
        syncTouchTargets(lastSegments)
    }

    /** 用窗口在屏幕上的真实原点修正卡片位置（兼容状态栏/挖孔造成的偏移） */
    private fun repositionCards() {
        val container = cardContainer ?: return
        if (container.childCount == 0) return
        if (container.childCount != lastSegments.size) return
        val location = IntArray(2)
        container.getLocationOnScreen(location)
        val offsetX = location[0]
        val offsetY = location[1]
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            val segment = lastSegments[index]
            val targetX = (segment.box.left - offsetX).toFloat()
            val targetY = (segment.box.top - offsetY).toFloat()
            // 用 translation 而不是 layoutParams，避免触发额外 layout
            if (child.translationX != targetX) child.translationX = targetX
            if (child.translationY != targetY) child.translationY = targetY
        }
    }

    /**
     * 「对照模式」下为每张卡片建一个恰好覆盖它的触摸窗口。
     * 每个窗口只有卡片那么大，卡片以外的屏幕区域完全没有窗口遮挡，触摸自然落到下层 App。
     */
    private fun syncTouchTargets(segments: List<TranslatedSegment>) {
        if (!settings.touchMode || segments.isEmpty()) {
            clearTouchTargets()
            return
        }

        // 段数与视图数对不上（还在 post 里重建）时先不建窗口，等 rebuildCards 完成后会再调用
        if (cardViews.size != segments.size) return

        while (touchTargets.size > segments.size) {
            val target = touchTargets.removeAt(touchTargets.size - 1)
            runCatching { windowManager.removeViewImmediate(target.view) }
        }

        segments.forEachIndexed { index, segment ->
            if (index >= cardViews.size) return@forEachIndexed
            val width = segment.box.width().coerceAtLeast(dp(28f))
            val height = segment.box.height().coerceAtLeast(dp(16f))
            val x = segment.box.left
            val y = segment.box.top

            val existing = touchTargets.getOrNull(index)
            if (existing != null) {
                val params = existing.params
                if (params.x != x || params.y != y ||
                    params.width != width || params.height != height
                ) {
                    params.x = x
                    params.y = y
                    params.width = width
                    params.height = height
                    runCatching { windowManager.updateViewLayout(existing.view, params) }
                }
                return@forEachIndexed
            }

            val proxy = View(appContext).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                // 按序号取卡片，而不是捕获当前对象：卡片重建后 capture 会指向已移除的旧视图
                setOnClickListener { cardViews.getOrNull(index)?.toggle() }
            }
            val params = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            try {
                windowManager.addView(proxy, params)
                touchTargets.add(TouchTarget(proxy, params))
            } catch (_: Exception) {
                // 加固流程中偶尔会拒绝新增窗口，忽略即可：触摸穿透优先于可点击
            }
        }
    }

    private fun clearTouchTargets() {
        touchTargets.forEach { runCatching { windowManager.removeViewImmediate(it.view) } }
        touchTargets.clear()
    }

    /** 设置页切换「对照模式」后调用：重建/移除卡片触摸层 */
    fun applyTouchMode() {
        if (cardContainer == null) return
        if (lastSegments.isEmpty()) {
            clearTouchTargets()
            applyWindowBounds(emptyList())
        } else {
            applyWindowBounds(lastSegments)
        }
    }

    fun toggleGlobalOriginal() {
        globalShowOriginal = !globalShowOriginal
        val container = cardContainer ?: return
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (child is TranslationCardView) {
                child.showingTranslation = !globalShowOriginal
                child.bindText()
            }
        }
    }

    fun hasCards(): Boolean = lastSegments.isNotEmpty()

    fun clearCards() {
        lastSegments = emptyList()
        renderGeneration++
        cardContainer?.removeAllViews()
        cardViews.clear()
        clearTouchTargets()
        applyWindowBounds(emptyList())
    }

    /** 自动模式做帧 diff 时，忽略悬浮球和卡片覆盖的区域 */
    fun ignoreRects(): List<Rect> {
        val result = ArrayList<Rect>(lastSegments.size + 1)
        lastSegments.forEach { result.add(Rect(it.box)) }
        val ball = ballView
        val params = ballParams
        if (ball != null && params != null && ball.visibility == View.VISIBLE) {
            result.add(Rect(params.x, params.y, params.x + ballSize, params.y + ballSize))
        }
        return result
    }

    fun hideForCapture() {
        ballView?.visibility = View.INVISIBLE
        cardContainer?.visibility = View.INVISIBLE
        // 触摸代理也要一起失效，否则截屏那几十毫秒里用户点卡片会被静默吃掉
        touchTargets.forEach { it.view.visibility = View.INVISIBLE }
    }

    fun showAfterCapture() {
        ballView?.visibility = View.VISIBLE
        cardContainer?.visibility = View.VISIBLE
        touchTargets.forEach { it.view.visibility = View.VISIBLE }
    }

    /** 旋转 / 分辨率变化时更新窗口尺寸 */
    fun updateScreenSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == screenWidth && height == screenHeight) return
        screenWidth = width
        screenHeight = height
        clearCards()
        val ball = ballView
        val params = ballParams
        if (ball != null && params != null) {
            params.x = params.x.coerceIn(0, (width - ballSize).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (height - ballSize).coerceAtLeast(0))
            runCatching { windowManager.updateViewLayout(ball, params) }
        }
    }

    private fun dp(value: Float): Int = (value * density).toInt()
}