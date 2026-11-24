package app.foglioscan.ocr

import android.graphics.Bitmap
import app.foglioscan.imaging.PageProcessor
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR with ML Kit Text Recognition v2 (Latin script model bundled in the APK: works
 * offline and without Google Play services). Runs on the enhanced page, whose flat background
 * and sharpened text are close to ideal OCR input.
 */
object TextRecognizer {

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Recognizes the text of the rendering [file] ([widthPx]×[heightPx]). */
    suspend fun recognize(file: File, widthPx: Int, heightPx: Int): PageText {
        val bitmap = withContext(Dispatchers.IO) { PageProcessor.decode(file, OCR_LONG_SIDE) }
        try {
            return recognize(bitmap, widthPx, heightPx)
        } finally {
            bitmap.recycle()
        }
    }

    /** Recognizes [bitmap]; coordinates are scaled to a [widthPx]×[heightPx] page. */
    suspend fun recognize(bitmap: Bitmap, widthPx: Int = bitmap.width, heightPx: Int = bitmap.height): PageText {
        val result = client.process(InputImage.fromBitmap(bitmap, 0)).await()
        val sx = widthPx.toFloat() / bitmap.width
        val sy = heightPx.toFloat() / bitmap.height
        val lines = ArrayList<TextLine>()
        result.textBlocks.forEachIndexed { b, block ->
            for (line in block.lines) {
                val text = line.text.trim()
                val pts = line.cornerPoints
                if (text.isEmpty() || pts == null || pts.size != 4) continue
                val corners = FloatArray(8)
                for (i in 0 until 4) {
                    corners[2 * i] = pts[i].x * sx
                    corners[2 * i + 1] = pts[i].y * sy
                }
                lines += TextLine(text, b, corners)
            }
        }
        return PageText(widthPx, heightPx, lines)
    }

    /**
     * Long side the page is recognized at: ≈200 dpi for A4, where body text is ~30 px tall
     * (ML Kit wants ≥16 px per character); larger only costs time.
     */
    private const val OCR_LONG_SIDE = 2480

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
