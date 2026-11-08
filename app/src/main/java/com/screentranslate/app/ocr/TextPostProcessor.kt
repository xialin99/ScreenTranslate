package com.screentranslate.app.ocr

import android.graphics.Rect

/**
 * OCR 结果后处理：
 * 1. 丢掉不含英文字母的块（时间、电量、百分比等）
 * 2. 丢掉太小的块（图标里的碎字）
 * 3. 丢掉状态栏 / 导航栏区域的块
 * 4. 同一行相邻的块合并（按钮文字被切碎的情况）
 */
object TextPostProcessor {

    private val LATIN = Regex("[A-Za-z]")

    fun process(
        segments: List<OcrSegment>,
        minHeightPx: Int,
        topInset: Int,
        bottomInset: Int,
        screenHeight: Int,
        minGapPx: Int
    ): List<OcrSegment> {
        val filtered = segments.filter { segment ->
            val box = segment.box
            LATIN.containsMatchIn(segment.text) &&
                box.height() >= minHeightPx &&
                box.centerY() > topInset &&
                box.centerY() < screenHeight - bottomInset
        }
        if (filtered.size < 2) return filtered

        val used = BooleanArray(filtered.size)
        val merged = ArrayList<OcrSegment>(filtered.size)

        for (i in filtered.indices) {
            if (used[i]) continue
            used[i] = true
            var text = filtered[i].text
            var box = Rect(filtered[i].box)
            var lines = filtered[i].lineCount
            var changed = true
            while (changed) {
                changed = false
                for (j in filtered.indices) {
                    if (used[j]) continue
                    val other = filtered[j]
                    if (shouldMerge(box, other.box, minGapPx)) {
                        text = "$text ${other.text}"
                        box = Rect(
                            minOf(box.left, other.box.left),
                            minOf(box.top, other.box.top),
                            maxOf(box.right, other.box.right),
                            maxOf(box.bottom, other.box.bottom)
                        )
                        lines += other.lineCount
                        used[j] = true
                        changed = true
                    }
                }
            }
            merged.add(OcrSegment(text, box, lines.coerceAtLeast(1)))
        }
        return merged
    }

    private fun shouldMerge(a: Rect, b: Rect, minGapPx: Int): Boolean {
        val verticalOverlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (verticalOverlap <= 0) return false
        if (verticalOverlap < minOf(a.height(), b.height()) * 0.6f) return false
        val gap = when {
            b.left >= a.right -> b.left - a.right
            a.left >= b.right -> a.left - b.right
            else -> 0
        }
        return gap <= minGapPx * 2
    }
}
