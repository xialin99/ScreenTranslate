package com.screentranslate.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit 文字识别（拉丁文字，bundled 模型，离线可用）。
 * 按 TextBlock 粒度返回：一个按钮/一行列表项通常就是一个 block。
 */
class OcrProcessor {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(bitmap: Bitmap): List<OcrSegment> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val segments = ArrayList<OcrSegment>()
                for (block in visionText.textBlocks) {
                    val box = block.boundingBox ?: continue
                    val text = block.text.trim()
                    if (text.isEmpty()) continue
                    segments.add(
                        OcrSegment(
                            text = text,
                            box = Rect(box),
                            lineCount = block.lines.size.coerceAtLeast(1)
                        )
                    )
                }
                if (cont.isActive) cont.resume(segments)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {
        }
    }
}
