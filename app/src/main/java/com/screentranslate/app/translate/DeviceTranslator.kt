package com.screentranslate.app.translate

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.google.mlkit.nl.translate.Translator as MlKitTranslator

/**
 * 设备端离线翻译（ML Kit，英 -> 中）。
 * 首次使用需要联网下载约 30MB 模型；模型管理依赖 Google Play 服务，
 * 国产无 GMS 机型请改用百度云端引擎。
 */
class DeviceTranslator(context: Context) : TranslateEngine {

    override val name: String = "设备端 ML Kit"

    @Suppress("unused")
    private val appContext = context.applicationContext

    private val translator: MlKitTranslator by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()
        Translation.getClient(options)
    }

    @Volatile
    private var modelReady = false

    suspend fun ensureModel(): Boolean {
        if (modelReady) return true
        val ok = suspendCancellableCoroutine { cont ->
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    modelReady = true
                    if (cont.isActive) cont.resume(true)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(false)
                }
        }
        return ok
    }

    override suspend fun translate(lines: List<String>): List<String> {
        if (!ensureModel()) {
            throw IllegalStateException(
                "离线翻译模型不可用（设备可能没有谷歌服务），请在设置里改用百度翻译"
            )
        }
        val results = ArrayList<String>(lines.size)
        for (line in lines) {
            results.add(translateOne(line) ?: "")
        }
        return results
    }

    private suspend fun translateOne(text: String): String? =
        suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

    override fun close() {
        try {
            translator.close()
        } catch (_: Exception) {
        }
    }
}
