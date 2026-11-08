package com.screentranslate.app.ocr

import android.graphics.Rect

/** OCR 识别出的一段文字（按文本块 block 粒度） */
data class OcrSegment(
    val text: String,
    val box: Rect,
    val lineCount: Int
)
