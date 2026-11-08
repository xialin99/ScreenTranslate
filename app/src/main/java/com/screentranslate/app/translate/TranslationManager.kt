package com.screentranslate.app.translate

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.util.LruCache
import com.screentranslate.app.ocr.OcrSegment
import com.screentranslate.app.settings.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TranslatedSegment(
    val original: String,
    val translation: String,
    val box: Rect,
    val lineCount: Int
)

/**
 * 翻译调度：术语表 -> 缓存 -> 引擎链。
 *
 * 引擎「链」而不是「单选」：按用户选择的引擎优先，失败自动降级到下一个。
 * 这样即使百度密钥无效/欠费/未开通，屏幕依旧能翻译出来，不会只弹一句「翻译失败」。
 *
 * 降级顺序（设置里选「自动」时）：
 *   百度（已填密钥）-> 免密钥云端 -> 设备端离线
 */
class TranslationManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val glossary: Glossary
) {

    private val cache = object : LruCache<String, String>(2048) {
        override fun sizeOf(key: String, value: String): Int = 1
    }

    private val mutex = Mutex()

    private var chain: List<TranslateEngine> = emptyList()

    @Volatile
    private var chainSignature: String = ""

    /** 全链路都失败时才有值；任何一条链路成功都保持 null */
    @Volatile
    var lastError: String? = null
        private set

    /** 非致命提示：主引擎失败但已自动降级时给出说明，供界面展示 */
    @Volatile
    var lastNotice: String? = null
        private set

    private fun ensureChain(): List<TranslateEngine> {
        val signature = "${settings.engine}|${settings.baiduAppId}|${settings.baiduKey}"
        if (signature == chainSignature && chain.isNotEmpty()) return chain

        closeEngines()
        val built = ArrayList<TranslateEngine>(3)
        val appId = settings.baiduAppId.trim()
        val key = settings.baiduKey.trim()
        val baiduReady = appId.isNotEmpty() && key.isNotEmpty()
        val baidu = if (baiduReady) BaiduTranslator(appId, key) else null
        val free = FreeWebTranslator()
        val device = DeviceTranslator(context)

        when (settings.engine) {
            // 用户明确选了百度：百度优先，但后面永远有兜底
            SettingsStore.ENGINE_BAIDU -> {
                if (baidu != null) built.add(baidu)
                built.add(free)
                built.add(device)
            }
            // 用户明确选了设备端：设备端优先（有 GMS 时才可能成功）
            SettingsStore.ENGINE_DEVICE -> {
                built.add(device)
                if (baidu != null) built.add(baidu)
                built.add(free)
            }
            // 默认：填了百度密钥就优先用百度，否则免密钥云端优先
            else -> {
                if (baidu != null) built.add(baidu)
                built.add(free)
                built.add(device)
            }
        }

        chain = built
        chainSignature = signature
        return chain
    }

    suspend fun translate(segments: List<OcrSegment>): List<TranslatedSegment> {
        if (segments.isEmpty()) return emptyList()
        glossary.ensureLoaded()
        lastError = null
        lastNotice = null

        val results = arrayOfNulls<String>(segments.size)
        val pending = ArrayList<Pair<Int, String>>()

        segments.forEachIndexed { index, segment ->
            val normalized = Glossary.normalize(segment.text)
            val gloss = glossary.lookup(normalized)
            if (gloss != null) {
                results[index] = gloss
                return@forEachIndexed
            }
            val cached = cache.get(normalized)
            if (cached != null) {
                results[index] = cached
            } else {
                pending.add(index to segment.text)
            }
        }

        if (pending.isNotEmpty()) {
            mutex.withLock {
                val engines = ensureChain()
                val failures = ArrayList<String>()
                // 还没拿到译文的段落下标（某个引擎只翻出一部分时，剩下的继续交给下一个引擎）
                var remaining = pending

                for (engine in engines) {
                    if (remaining.isEmpty()) break
                    val sources = remaining.map { it.second }
                    val translated = try {
                        engine.translate(sources)
                    } catch (e: Exception) {
                        failures.add("${engine.name}：${e.message ?: "请求失败"}")
                        Log.w(TAG, "engine ${engine.name} failed", e)
                        continue
                    }

                    val unresolved = ArrayList<Pair<Int, String>>()
                    remaining.forEachIndexed { position, pair ->
                        val text = translated.getOrNull(position)?.trim().orEmpty()
                        if (text.isEmpty()) {
                            unresolved.add(pair)
                        } else {
                            results[pair.first] = text
                            // 只有真正拿到译文才写缓存，避免把空结果缓存下来
                            cache.put(Glossary.normalize(pair.second), text)
                        }
                    }
                    if (unresolved.size == remaining.size) {
                        failures.add("${engine.name}：没有返回译文")
                        continue
                    }
                    remaining = unresolved
                    if (failures.isNotEmpty()) {
                        lastNotice = "已自动改用「${engine.name}」翻译（${failures.first()}）"
                    }
                }

                // 一条都没翻出来才算「翻译失败」；翻出了一些、剩几条没成功只算提示
                val anySuccess = results.any { it != null }
                lastError = if (!anySuccess) {
                    if (failures.isEmpty()) "翻译失败" else failures.joinToString("；")
                } else {
                    null
                }
                if (anySuccess && remaining.isNotEmpty()) {
                    lastNotice = "部分文字未翻译成功（${failures.firstOrNull() ?: "接口未返回"}）"
                }
            }
        }

        return segments.mapIndexedNotNull { index, segment ->
            val text = results[index] ?: return@mapIndexedNotNull null
            TranslatedSegment(segment.text, text, segment.box, segment.lineCount)
        }
    }

    fun close() {
        closeEngines()
        cache.evictAll()
    }

    private fun closeEngines() {
        chain.forEach { runCatching { it.close() } }
        chain = emptyList()
        chainSignature = ""
    }

    companion object {
        private const val TAG = "TranslationManager"
    }
}