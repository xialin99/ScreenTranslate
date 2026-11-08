package com.screentranslate.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.screentranslate.app.MainActivity
import com.screentranslate.app.R
import com.screentranslate.app.ScreenTranslateApp
import com.screentranslate.app.capture.ScreenCapturer
import com.screentranslate.app.ocr.OcrProcessor
import com.screentranslate.app.overlay.FloatingBallView
import com.screentranslate.app.overlay.OverlayController
import com.screentranslate.app.pipeline.TranslatePipeline
import com.screentranslate.app.translate.TranslationManager
import com.screentranslate.app.util.DisplayUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScreenTranslateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var capturer: ScreenCapturer
    private lateinit var ocr: OcrProcessor
    private lateinit var translation: TranslationManager

    private var overlay: OverlayController? = null
    private var pipeline: TranslatePipeline? = null

    private var started = false

    @Volatile
    private var stopping = false

    private val app: ScreenTranslateApp
        get() = application as ScreenTranslateApp

    private val ballCallbacks = object : OverlayController.BallCallbacks {
        override fun onTap() {
            val currentOverlay = overlay ?: return
            val currentPipeline = pipeline ?: return
            if (currentOverlay.hasCards()) {
                // 1. 让正在跑的翻译失效（防止它完事儿后又把卡片画回来）
                currentPipeline.cancelInFlight()
                // 2. 清除卡片
                currentOverlay.clearCards()
                // 3. 重置自动模式的对比基线
                currentPipeline.requestBaselineReset()
                // 4. 恢复悬浮球视觉
                currentOverlay.updateBallState(FloatingBallView.BallState.IDLE)
            } else {
                // 无卡片 → 启动翻译（isRunning 守卫会忽略连点）
                currentPipeline.translateNow()
            }
        }

        override fun onDoubleTap() {
            overlay?.toggleGlobalOriginal()
        }

        override fun onLongPress() {
            val intent = Intent(this@ScreenTranslateService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        capturer = ScreenCapturer(this)
        ocr = OcrProcessor()
        translation = TranslationManager(this, app.settings, app.glossary)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent.action
        if (action == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (action != ACTION_START && !started) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START -> handleStart(intent)

            ACTION_STOP -> requestStop()

            ACTION_TOGGLE_AUTO -> {
                app.settings.autoMode = !app.settings.autoMode
                updateNotification("自动翻译：${if (app.settings.autoMode) "开" else "关"}")
            }

            ACTION_TOGGLE_GLOBAL -> overlay?.toggleGlobalOriginal()

            ACTION_APPLY_SETTINGS -> {
                overlay?.applyTouchMode()
                overlay?.updateBallState(
                    if (overlay?.hasCards() == true) FloatingBallView.BallState.HAS_CARDS
                    else FloatingBallView.BallState.IDLE
                )
                updateNotification("设置已生效")
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val notification = buildNotification("正在启动屏幕翻译…")
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            showToast("前台服务启动失败：${e.message}")
            stopSelf()
            return
        }

        if (started) {
            updateNotification("屏幕翻译已就绪，点悬浮球翻译当前屏幕")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            updateNotification("未获得屏幕捕获授权")
            requestStop()
            return
        }

        capturer.onProjectionStopped = {
            scope.launch {
                showToast("屏幕捕获已停止")
                requestStop()
            }
        }
        started = try {
            capturer.start(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "start capture failed", e)
            false
        }
        if (!started) {
            updateNotification("屏幕捕获启动失败，请重试")
            requestStop()
            return
        }

        val newOverlay = OverlayController(
            context = this,
            settings = app.settings,
            initialWidth = capturer.width,
            initialHeight = capturer.height,
            callbacks = ballCallbacks
        )
        try {
            newOverlay.attach()
        } catch (e: Exception) {
            showToast("悬浮窗创建失败，请先授予悬浮窗权限")
            requestStop()
            return
        }
        overlay = newOverlay

        pipeline = TranslatePipeline(
            scope = scope,
            capturer = capturer,
            ocr = ocr,
            translation = translation,
            overlay = newOverlay,
            settings = app.settings,
            topInsetPx = DisplayUtils.statusBarHeight(this),
            bottomInsetPx = DisplayUtils.navigationBarHeight(this),
            onError = { message -> showToast("翻译失败：$message") },
            onNotice = { message -> showToast(message) },
            onStatus = { status -> updateNotification(status) }
        )
        pipeline?.startAutoLoop()
        isRunning = true
        updateNotification("屏幕翻译已就绪，点悬浮球翻译当前屏幕")
    }

    private fun requestStop() {
        if (stopping) return
        stopping = true
        scope.launch { shutdown() }
    }

    private fun shutdown() {
        runCatching { pipeline?.stop() }
        pipeline = null
        runCatching { overlay?.detach() }
        overlay = null
        runCatching { capturer.stop() }
        runCatching { ocr.close() }
        runCatching { translation.close() }
        started = false
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { pipeline?.stop() }
        runCatching { overlay?.detach() }
        runCatching { capturer.stop() }
        runCatching { ocr.close() }
        runCatching { translation.close() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- 通知

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "屏幕翻译",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "屏幕翻译运行状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val autoIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_TOGGLE_AUTO),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val globalIntent = PendingIntent.getService(
            this,
            103,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_TOGGLE_GLOBAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val autoLabel = if (app.settings.autoMode) "自动:开" else "自动:关"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕翻译")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, "切换原文", globalIntent)
            .addAction(0, autoLabel, autoIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private fun showToast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val ACTION_START = "com.screentranslate.app.action.START"
        const val ACTION_STOP = "com.screentranslate.app.action.STOP"
        const val ACTION_TOGGLE_AUTO = "com.screentranslate.app.action.TOGGLE_AUTO"
        const val ACTION_TOGGLE_GLOBAL = "com.screentranslate.app.action.TOGGLE_GLOBAL"
        const val ACTION_APPLY_SETTINGS = "com.screentranslate.app.action.APPLY_SETTINGS"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val TAG = "ScreenTranslateService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_translate"

        /** 服务是否正在运行（设置页据此决定是否下发设置变更） */
        @Volatile
        var isRunning: Boolean = false
    }
}
