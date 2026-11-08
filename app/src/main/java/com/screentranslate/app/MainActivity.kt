package com.screentranslate.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.screentranslate.app.databinding.ActivityMainBinding
import com.screentranslate.app.service.ScreenTranslateService
import com.screentranslate.app.settings.SettingsStore
import com.screentranslate.app.translate.DeviceTranslator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore

    private val app: ScreenTranslateApp
        get() = application as ScreenTranslateApp

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenTranslateService::class.java).apply {
                action = ScreenTranslateService.ACTION_START
                putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenTranslateService.EXTRA_RESULT_DATA, data)
            }
            try {
                ContextCompat.startForegroundService(this, intent)
                binding.statusText.text = "屏幕翻译已启动。切换到目标 App，点悬浮球「译」翻译当前屏幕。"
            } catch (e: Exception) {
                Toast.makeText(this, "启动服务失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "未授权屏幕捕获，无法翻译", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "没有通知权限，前台服务通知会被隐藏", Toast.LENGTH_SHORT).show()
        }
        launchCapture()
    }

    private val importGlossaryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val count = app.glossary.importFrom(uri)
        val message = when {
            count > 0 -> "已导入 $count 条术语"
            count == 0 -> "文件里没有可用术语"
            else -> "术语表导入失败"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private val exportGlossaryLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val ok = app.glossary.exportTo(uri)
        Toast.makeText(this, if (ok) "术语表已导出" else "导出失败", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = app.settings

        binding.startButton.setOnClickListener { onStartClicked() }
        binding.stopButton.setOnClickListener {
            stopService(Intent(this, ScreenTranslateService::class.java))
            binding.statusText.text = getString(R.string.status_idle)
        }

        binding.overlayPermissionButton.setOnClickListener { openOverlaySettings() }
        binding.notificationPermissionButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "Android 13 以下无需通知权限", Toast.LENGTH_SHORT).show()
            }
        }

        binding.engineGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.engine = when (checkedId) {
                R.id.rbBaidu -> SettingsStore.ENGINE_BAIDU
                R.id.rbDevice -> SettingsStore.ENGINE_DEVICE
                else -> SettingsStore.ENGINE_FREE
            }
            updateBaiduPanel()
            notifySettingsChanged()
        }

        binding.baiduAppIdInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                settings.baiduAppId = binding.baiduAppIdInput.text.toString().trim()
                notifySettingsChanged()
            }
        }
        binding.baiduKeyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                settings.baiduKey = binding.baiduKeyInput.text.toString().trim()
                notifySettingsChanged()
            }
        }

        binding.downloadModelButton.setOnClickListener { downloadModel() }

        binding.autoModeCheck.setOnCheckedChangeListener { _, checked ->
            settings.autoMode = checked
            notifySettingsChanged()
        }
        binding.touchModeCheck.setOnCheckedChangeListener { _, checked ->
            settings.touchMode = checked
            notifySettingsChanged()
        }

        binding.importGlossaryButton.setOnClickListener {
            importGlossaryLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.exportGlossaryButton.setOnClickListener {
            exportGlossaryLauncher.launch("glossary.json")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val canOverlay = Settings.canDrawOverlays(this)
        binding.overlayPermissionButton.text = getString(
            if (canOverlay) R.string.grant_overlay_ok else R.string.grant_overlay_missing
        )

        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        binding.notificationPermissionButton.text = getString(
            if (canNotify) R.string.grant_notification_ok else R.string.grant_notification_missing
        )

        val isBaidu = settings.engine == SettingsStore.ENGINE_BAIDU
        binding.rbBaidu.isChecked = isBaidu
        binding.rbDevice.isChecked = settings.engine == SettingsStore.ENGINE_DEVICE
        binding.rbFree.isChecked = settings.engine == SettingsStore.ENGINE_FREE
        if (binding.baiduAppIdInput.text.toString() != settings.baiduAppId) {
            binding.baiduAppIdInput.setText(settings.baiduAppId)
        }
        if (binding.baiduKeyInput.text.toString() != settings.baiduKey) {
            binding.baiduKeyInput.setText(settings.baiduKey)
        }
        updateBaiduPanel()
        binding.autoModeCheck.isChecked = settings.autoMode
        binding.touchModeCheck.isChecked = settings.touchMode
        binding.glossaryCountText.text = getString(
            R.string.glossary_count_format,
            app.glossary.size()
        )
    }

    private fun updateBaiduPanel() {
        binding.baiduPanel.visibility = if (binding.rbBaidu.isChecked) View.VISIBLE else View.GONE
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchCapture()
        }
    }

    private fun launchCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        try {
            captureLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "无法发起屏幕捕获：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "请手动到系统设置里允许悬浮窗", Toast.LENGTH_LONG).show()
        }
    }

    private fun notifySettingsChanged() {
        if (binding.autoModeCheck.isChecked != settings.autoMode) {
            settings.autoMode = binding.autoModeCheck.isChecked
        }
        if (binding.touchModeCheck.isChecked != settings.touchMode) {
            settings.touchMode = binding.touchModeCheck.isChecked
        }
        if (!ScreenTranslateService.isRunning) return
        try {
            startService(
                Intent(this, ScreenTranslateService::class.java)
                    .setAction(ScreenTranslateService.ACTION_APPLY_SETTINGS)
            )
        } catch (_: Exception) {
        }
    }

    private fun downloadModel() {
        Toast.makeText(
            this,
            "开始准备离线翻译模型（约 30MB，需要网络和谷歌服务）…",
            Toast.LENGTH_LONG
        ).show()
        lifecycleScope.launch {
            val translator = DeviceTranslator(this@MainActivity)
            val ready = try {
                translator.ensureModel()
            } catch (e: Exception) {
                false
            }
            translator.close()
            Toast.makeText(
                this@MainActivity,
                if (ready) "离线翻译模型已就绪" else "模型不可用：设备可能缺少谷歌服务，请改用百度翻译",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
