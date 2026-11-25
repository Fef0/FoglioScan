package app.foglioscan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.foglioscan.data.DocumentRepository
import app.foglioscan.data.SearchIndex
import app.foglioscan.data.SettingsRepository
import app.foglioscan.data.TextSearch
import app.foglioscan.imaging.BookSplit
import app.foglioscan.imaging.PageDewarper
import app.foglioscan.imaging.PageFilter
import app.foglioscan.imaging.PageProcessor
import app.foglioscan.ocr.TextRecognizer
import app.foglioscan.pdf.ExportPage
import app.foglioscan.pdf.PageSize
import app.foglioscan.pdf.PdfCompression
import app.foglioscan.pdf.PdfExporter
import app.foglioscan.pdf.PdfImage
import app.foglioscan.pdf.PdfPageExtractor
import app.foglioscan.pdf.PdfWriter
import app.foglioscan.session.ScanSessionViewModel
import app.foglioscan.vision.DocumentLocator
import app.foglioscan.vision.Point2
import app.foglioscan.vision.Quad
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

/** Book mode, CCITT G4 pages, page sizes and in-app search, on the real pipeline. */
@RunWith(AndroidJUnit4::class)
class DocumentFeaturesTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext.applicationContext as Application
    private val out = File(app.getExternalFilesDir(null), "features-test").apply { mkdirs() }

    private fun asset(name: String): File = File(app.cacheDir, name).also { f ->
        instr.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
    }

    private fun points(name: String): List<Point2> = instr.context.assets.open(name).bufferedReader().readLines()
        .filter { it.isNotBlank() }.map { l -> l.trim().split(' ').let { Point2(it[0].toFloat(), it[1].toFloat()) } }

    @Test
    fun bookSpreadsSplitAtTheSpineIntoTwoFlatPages(): Unit = runBlocking {
        val photo = PageProcessor.decode(asset("curved_spread.jpg"), 2000)
        val w = photo.width; val h = photo.height
        val src = IntArray(w * h).also { photo.getPixels(it, 0, w, 0, 0, w, h) }
        val outline = DocumentLocator.get(app).locatePrecise(photo)
        photo.recycle()
        assertNotNull("spread not detected", outline)
        val split = BookSplit.split(src, w, h, outline!!)
        val (top, bottom) = points("curved_spread_spine.txt")
        val width = outline.tl.distanceTo(outline.tr) * w
        val eTop = Point2(split.spineTop.x * w, split.spineTop.y * h).distanceTo(Point2(top.x * w, top.y * h)) / width
        val eBottom = Point2(split.spineBottom.x * w, split.spineBottom.y * h).distanceTo(Point2(bottom.x * w, bottom.y * h)) / width
        Log.i(TAG, "spine error top %.2f%% bottom %.2f%% of the spread width, gutter depth %.2f".format(eTop * 100, eBottom * 100, split.confidence))
        assertTrue("spine top off by ${eTop * 100}%", eTop < 0.02f)
        assertTrue("spine bottom off by ${eBottom * 100}%", eBottom < 0.03f)

        // Each half through the real pipeline: flattened, A4, and the right page.
        val processor = PageProcessor(DocumentLocator.get(app)) { PageDewarper.get(app) }
        val source = asset("curved_spread.jpg")
        for ((half, title) in listOf(split.left to "2026-007", split.right to "2026-057")) {
            val r = processor.process(source, File(out, "half-$title.jpg"), File(out, "half-$title-prev.jpg"), PageFilter.AUTO, 0, half, null, flatten = null)
            val aspect = r.heightPx.toFloat() / r.widthPx
            val text = TextRecognizer.recognize(r.file, r.widthPx, r.heightPx).plain
            Log.i(TAG, "$title: flattened=${r.flattened} ${r.widthPx}x${r.heightPx} aspect %.3f, text: ${text.take(40)}".format(aspect))
            assertTrue("$title flattened", r.flattened)
            assertEquals("$title A4", 1.4142f, aspect, 0.07f)
            assertTrue("$title should read 'Invoice $title': ${text.take(80)}", text.contains(title))
        }
    }

    @Test
    fun blackAndWhitePagesUseG4AndRenderIdentically(): Unit = runBlocking {
        val processor = PageProcessor(DocumentLocator.get(app))
        val r = processor.process(asset("upright.jpg"), File(out, "bw.jpg"), File(out, "bw-prev.jpg"), PageFilter.BLACK_WHITE, 0, null, null)
        val page = ExportPage(r.file, r.widthPx, r.heightPx, PageFilter.BLACK_WHITE)
        val g4 = PdfExporter.encode(page, PdfCompression.BALANCED) as PdfImage.Bilevel
        assertEquals(PdfImage.Bilevel.Encoding.G4, g4.encoding)

        // What the previous version wrote (Flate), for comparison.
        val bmp = PageProcessor.decode(r.file, PdfCompression.BALANCED.maxLongSide!!)
        val row = IntArray(bmp.width); val packed = ByteArray((bmp.width + 7) / 8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION); val flate = ByteArrayOutputStream(); val buf = ByteArray(65536)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1); PdfExporter.packBilevelRow(row, packed)
            deflater.setInput(packed); while (!deflater.needsInput()) { val n = deflater.deflate(buf); if (n == 0) break; flate.write(buf, 0, n) }
        }
        deflater.finish(); while (!deflater.finished()) flate.write(buf, 0, deflater.deflate(buf)); deflater.end()
        bmp.recycle()
        Log.i(TAG, "B&W page ${g4.width}x${g4.height}: G4 ${g4.data.size / 1024} KB vs Flate ${flate.size() / 1024} KB (%.1fx)".format(flate.size().toFloat() / g4.data.size))
        assertTrue("G4 should be smaller than Flate", g4.data.size < flate.size())

        // The platform renderer (pdfium) draws exactly the pixels our decoder gives back.
        val pdf = File(out, "bw.pdf")
        pdf.outputStream().use { PdfExporter.write(it, listOf(page), PdfCompression.BALANCED, "bw") }
        val decodedPng = File(out, "bw-decoded.png")
        PdfPageExtractor.open(pdf)!!.use { x -> PdfExporter.bilevelToPng(x.page(0) as PdfImage.Bilevel, decodedPng) }
        val expected = BitmapFactory.decodeFile(decodedPng.path)
        val rendered = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { rr -> rr.openPage(0).use { p ->
                Bitmap.createBitmap(expected.width, expected.height, Bitmap.Config.ARGB_8888).also { b ->
                    b.eraseColor(Color.WHITE)
                    // Page.width/height are rounded to whole points: scale from the exact size,
                    // so one image pixel lands on one bitmap pixel.
                    val l = PdfWriter.layout(expected.width, expected.height, PageSize.AUTO)
                    val m = android.graphics.Matrix().apply { setScale(b.width / l.pw, b.height / l.ph) }
                    p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } }
        }
        File(out, "bw-pdfium.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Exact agreement, and agreement within one pixel (rendering resamples glyph edges).
        var same = 0L; var near = 0L; var n = 0L
        fun black(b: Bitmap, x: Int, y: Int) = Color.red(b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))) <= 127
        for (y in 0 until expected.height step 2) for (x in 0 until expected.width step 2) {
            val b = black(rendered, x, y)
            n++
            if (black(expected, x, y) == b) { same++; near++; continue }
            if ((-1..1).any { dy -> (-1..1).any { dx -> black(expected, x + dx, y + dy) == b } }) near++
        }
        Log.i(TAG, "pdfium vs our G4 decoder: %.2f%% of pixels agree exactly, %.3f%% within 1 px".format(same * 100f / n, near * 100f / n))
        assertTrue("rendered page differs beyond resampling: ${near.toFloat() / n}", near.toFloat() / n > 0.999f)
    }

    @Test
    fun pageSizeSettingIsApplied() {
        val receipt = Bitmap.createBitmap(600, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val file = File(out, "receipt.jpg").also { f -> f.outputStream().use { receipt.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        val page = ExportPage(file, 600, 1800, PageFilter.ORIGINAL)
        for ((size, expected) in listOf(PageSize.A4 to (595 to 842), PageSize.LETTER to (612 to 792), PageSize.ORIGINAL to (281 to 842))) {
            val pdf = File(out, "receipt-$size.pdf")
            pdf.outputStream().use { PdfExporter.write(it, listOf(page), PdfCompression.MAXIMUM, "r", size) }
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r -> r.openPage(0).use { p ->
                    Log.i(TAG, "$size: ${p.width}x${p.height} pt")
                    assertEquals("$size width", expected.first.toFloat(), p.width.toFloat(), 1.5f)
                    assertEquals("$size height", expected.second.toFloat(), p.height.toFloat(), 1.5f)
                } }
            }
        }
    }

    @Test
    fun savedDocumentsAreFoundByTheirText(): Unit = runBlocking {
        SettingsRepository(app).setOcr(true)
        val repo = DocumentRepository.get(app)
        lateinit var vm: ScanSessionViewModel
        instr.runOnMainSync { vm = ScanSessionViewModel(app, SavedStateHandle()); vm.discard() }
        instr.runOnMainSync { vm.importImages(listOf(Uri.fromFile(asset("tilted.jpg")))) }
        val t0 = System.currentTimeMillis()
        while (vm.pages.value.size < 1 || vm.pages.value.any { it.processing }) { Thread.sleep(200); check(System.currentTimeMillis() - t0 < 120_000) }
        vm.awaitRecognized()
        val name = "Search test ${System.currentTimeMillis()}"
        val uri = vm.save(name, PdfCompression.BALANCED)

        val docs = repo.list()
        val texts = SearchIndex.get(app).sync(docs)
        val doc = docs.first { it.uri == uri }
        val pages = texts[uri.toString()].orEmpty()
        Log.i(TAG, "indexed ${pages.size} page(s), ${pages.sumOf { it.length }} chars")
        val m = TextSearch.match("invoice", doc.name, pages)
        assertNotNull("saved document not found by its text", m)
        assertEquals(0, m!!.page)
        assertTrue(TextSearch.match("zzzznotthere", doc.name, pages) == null)

        // Deleted documents leave the index.
        repo.delete(doc)
        assertTrue(uri.toString() !in SearchIndex.get(app).sync(repo.list()))
    }

    private companion object { const val TAG = "FeaturesTest" }
}
