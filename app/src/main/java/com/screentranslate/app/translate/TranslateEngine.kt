package com.screentranslate.app.translate

/** 翻译引擎抽象：设备端 / 云端共用同一接口 */
interface TranslateEngine {
    val name: String

    /** 以行为单位批量翻译（英文 -> 中文），返回数量与入参一致 */
    suspend fun translate(lines: List<String>): List<String>

    fun close() {}
}
