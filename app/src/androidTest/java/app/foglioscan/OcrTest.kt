package app.foglioscan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.foglioscan.data.SettingsRepository
import app.foglioscan.ocr.PageOrientation
import app.foglioscan.session.ScanSessionViewModel
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.foglioscan.imaging.PageFilter
import app.foglioscan.imaging.PageProcessor
import app.foglioscan.ocr.PageText
import app.foglioscan.ocr.TextRecognizer
import app.foglioscan.pdf.ExportPage
import app.foglioscan.pdf.PdfCompression
import app.foglioscan.pdf.PdfExporter
import app.foglioscan.vision.DocumentLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * On-device OCR: accuracy on a clean page, on the real pipeline's output from perspective /
 * shadowed photos, and that the saved PDF really contains the text (extracted back with the
 * platform PDF engine, at every compression level).
 */
@RunWith(AndroidJUnit4::class)
class OcrTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext

    private val lines = listOf(
        "Fattura numero 2026-1124",
        "Il perché della città è più complesso",
        "Totale da pagare: 1.234,56 EUR",
        "The quick brown fox jumps over the lazy dog",
    )

    @Test
    fun cleanPageIsReadAccuratelyWithCorrectBoxes(): Unit = runBlocking {
        val w = 2480; val h = 3508
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f; typeface = Typeface.SERIF }
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            lines.forEachIndexed { i, s -> drawText(s, 200f, 400f + i * 160f, paint) }
        }
        val file = File(app.cacheDir, "ocr-clean.jpg").apply { outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        bmp.recycle()

        val t0 = System.nanoTime()
        val text = TextRecognizer.recognize(file, w, h)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val acc = accuracy(lines.joinToString("\n"), text.plain)
        Log.i(TAG, "clean page: ${text.lines.size} lines, char accuracy ${"%.1f".format(acc * 100)}%, $ms ms\n${text.plain}")
        assertTrue("accuracy $acc", acc > 0.97)
        // Boxes: each line's baseline is where it was drawn (±1% of the page height).
        lines.forEachIndexed { i, s ->
            val l = text.lines.minByOrNull { accuracy(s, it.text).let { a -> -a } }!!
            val baseY = (l.corners[5] + l.corners[7]) / 2
            val drawnBase = 400f + i * 160f + paint.descent()
            assertTrue("line $i baseline $baseY vs $drawnBase", abs(baseY - drawnBase) < h * 0.01f)
            assertTrue("line $i left ${l.corners[6]}", abs(l.corners[6] - 200f) < w * 0.01f)
        }
        assertPdfContainsText(file, w, h, text, listOf("Fattura", "2026-1124", "perché", "città", "1.234,56"))
    }

    @Test
    fun scannedPhotosBecomeSearchable(): Unit = runBlocking {
        val processor = PageProcessor(DocumentLocator.get(app))
        for (name in listOf("tilted", "projected", "upright")) {
            val src = File(app.cacheDir, "$name.jpg").also { f -> instr.context.assets.open("$name.jpg").use { i -> f.outputStream().use { i.copyTo(it) } } }
            val out = File(app.cacheDir, "ocr-$name.jpg")
            val r = processor.process(src, out, File(app.cacheDir, "ocr-$name-prev.jpg"), PageFilter.AUTO, 0, null, null)
            val t0 = System.nanoTime()
            val text = TextRecognizer.recognize(r.file, r.widthPx, r.heightPx)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val words = text.plain.lowercase().split(Regex("[^\\p{L}\\d#-]+")).toSet()
            val lorem = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore".split(" ")
            val found = lorem.count { it in words }
            Log.i(TAG, "$name: ${text.lines.size} lines, $found/${lorem.size} vocabulary words, $ms ms; first line: ${text.lines.firstOrNull()?.text}")
            assertTrue("$name: title not read: ${text.plain.take(200)}", text.plain.contains("Invoice") && text.plain.contains("2026-1116"))
            assertTrue("$name: body words $found", found >= lorem.size - 2)
            // The PDF must contain every line OCR found (first words of each, to stay robust to
            // how viewers join runs).
            val expected = text.lines.map { it.text.split(' ').first() }.filter { it.length >= 4 }.distinct()
            assertPdfContainsText(r.file, r.widthPx, r.heightPx, text, expected)
        }
    }

    @Test
    fun sidewaysAndUpsideDownPagesAreTurnedUpright() {
        runBlocking { SettingsRepository(app.applicationContext as Application).setOcr(true) }
        lateinit var vm: ScanSessionViewModel
        instr.runOnMainSync { vm = ScanSessionViewModel(app.applicationContext as Application, SavedStateHandle()); vm.discard() }
        val photo = PageProcessor.decode(File(app.cacheDir, "upright-src.jpg").also { f ->
            instr.context.assets.open("upright.jpg").use { i -> f.outputStream().use { i.copyTo(it) } }
        }, 4096)
        val angles = listOf(0, 90, 180, 270)
        val uris = angles.map { deg ->
            val b = if (deg == 0) photo else Bitmap.createBitmap(photo, 0, 0, photo.width, photo.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
            val f = File(app.cacheDir, "rot-$deg.jpg")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            Uri.fromFile(f)
        }
        instr.runOnMainSync { vm.importImages(uris) }
        // Wait for the pages to arrive, then for every page to be read (and turned, if needed).
        val t0 = System.currentTimeMillis()
        while (vm.pages.value.size < angles.size) { Thread.sleep(200); check(System.currentTimeMillis() - t0 < 60_000) }
        runBlocking { vm.awaitRecognized() }
        vm.pages.value.forEachIndexed { i, p ->
            val text = p.text!!
            Log.i(TAG, "photo rotated ${angles[i]}° → page rotation ${p.spec.rotation}°, first line '${text.lines.firstOrNull()?.text}'")
            assertTrue("page ${angles[i]}: text still rotated", PageOrientation.correction(text) == 0 && text.lines.size > 10)
            assertTrue("page ${angles[i]}: ${text.plain.take(80)}", text.plain.startsWith("Invoice"))
            assertTrue(p.spec.oriented)
        }
        instr.runOnMainSync { vm.discard() }
    }

    /** Writes a one-page PDF at every compression level and extracts its text with the platform engine. */
    private fun assertPdfContainsText(file: File, w: Int, h: Int, text: PageText, expected: List<String>) {
        for (level in PdfCompression.entries) {
            val pdf = File(app.cacheDir, "ocr-${level.name}.pdf")
            pdf.outputStream().buffered().use { PdfExporter.write(it, listOf(ExportPage(file, w, h, PageFilter.AUTO, text)), level, "OCR") }
            if (Build.VERSION.SDK_INT < 35) continue
            val extracted = PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                r.openPage(0).use { p -> p.textContents.joinToString("\n") { it.text } }
            }
            for (e in expected) assertTrue("$level: '$e' not in extracted text: ${extracted.take(300)}", extracted.contains(e))
            if (level == PdfCompression.BALANCED) Log.i(TAG, "extracted from PDF: ${extracted.take(160).replace('\n', '|')}")
            pdf.delete()
        }
    }

    /** 1 − normalized Levenshtein distance. */
    private fun accuracy(expected: String, actual: String): Double {
        val a = expected; val b = actual
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, 1)
    }

    private companion object { const val TAG = "OcrTest" }
}
