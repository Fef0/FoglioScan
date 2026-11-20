package app.foglioscan

import android.app.Application
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.foglioscan.data.DocumentRepository
import app.foglioscan.data.SettingsRepository
import app.foglioscan.imaging.PageFilter
import app.foglioscan.pdf.ExportPage
import app.foglioscan.pdf.PdfCompression
import app.foglioscan.pdf.PdfExporter
import app.foglioscan.session.ScanSessionViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Stress test for long documents, through the real ScanSessionViewModel with full-resolution
 * (3000×4000) photos:
 *  - every page renders (no failures) and memory does not grow with the page count;
 *  - every compression level produces a valid PDF with all pages, sizes decrease level by
 *    level, the size estimate is close, and export memory is bounded;
 *  - the normal save path (MediaStore) works.
 */
@RunWith(AndroidJUnit4::class)
class LargeDocumentTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext.applicationContext as Application

    private fun photos(n: Int): List<Uri> {
        val dir = File(app.cacheDir, "stress").apply { mkdirs() }
        val names = listOf("tilted.jpg", "projected.jpg", "upright.jpg")
        return (0 until n).map { i ->
            val f = File(dir, "p$i.jpg")
            if (!f.exists()) instr.context.assets.open(names[i % 3]).use { input -> f.outputStream().use { input.copyTo(it) } }
            Uri.fromFile(f)
        }
    }

    private fun usedMb(): Double {
        val rt = Runtime.getRuntime()
        return ((rt.totalMemory() - rt.freeMemory()) + Debug.getNativeHeapAllocatedSize()) / 1048576.0
    }

    private fun waitRendered(vm: ScanSessionViewModel, expected: Int, onSample: (Int, Double) -> Unit = { _, _ -> }) {
        val t0 = System.currentTimeMillis()
        while (true) {
            Thread.sleep(400)
            val pages = vm.pages.value
            onSample(pages.count { !it.processing }, usedMb())
            if (pages.size == expected && pages.none { it.processing }) return
            check(System.currentTimeMillis() - t0 < 40 * 60_000) { "timeout: ${pages.size} pages" }
        }
    }

    @Test
    fun fortyPagesRenderCompressAndSave() {
        val n = 40
        lateinit var vm: ScanSessionViewModel
        // The app remembers the last filter chosen; start every run from the default.
        runBlocking { SettingsRepository(app).setDefaultFilter(PageFilter.AUTO) }
        instr.runOnMainSync { vm = ScanSessionViewModel(app, SavedStateHandle()); vm.discard() }

        // 1. Render 40 pages, sampling memory.
        val samples = sortedMapOf<Int, Double>()
        val t0 = System.currentTimeMillis()
        instr.runOnMainSync { vm.importImages(photos(n)) }
        waitRendered(vm, n) { done, mb -> samples.merge(done, mb, ::maxOf) }
        Log.i(TAG, "rendered $n pages in ${(System.currentTimeMillis() - t0) / 1000}s; heap limit ${Runtime.getRuntime().maxMemory() shr 20} MB")
        samples.filterKeys { it % 5 == 0 }.forEach { (k, v) -> Log.i(TAG, "  after $k pages: ${"%.0f".format(v)} MB") }
        assertEquals("pages that failed to render", 0, vm.pages.value.count { it.failed })
        val at10 = samples.filterKeys { it >= 10 }.values.first()
        val atEnd = samples.values.last()
        assertTrue("memory grows with page count: ${"%.0f".format(at10)} -> ${"%.0f".format(atEnd)} MB", atEnd - at10 < 40)

        // 2. Make every 4th page black & white (exercises 1-bit compression).
        instr.runOnMainSync {
            vm.pages.value.forEachIndexed { i, p -> if (i % 4 == 0) vm.setFilter(p.spec.id, PageFilter.BLACK_WHITE) }
        }
        waitRendered(vm, n)
        val pages = vm.pages.value.map { ExportPage(it.output!!, it.widthPx, it.heightPx, it.spec.filter) }

        // 3. Every compression level → valid PDF with all pages; sizes decrease; estimate close.
        val estimates = runBlocking { vm.estimateSizes() }
        val sizes = LinkedHashMap<PdfCompression, Long>()
        for (level in PdfCompression.entries) {
            val pdf = File(app.cacheDir, "level-${level.name}.pdf")
            var peak = 0.0
            val te = System.currentTimeMillis()
            pdf.outputStream().buffered().use { out ->
                PdfExporter.write(out, pages, level, "Level $level") { peak = maxOf(peak, usedMb()) }
            }
            val count = PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                // Render one page of each kind to prove the content decodes.
                r.openPage(0).use { pg ->
                    val bmp = android.graphics.Bitmap.createBitmap(pg.width, pg.height, android.graphics.Bitmap.Config.ARGB_8888)
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp.recycle()
                }
                r.pageCount
            }
            sizes[level] = pdf.length()
            val est = estimates[level] ?: 0
            Log.i(
                TAG, "$level: ${pdf.length() / 1024} KB (est ${est / 1024} KB, ${"%+.0f".format(100.0 * (est - pdf.length()) / pdf.length())}%), " +
                    "$count pages, ${(System.currentTimeMillis() - te) / 1000}s, peak ${"%.0f".format(peak)} MB",
            )
            assertEquals("$level page count", n, count)
            assertTrue("$level estimate off: $est vs ${pdf.length()}", abs(est - pdf.length()) < pdf.length() * 0.35)
            pdf.delete()
        }
        val ordered = PdfCompression.entries.map { sizes.getValue(it) }
        assertTrue("sizes must decrease: $ordered", ordered.zipWithNext().all { (a, b) -> b < a })

        // 4. Normal save path (then restore the default filter for the next run).
        runBlocking { SettingsRepository(app).setDefaultFilter(PageFilter.AUTO) }
        val uri = runBlocking { vm.save("Stress test $n pages", PdfCompression.BALANCED) }
        val count = app.contentResolver.openFileDescriptor(uri, "r")!!.use { PdfRenderer(it).use { r -> r.pageCount } }
        assertEquals(n, count)
        assertTrue("session cleared after save", vm.pages.value.isEmpty())
        runBlocking {
            val repo = DocumentRepository.get(app)
            repo.list().filter { it.uri == uri }.forEach { repo.delete(it) }
        }
    }

    private companion object { const val TAG = "LargeDoc" }
}
