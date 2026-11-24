package app.foglioscan

import android.app.Application
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.foglioscan.data.DocumentRepository
import app.foglioscan.data.SettingsRepository
import app.foglioscan.imaging.PageFilter
import app.foglioscan.pdf.PdfCompression
import app.foglioscan.pdf.PdfImage
import app.foglioscan.pdf.PdfPageExtractor
import app.foglioscan.session.ScanSessionViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Reopening a saved PDF to add pages: lossless page reuse, replacing the original, foreign PDFs. */
@RunWith(AndroidJUnit4::class)
class ReopenDocumentTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext.applicationContext as Application
    private val repo = DocumentRepository.get(app)

    private fun photo(name: String): Uri {
        val f = File(app.cacheDir, "reopen-$name")
        instr.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(f)
    }

    private fun newSession(): ScanSessionViewModel {
        lateinit var vm: ScanSessionViewModel
        instr.runOnMainSync { vm = ScanSessionViewModel(app, SavedStateHandle()); vm.discard() }
        return vm
    }

    private fun ScanSessionViewModel.waitPages(n: Int) {
        val t0 = System.currentTimeMillis()
        while (opening.value || pages.value.size < n || pages.value.any { it.processing }) {
            Thread.sleep(200); check(System.currentTimeMillis() - t0 < 120_000) { "timeout, ${pages.value.size} pages" }
        }
    }

    private fun embedded(uri: Uri): List<PdfImage> {
        val f = File(app.cacheDir, "reopen-check.pdf")
        runBlocking { repo.copyTo(uri, f) }
        return PdfPageExtractor.open(f)!!.use { x -> (0 until x.pageCount).map { x.page(it) } }
    }

    private fun text(uri: Uri): List<String> {
        if (Build.VERSION.SDK_INT < 35) return emptyList()
        return app.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
            PdfRenderer(fd).use { r -> (0 until r.pageCount).map { i -> r.openPage(i).use { p -> p.textContents.joinToString("\n") { it.text } } } }
        }
    }

    @Test
    fun addPagesToASavedDocumentWithoutLosingQuality(): Unit = runBlocking {
        SettingsRepository(app).setDefaultFilter(PageFilter.AUTO)
        SettingsRepository(app).setOcr(true)
        val name = "Reopen test ${System.currentTimeMillis()}"

        // 1. A 2-page document, page 2 black & white, saved at Maximum.
        val vm = newSession()
        instr.runOnMainSync { vm.importImages(listOf(photo("tilted.jpg"), photo("upright.jpg"))) }
        vm.waitPages(2)
        instr.runOnMainSync { vm.setFilter(vm.pages.value[1].spec.id, PageFilter.BLACK_WHITE) }
        vm.waitPages(2)
        val uri = vm.save(name, PdfCompression.MAXIMUM)
        val v1 = embedded(uri)
        assertEquals(2, v1.size)

        // 2. Reopen: pages come back as they were saved.
        instr.runOnMainSync { vm.openPdf(uri, name) }
        vm.waitPages(2)
        assertEquals(name, vm.editing.value?.name)
        val reopened = vm.pages.value
        assertTrue(reopened.all { it.spec.fromPdf })
        assertEquals(PageFilter.ORIGINAL, reopened[0].spec.filter)
        assertArrayEquals("JPEG page reused byte for byte", (v1[0] as PdfImage.Jpeg).bytes, reopened[0].output!!.readBytes())
        assertEquals(v1[0].width, reopened[0].widthPx)

        // 3. Add a page and save over the original, at Maximum again.
        instr.runOnMainSync { vm.importImages(listOf(photo("projected.jpg"))) }
        vm.waitPages(3)
        val uri2 = vm.save(name, PdfCompression.MAXIMUM, replace = true)
        assertEquals("same document", uri, uri2)
        assertEquals("only one document with this name", 1, repo.list().count { it.name == name })
        val v2 = embedded(uri)
        assertEquals(3, v2.size)
        assertArrayEquals("no generational loss on the first page", (v1[0] as PdfImage.Jpeg).bytes, (v2[0] as PdfImage.Jpeg).bytes)
        val texts = text(uri)
        if (texts.isNotEmpty()) texts.forEachIndexed { i, t -> assertTrue("page $i searchable: ${t.take(60)}", t.contains("Invoice")) }
        Log.i(TAG, "v1 ${v1.map { it::class.simpleName }}, v2 ${v2.size} pages, searchable ${texts.map { it.contains("Invoice") }}")

        // 4. Save a copy instead: the original stays untouched.
        instr.runOnMainSync { vm.openPdf(uri, name) }
        vm.waitPages(3)
        instr.runOnMainSync { vm.delete(vm.pages.value[2].spec.id) }
        val copyUri = vm.save("$name copy", PdfCompression.SMALL, replace = false)
        assertTrue(copyUri != uri)
        assertEquals(3, embedded(uri).size)
        assertEquals(2, app.contentResolver.openFileDescriptor(copyUri, "r")!!.use { PdfRenderer(it).use { r -> r.pageCount } })

        repo.list().filter { it.uri == uri || it.uri == copyUri }.forEach { repo.delete(it) }
    }

    @Test
    fun otherPdfsAreRasterizedAndSavedAsNew(): Unit = runBlocking {
        // A text PDF made by another app.
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawText("Contratto di locazione 2026", 72f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f })
        doc.finishPage(page)
        val f = File(app.cacheDir, "foreign.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()

        val vm = newSession()
        instr.runOnMainSync { vm.openPdf(Uri.fromFile(f), "foreign", ownDocument = false) }
        vm.waitPages(1)
        assertNull(vm.editing.value)
        val p = vm.pages.value.single()
        // ≈300 dpi raster of an A4 page.
        assertEquals(2479f, p.widthPx.toFloat(), 3f)
        vm.awaitRecognized()
        assertTrue(vm.pages.value.single().text!!.plain, vm.pages.value.single().text!!.plain.contains("Contratto"))
        instr.runOnMainSync { vm.discard() }
    }

    private companion object { const val TAG = "ReopenTest" }
}
