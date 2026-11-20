package app.foglioscan

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.foglioscan.imaging.PageFilter
import app.foglioscan.imaging.PageProcessor
import app.foglioscan.pdf.ExportPage
import app.foglioscan.pdf.PdfCompression
import app.foglioscan.pdf.PdfExporter
import app.foglioscan.vision.DocumentLocator
import app.foglioscan.vision.Point2
import app.foglioscan.vision.Quad
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end check of the real pipeline on a device: neural page detection,
 * perspective correction, every filter, and PDF output.
 * Results are written to the app's external files dir (pull with adb) for visual review.
 */
@RunWith(AndroidJUnit4::class)
class PipelineTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext
    private val out = File(app.getExternalFilesDir(null), "pipeline-test").apply { deleteRecursively(); mkdirs() }

    /** Ground-truth corners of the generated photos (3000×4000), see tools/make_test_photos.py. */
    private val truth = mapOf(
        "tilted" to listOf(520f to 610f, 2480f to 760f, 2620f to 3520f, 330f to 3330f),
        "upright" to listOf(780f to 420f, 2350f to 520f, 2500f to 3700f, 600f to 3560f),
        // A4 sheet tilted in 3D and projected by a pinhole camera (physically valid).
        "projected" to listOf(648f to 392f, 2825f to 798f, 2052f to 3040f, 439f to 2963f),
    )

    private fun asset(name: String): File = File(app.cacheDir, name).also { f ->
        instr.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
    }

    @Test
    fun detectsCornersPrecisely() {
        val locator = DocumentLocator.get(app)
        for ((name, gt) in truth) {
            val photo = PageProcessor.decode(asset("$name.jpg"), 4096)
            val t0 = System.nanoTime()
            val quad = locator.locatePrecise(photo)
            val ms = (System.nanoTime() - t0) / 1e6
            assertNotNull("$name: no document found", quad)
            val errors = quad!!.corners.zip(gt).map { (p, g) ->
                Point2(p.x * photo.width, p.y * photo.height).distanceTo(Point2(g.first, g.second))
            }
            Log.i(TAG, "$name neural=${locator.usesNeuralNetwork} corner errors px=${errors.map { it.toInt() }} in ${ms.toInt()} ms")
            // ≤ 0.6% of the long side (24 px at 4000 px).
            assertTrue("$name: corner error too large $errors", errors.all { it < 24f })
        }
    }

    @Test
    fun liveDetectionIsFast() {
        val locator = DocumentLocator.get(app)
        val frame = PageProcessor.decode(asset("tilted.jpg"), 640)
        locator.locateLive(frame, 0) // warm-up (GPU compile)
        val t0 = System.nanoTime()
        repeat(10) { assertNotNull(locator.locateLive(frame, 0)) }
        val ms = (System.nanoTime() - t0) / 1e6 / 10
        Log.i(TAG, "live detection avg ${"%.1f".format(ms)} ms/frame")
    }

    @Test
    fun producesScansAndPdf(): Unit = runBlocking {
        val processor = PageProcessor(DocumentLocator.get(app))
        val pages = ArrayList<ExportPage>()
        for (name in truth.keys) for (filter in PageFilter.entries) {
            val t0 = System.nanoTime()
            val r = processor.process(asset("$name.jpg"), File(out, "$name-${filter.name.lowercase()}.jpg"), File(out, "$name-${filter.name.lowercase()}-preview.jpg"), filter, 0, null, null)
            Log.i(TAG, "$name $filter -> ${r.widthPx}x${r.heightPx}, ${r.file.length() / 1024} KB in ${(System.nanoTime() - t0) / 1_000_000} ms")
            if (filter == PageFilter.AUTO) pages += ExportPage(r.file, r.widthPx, r.heightPx, filter)
            if (name == "projected") {
                val aspect = r.heightPx.toFloat() / r.widthPx
                assertTrue("projected A4 should come out as A4, got $aspect", kotlin.math.abs(aspect - 1.4142f) < 0.015f)
            }
        }
        val pdf = File(out, "scan.pdf")
        pdf.outputStream().use { PdfExporter.write(it, pages, PdfCompression.MAXIMUM, "Pipeline test") }
        android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use {
            assertTrue(it.pageCount == pages.size)
        }
        Log.i(TAG, "PDF ${pdf.length() / 1024} KB, ${pages.size} pages")

        // Visual check of the compression levels: render page 1 of each PDF to PNG.
        for (level in PdfCompression.entries) {
            val f = File(out, "scan-${level.name.lowercase()}.pdf")
            f.outputStream().use { PdfExporter.write(it, pages, level, "Level $level") }
            android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                r.openPage(0).use { pg ->
                    val bmp = android.graphics.Bitmap.createBitmap(pg.width * 3, pg.height * 3, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    File(out, "render-${level.name.lowercase()}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
            }
            Log.i(TAG, "level $level: ${f.length() / 1024} KB for ${pages.size} pages")
        }
    }

    @Test
    fun quadOrdering() {
        val q = Quad.fromPoints(listOf(Point2(1f, 1f), Point2(0f, 0f), Point2(0f, 1f), Point2(1f, 0f)))
        assertTrue(q == Quad.FULL)
        assertTrue(Quad.FULL.rotatedClockwise(4) == Quad.FULL)
    }

    private companion object { const val TAG = "PipelineTest" }
}
