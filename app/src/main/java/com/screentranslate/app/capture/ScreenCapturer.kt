package com.screentranslate.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.delay

/**
 * MediaProjection 截屏：
 * - 虚拟显示器 + ImageReader 持续镜像屏幕
 * - 有需要的帧时才转 Bitmap（节流 200ms，避免持续拷贝 8MB 位图）
 * - capture() 会等待"隐藏覆盖层之后"产生的新帧，保证截图里没有自家悬浮窗
 */
class ScreenCapturer(private val context: Context) {

    var width = 0
        private set
    var height = 0
        private set
    var densityDpi = 0
        private set

    /** 用户在系统 UI 里主动停止投屏时回调 */
    @Volatile
    var onProjectionStopped: (() -> Unit)? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    @Volatile
    private var latestBitmap: Bitmap? = null

    @Volatile
    private var frameSeq = 0L

    @Volatile
    private var lastConvertAt = 0L

    @Volatile
    private var captureRequested = false

    @Volatile
    private var releasing = false

    @Volatile
    private var stopping = false

    fun start(resultCode: Int, data: Intent): Boolean {
        stopping = false
        releasing = false
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return false
        val newProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        } ?: return false

        projection = newProjection
        val thread = HandlerThread("screen-capture").also { it.start() }
        handlerThread = thread
        val captureHandler = Handler(thread.looper)
        handler = captureHandler
        measure()

        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (stopping) return
                stopping = true
                onProjectionStopped?.invoke()
            }
        }, captureHandler)

        createDisplay(newProjection)
        registerDisplayListener()
        return true
    }

    private fun createDisplay(mediaProjection: MediaProjection) {
        val captureHandler = handler ?: return
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> handleImage(r) }, captureHandler)
        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "screen-translate",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
    }

    private fun handleImage(reader: ImageReader) {
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage failed", e)
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()
            if (captureRequested || now - lastConvertAt >= CONVERT_INTERVAL_MS) {
                lastConvertAt = now
                latestBitmap = imageToBitmap(image)
                frameSeq++
            }
        } catch (e: Throwable) {
            Log.w(TAG, "convert frame failed", e)
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun measure() {
        val windowManager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.maximumWindowMetrics
            width = metrics.bounds.width()
            height = metrics.bounds.height()
            densityDpi = context.resources.displayMetrics.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            densityDpi = metrics.densityDpi
        }
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val displayManager = context.getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                handler?.post { rebuildIfSizeChanged() }
            }
        }
        displayListener = listener
        displayManager.registerDisplayListener(listener, handler)
    }

    /** 旋转 / 分辨率变化后重建虚拟显示器（不需要重新授权） */
    private fun rebuildIfSizeChanged() {
        val mediaProjection = projection ?: return
        if (releasing) return
        try {
            val oldWidth = width
            val oldHeight = height
            measure()
            if (oldWidth == width && oldHeight == height) return
            try {
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            virtualDisplay = null
            try {
                imageReader?.close()
            } catch (_: Exception) {
            }
            imageReader = null
            createDisplay(mediaProjection)
        } catch (e: Throwable) {
            Log.w(TAG, "rebuild display failed", e)
        }
    }

    /**
     * 等待"指定序号之后"产生的新帧（截屏前记录序号，确保拿到的是隐藏覆盖层之后的画面）。
     * 屏幕静止时可能没有新帧，超时后返回最近一帧。
     */
    suspend fun captureAfter(afterSeq: Long, timeoutMs: Long = 700): Bitmap? {
        captureRequested = true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (frameSeq > afterSeq) return latestBitmap
                delay(20)
            }
            return latestBitmap
        } finally {
            captureRequested = false
        }
    }

    /** 当前帧序号（截屏前记录，配合 captureAfter 使用） */
    fun currentFrameSeq(): Long = frameSeq

    fun latest(): Bitmap? = latestBitmap

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    fun stop() {
        stopping = true
        release()
    }

    @Synchronized
    private fun release() {
        if (releasing) return
        releasing = true

        displayListener?.let { listener ->
            try {
                context.getSystemService(DisplayManager::class.java)
                    ?.unregisterDisplayListener(listener)
            } catch (_: Exception) {
            }
        }
        displayListener = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        latestBitmap = null
    }

    companion object {
        private const val TAG = "ScreenCapturer"
        private const val CONVERT_INTERVAL_MS = 200L
    }
}
