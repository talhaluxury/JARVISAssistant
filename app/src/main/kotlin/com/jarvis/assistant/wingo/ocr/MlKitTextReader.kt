package com.jarvis.assistant.wingo.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device text recognition (ML Kit, bundled model - no network needed). Produces word-level [OcrLine]s. */
class MlKitTextReader : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(bitmap: Bitmap): List<OcrLine> = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val lines = ArrayList<OcrLine>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val box = element.boundingBox ?: continue
                            lines.add(OcrLine(element.text, box.left, box.top, box.right, box.bottom, element.confidence))
                        }
                    }
                }
                continuation.resume(lines)
            }
            .addOnFailureListener { error -> continuation.resumeWithException(error) }
    }

    override fun close() {
        recognizer.close()
    }
}
