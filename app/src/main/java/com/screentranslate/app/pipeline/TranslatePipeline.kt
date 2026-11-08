package com.screentranslate.app.pipeline

import android.graphics.Bitmap
import com.screentranslate.app.capture.FrameDiff
import com.screentranslate.app.capture.ScreenCapturer
import com.screentranslate.app.ocr.OcrProcessor
import com.screentranslate.app.ocr.TextPostProcessor
import com.screentranslate.app.overlay.FloatingBallView
import com.screentranslate.app.overlay.OverlayController
import com.screentranslate.app.settings.SettingsStore
import com.screentranslate.app.translate.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 翻译链路：隐藏悬浮层 -> 截屏 -> OCR -> 过滤合并 -> 翻译 -> 原位渲染。
 * 自动模式：低频比对画面，变了才重新走链路。
 *
 * 并发控制：
 * - translateGeneration 防止 "清除卡片后翻译才完成" 的竞态
 * - isRunning 防止快速连点排队多次翻译
 */
class TranslatePipeline(
    private val scope: CoroutineScope,
    private val capturer: ScreenCapturer,
    private val ocr: OcrProcessor,
    private val translation: TranslationManager,
    private val overlay: OverlayController,
    private val settings: SettingsStore,
    private val topInsetPx: Int,
    private val bottomInsetPx: Int,
    private val onError: (String) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private val mutex = Mutex()
    private var autoJob: Job? = null

    @Volatile
    private var baselineResetRequested = false

    /** 每调用一次 translateNow / cancelInFlight 就 +1，translateOnce 发现变号就放弃渲染 */
    @Volatile
    private var translateGeneration = 0

    /** 当前是否有 translateOnce 正在执行 */
    @Volatile
    var isRunning = false
        private set

    /** 让本次翻译失效（用户清除了卡片，正在跑的翻译不要再画回来） */
    fun cancelInFlight() {
        translateGeneration++
    }

    fun translateNow() {
        if (isRunning) return // 忽略连点
        scope.launch { translateOnce() }
    }

    fun translateNowOrNothing() {
        scope.launch { translateOnce() }
    }

    fun requestBaselineReset() {
        baselineResetRequested = true
    }

    suspend fun translateOnce() = mutex.withLock {
        val myGeneration = translateGeneration
        isRunning = true
        updateBall(FloatingBallView.BallState.TRANSLATING)
        // 是否已走到终态（成功渲染 / 明确失败 / 用户取消），供 finally 决定通知文案
        var settledByCancel = false
        try {
            overlay.updateScreenSize(capturer.width, capturer.height)
            onStatus("正在识别屏幕文字…")
            // 先记录帧序号，再隐藏悬浮层：保证拿到的是"没有自家悬浮窗"的新帧
            val hideSeq = capturer.currentFrameSeq()
            overlay.hideForCapture()
            delay(80)
            val bitmap = capturer.captureAfter(hideSeq)
            if (bitmap == null) {
                onError("截屏失败")
                onStatus("截屏失败")
                return@withLock
            }
            // 隐藏期间用户可能已清除卡片 → 检查 generation
            if (translateGeneration != myGeneration) {
                settledByCancel = true
                return@withLock
            }

            val density = capturer.densityDpi / 160f
            val rawSegments = ocr.recognize(bitmap)
            val segments = TextPostProcessor.process(
                segments = rawSegments,
                minHeightPx = (11 * density).toInt(),
                topInset = topInsetPx,
                bottomInset = bottomInsetPx,
                screenHeight = capturer.height,
                minGapPx = (8 * density).toInt()
            )
            if (translateGeneration != myGeneration) {
                settledByCancel = true
                return@withLock
            }

            if (segments.isEmpty()) {
                overlay.clearCards()
                onStatus("当前屏幕没有可翻译的英文")
                return@withLock
            }
            onStatus("正在翻译 ${segments.size} 段文字…")
            val translated = translation.translate(segments)
            val error = translation.lastError
            // 主引擎失败但已自动降级成功：只提示，不当失败处理
            val notice = translation.lastNotice

            if (translateGeneration != myGeneration) {
                settledByCancel = true
                return@withLock
            }

            if (translated.isEmpty()) {
                val reason = error ?: "翻译失败"
                onError(reason)
                onStatus("翻译失败：$reason")
                return@withLock
            }
            overlay.render(translated)
            updateBall(FloatingBallView.BallState.HAS_CARDS)
            when {
                error != null -> onError(error)
                notice != null -> {
                    onNotice(notice)
                    onStatus("翻译完成")
                }
                else -> onStatus("翻译完成")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: "翻译失败"
            onError(reason)
            onStatus("翻译失败：$reason")
        } finally {
            isRunning = false
            overlay.showAfterCapture()
            // 关键：无论成功、失败、报错还是被取消，悬浮球都必须回到与当前屏幕一致的状态。
            // 之前只在「被取消」分支恢复，导致翻译失败后球永远卡在橙色 TRANSLATING。
            updateBall(
                if (overlay.hasCards()) FloatingBallView.BallState.HAS_CARDS
                else FloatingBallView.BallState.IDLE
            )
            // 被取消时通知停在「正在翻译…」也是错的，给个中性文案
            if (settledByCancel) {
                onStatus(
                    if (overlay.hasCards()) "翻译完成" else "屏幕翻译已就绪，点悬浮球翻译当前屏幕"
                )
            }
        }
    }

    fun startAutoLoop() {
        if (autoJob?.isActive == true) return
        autoJob = scope.launch {
            var previous: Bitmap? = null
            while (isActive) {
                delay(600)
                if (baselineResetRequested) {
                    baselineResetRequested = false
                    previous = null
                    continue
                }
                if (!settings.autoMode) {
                    previous = null
                    continue
                }
                if (isRunning) continue // 等手动翻译跑完再比较

                val current = capturer.latest() ?: continue
                if (previous == null ||
                    previous.width != current.width ||
                    previous.height != current.height
                ) {
                    previous = current
                    continue
                }
                val ratio = FrameDiff.changedRatio(previous, current, overlay.ignoreRects())
                if (ratio > CHANGE_THRESHOLD) {
                    translateOnce()
                    if (!isActive) break
                    delay(300)
                    previous = capturer.latest()
                }
            }
        }
    }

    fun stop() {
        autoJob?.cancel()
        autoJob = null
    }

    private fun updateBall(state: FloatingBallView.BallState) {
        overlay.updateBallState(state)
    }

    companion object {
        private const val CHANGE_THRESHOLD = 0.012f
    }
}