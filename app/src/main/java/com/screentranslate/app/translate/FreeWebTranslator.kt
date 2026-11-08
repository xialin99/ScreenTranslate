package com.screentranslate.app.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 免密钥云端翻译（国内网络可直连，无需注册、无需填密钥）。
 *
 * 依次尝试多个公开接口，任一成功即返回，全部失败才抛异常。
 * 这些接口是站点给网页/演示用的公开通道，可能随时变更或限流，
 * 所以定位是「兜底」：调用顺序由 [TranslationManager] 决定（免费 -> 百度 -> 设备端）。
 */
class FreeWebTranslator : TranslateEngine {

    override val name: String = "免费云端翻译"

    override suspend fun translate(lines: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()

        val failures = ArrayList<String>()
        for (provider in PROVIDERS) {
            try {
                val output = provider.translate(lines)
                if (output.size == lines.size) return@withContext output
                failures.add("${provider.name}返回条数不匹配")
            } catch (e: Exception) {
                failures.add(e.message ?: "${provider.name}请求失败")
            }
        }
        throw IllegalStateException("免费翻译暂不可用（${failures.joinToString("；")}）")
    }

    // ------------------------------------------------------------------ 接口抽象

    private abstract class Provider {
        abstract val name: String

        /** 单次请求能携带的最大字符数（各接口限制不同） */
        protected abstract val maxChars: Int

        /** 一次真实的批量请求：入参行数与返回译文行数必须一致 */
        protected abstract suspend fun request(lines: List<String>): List<String>

        suspend fun translate(lines: List<String>): List<String> {
            // 超长行先按上限切片，翻译完再拼回同一行，避免截断丢内容
            val pieces = ArrayList<String>(lines.size)
            val owner = ArrayList<Int>(lines.size)
            lines.forEachIndexed { index, line ->
                if (line.length <= maxChars) {
                    pieces.add(line)
                    owner.add(index)
                } else {
                    splitLongLine(line, maxChars).forEach { piece ->
                        pieces.add(piece)
                        owner.add(index)
                    }
                }
            }

            val translatedPieces = ArrayList<String>(pieces.size)
            var start = 0
            while (start < pieces.size) {
                var end = start
                var length = 0
                while (end < pieces.size) {
                    val next = length + pieces[end].length + 1
                    if (end > start && next > maxChars) break
                    length = next
                    end++
                }
                val chunk = pieces.subList(start, end)
                val result = request(chunk)
                if (result.size != chunk.size) {
                    throw IllegalStateException("$name 返回条数不匹配")
                }
                translatedPieces.addAll(result)
                start = end
            }

            val merged = List(lines.size) { StringBuilder() }
            translatedPieces.forEachIndexed { index, text ->
                merged[owner[index]].append(text)
            }
            return merged.map { it.toString().trim() }
        }

        /** 优先在空白处切分，切不动就硬切 */
        private fun splitLongLine(line: String, limit: Int): List<String> {
            val parts = ArrayList<String>(line.length / limit + 1)
            var cursor = 0
            while (cursor < line.length) {
                val end = minOf(cursor + limit, line.length)
                if (end == line.length) {
                    parts.add(line.substring(cursor))
                    break
                }
                val cut = line.lastIndexOf(' ', end).takeIf { it > cursor + limit / 2 } ?: end
                parts.add(line.substring(cursor, cut))
                cursor = cut
            }
            return parts
        }

        protected fun httpGet(urlText: String): String {
            val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return text
            } finally {
                runCatching { connection.disconnect() }
            }
        }

        /**
         * 请求里用 \n 把多行拼成一段，返回时再拆回对应条数。
         * 条数对不上时按位置兜底，保证结果长度始终与入参一致。
         */
        protected fun splitKeepingCount(joined: String, count: Int): List<String> {
            if (count == 1) return listOf(joined.trim())
            val parts = joined.split("\n")
            if (parts.size == count) return parts.map { it.trim() }
            return List(count) { index -> parts.getOrNull(index)?.trim().orEmpty() }
        }

        protected fun encode(text: String): String =
            URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }

    // ------------------------------------------------------------------ 有道公开接口

    private class YoudaoProvider : Provider() {
        override val name: String = "有道"

        /** 实测 GET 3000 字符仍返回 200，这里留足余量 */
        override val maxChars: Int = 1200

        override suspend fun request(lines: List<String>): List<String> {
            val query = lines.joinToString("\n")
            val json = JSONObject(
                httpGet("$YOUDAO_ENDPOINT?q=${encode(query)}&from=en&to=zh-CHS")
            )
            val code = json.optString("errorCode", "")
            if (code.isNotEmpty() && code != "0") {
                throw IllegalStateException("有道接口错误码 $code")
            }
            val array = json.optJSONArray("translation")
                ?: throw IllegalStateException("有道接口返回格式异常")
            // 有道有时整段放在一个元素里（用 \n 分隔），有时按行拆成多个元素，两种都要认
            val joined = when {
                array.length() == 0 -> ""
                array.length() == 1 -> array.optString(0)
                else -> buildString {
                    for (index in 0 until array.length()) {
                        if (index > 0) append('\n')
                        append(array.optString(index))
                    }
                }
            }
            if (joined.isBlank()) throw IllegalStateException("有道接口返回空译文")
            return splitKeepingCount(joined, lines.size)
        }
    }

    // ------------------------------------------------------------------ MyMemory 公开接口

    private class MyMemoryProvider : Provider() {
        override val name: String = "MyMemory"

        /** 硬限制 500 字符（超出返回 403），按 400 分块留余量 */
        override val maxChars: Int = 400

        override suspend fun request(lines: List<String>): List<String> {
            val query = lines.joinToString("\n")
            val json = JSONObject(
                httpGet("$MYMEMORY_ENDPOINT?q=${encode(query)}&langpair=en%7Czh-CN")
            )
            val status = json.optInt("responseStatus", 200)
            if (status != 200) {
                val detail = json.optString("responseDetails").take(80)
                throw IllegalStateException("MyMemory $status $detail")
            }
            val joined = json.optJSONObject("responseData")
                ?.optString("translatedText").orEmpty()
            if (joined.isBlank()) throw IllegalStateException("MyMemory 返回空译文")
            return splitKeepingCount(joined, lines.size)
        }
    }

    companion object {
        private val PROVIDERS: List<Provider> = listOf(YoudaoProvider(), MyMemoryProvider())

        private const val YOUDAO_ENDPOINT = "https://aidemo.youdao.com/trans"
        private const val MYMEMORY_ENDPOINT = "https://api.mymemory.translated.net/get"

        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 9000

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}