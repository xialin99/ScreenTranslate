package com.screentranslate.app.translate

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 术语表：英文 -> 中文，整行精确匹配优先于翻译引擎。
 * 默认从 assets/glossary.json 加载，导入后保存到 filesDir/glossary.json 覆盖默认表。
 */
class Glossary(context: Context) {

    private val appContext = context.applicationContext
    private val entries = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val file = File(appContext.filesDir, FILE_NAME)
            val text = try {
                if (file.exists()) file.readText(Charsets.UTF_8) else readAsset()
            } catch (_: Exception) {
                null
            }
            parseIntoMap(text)
            loaded = true
        }
    }

    fun lookup(raw: String): String? {
        ensureLoaded()
        return entries[normalize(raw)]
    }

    fun size(): Int {
        ensureLoaded()
        return entries.size
    }

    fun exportTo(uri: Uri): Boolean {
        ensureLoaded()
        return try {
            val json = JSONObject()
            entries.forEach { (key, value) -> json.put(key, value) }
            val output = appContext.contentResolver.openOutputStream(uri) ?: return false
            output.use { it.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * @return 导入条数；失败返回 -1；内容为空返回 0
     */
    fun importFrom(uri: Uri): Int {
        return try {
            val text = appContext.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return 0
            val json = JSONObject(text)
            val newEntries = HashMap<String, String>()
            json.keys().forEach { key ->
                val value = json.optString(key, "").trim()
                if (key.isNotBlank() && value.isNotEmpty()) {
                    newEntries[normalize(key)] = value
                }
            }
            if (newEntries.isEmpty()) return 0
            entries.clear()
            entries.putAll(newEntries)
            saveToFile()
            newEntries.size
        } catch (_: Exception) {
            -1
        }
    }

    private fun readAsset(): String? =
        appContext.assets.open(FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun parseIntoMap(text: String?) {
        if (text == null) return
        try {
            val json = JSONObject(text)
            json.keys().forEach { key ->
                val value = json.optString(key, "").trim()
                if (key.isNotBlank() && value.isNotEmpty()) {
                    entries[normalize(key)] = value
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveToFile() {
        try {
            val json = JSONObject()
            entries.forEach { (key, value) -> json.put(key, value) }
            File(appContext.filesDir, FILE_NAME).writeText(json.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val FILE_NAME = "glossary.json"
        private val WHITESPACE = Regex("\\s+")

        fun normalize(text: String): String =
            WHITESPACE.replace(text.trim().lowercase(), " ")
    }
}
