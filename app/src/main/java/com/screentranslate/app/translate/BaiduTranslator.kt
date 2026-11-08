package com.screentranslate.app.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 百度翻译通用 API（标准版免费，限速 1 次/秒）。
 * 整屏所有文字合并成一次请求，按 \n 拆分返回。
 */
class BaiduTranslator(
    private val appId: String,
    private val secretKey: String
) : TranslateEngine {

    override val name: String = "百度翻译"

    private val mutex = Mutex()

    @Volatile
    private var lastRequestAt = 0L

    override suspend fun translate(lines: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        mutex.withLock {
            val result = ArrayList<String>(lines.size)
            var index = 0
            while (index < lines.size) {
                val chunk = ArrayList<String>()
                var chunkLength = 0
                while (index < lines.size) {
                    val line = lines[index]
                    if (chunk.isNotEmpty() && chunkLength + line.length > 1200) break
                    chunk.add(line)
                    chunkLength += line.length + 1
                    index++
                }
                result.addAll(requestChunk(chunk))
                if (index < lines.size) delay(1100)
            }
            result
        }
    }

    private suspend fun requestChunk(chunk: List<String>): List<String> {
        val waitMs = RATE_LIMIT_MS - (System.currentTimeMillis() - lastRequestAt)
        if (waitMs > 0) delay(waitMs)
        lastRequestAt = System.currentTimeMillis()

        val q = chunk.joinToString("\n")
        val salt = System.currentTimeMillis().toString()
        val sign = md5(appId + q + salt + secretKey)
        val body = buildString {
            append("q=").append(URLEncoder.encode(q, "UTF-8"))
            append("&from=en&to=zh")
            append("&appid=").append(URLEncoder.encode(appId, "UTF-8"))
            append("&salt=").append(salt)
            append("&sign=").append(sign)
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            if (code !in 200..299) {
                throw IllegalStateException("百度翻译 HTTP $code")
            }
            val json = JSONObject(text)
            if (json.has("error_code")) {
                throw IllegalStateException("百度翻译错误 ${json.optString("error_code")}：${json.optString("error_msg")}")
            }
            val array = json.optJSONArray("trans_result")
                ?: throw IllegalStateException("百度翻译返回内容异常")
            val output = ArrayList<String>(chunk.size)
            for (position in chunk.indices) {
                val item = if (position < array.length()) array.optJSONObject(position) else null
                // 拿不到译文就返回空串，交给 TranslationManager 降级到下一个引擎，
                // 而不是把英文原文当成译文渲染回去（那看起来就像没翻译）
                output.add(item?.optString("dst").orEmpty())
            }
            return output
        } finally {
            connection.disconnect()
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate"
        private const val RATE_LIMIT_MS = 1100L
    }
}
