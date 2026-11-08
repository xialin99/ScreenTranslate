package com.screentranslate.app.capture

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/**
 * 帧间差异检测：两帧缩到 64x64 灰度网格后比较，
 * 忽略自家悬浮球 / 译文卡片覆盖的区域。
 */
object FrameDiff {

    private const val GRID = 64
    private const val CHANNEL_THRESHOLD = 60

    fun changedRatio(previous: Bitmap, current: Bitmap, ignoreRects: List<Rect>): Float {
        if (previous.width != current.width || previous.height != current.height) return 1f
        if (previous === current) return 0f

        val oldSmall = Bitmap.createScaledBitmap(previous, GRID, GRID, false)
        val newSmall = Bitmap.createScaledBitmap(current, GRID, GRID, false)
        try {
            val oldPixels = IntArray(GRID * GRID)
            val newPixels = IntArray(GRID * GRID)
            oldSmall.getPixels(oldPixels, 0, GRID, 0, 0, GRID, GRID)
            newSmall.getPixels(newPixels, 0, GRID, 0, 0, GRID, GRID)

            val scaleX = GRID.toFloat() / current.width
            val scaleY = GRID.toFloat() / current.height
            val scaledIgnore = ignoreRects.map { rect ->
                Rect(
                    (rect.left * scaleX).toInt(),
                    (rect.top * scaleY).toInt(),
                    (rect.right * scaleX + 1).toInt(),
                    (rect.bottom * scaleY + 1).toInt()
                )
            }

            var total = 0
            var changed = 0
            for (y in 0 until GRID) {
                for (x in 0 until GRID) {
                    if (scaledIgnore.any { it.contains(x, y) }) continue
                    total++
                    val first = oldPixels[y * GRID + x]
                    val second = newPixels[y * GRID + x]
                    val difference =
                        abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)) +
                            abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)) +
                            abs((first and 0xFF) - (second and 0xFF))
                    if (difference > CHANNEL_THRESHOLD) changed++
                }
            }
            return if (total == 0) 0f else changed.toFloat() / total
        } finally {
            if (oldSmall !== previous && oldSmall !== current) oldSmall.recycle()
            if (newSmall !== previous && newSmall !== current) newSmall.recycle()
        }
    }
}
