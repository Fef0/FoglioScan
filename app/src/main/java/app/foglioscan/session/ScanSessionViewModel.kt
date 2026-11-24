package app.foglioscan.session

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.foglioscan.data.DocumentRepository
import app.foglioscan.data.SettingsRepository
import app.foglioscan.imaging.PageFilter
import app.foglioscan.imaging.PageProcessor
import app.foglioscan.ocr.PageOrientation
import app.foglioscan.ocr.PageText
import app.foglioscan.ocr.TextRecognizer
import app.foglioscan.pdf.ExportPage
import app.foglioscan.pdf.PdfCompression
import app.foglioscan.pdf.PdfExporter
import app.foglioscan.pdf.PdfImage
import app.foglioscan.pdf.PdfPageExtractor
import app.foglioscan.ui.PageImages
import app.foglioscan.vision.DocumentLocator
import app.foglioscan.vision.Quad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** What the user decided for a page; everything else is derived from it. */
@Serializable
data class PageSpec(
    val id: String,
    val sourcePath: String,
    val filter: PageFilter,
    val rotation: Int = 0,
    /** Outline actually used (detected or user-adjusted), normalized to the source photo. */
    val quad: Quad? = null,
    /** Outline seen in the live preview when the shutter was pressed. */
    val hint: Quad? = null,
    /**
     * Orientation already settled: checked from the recognized text, or set by the user.
     * The page is never turned automatically after that.
     */
    val oriented: Boolean = false,
    /** A finished page taken back from a saved PDF: used as is until the user changes it. */
    val fromPdf: Boolean = false,
)

/** The saved document being extended, if the session started from one. */
@Serializable
data class EditingDocument(val uri: String, val name: String)

/**
 * UI state of one page. Holds only file references: images are decoded on demand by the UI
 * (see PageImages), so the session's memory does not grow with its length.
 */
data class PageState(
    val spec: PageSpec,
    /** Screen-size rendering (JPEG). */
    val previewFile: File? = null,
    /** Full-resolution rendering (JPEG). */
    val output: File? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val processing: Boolean = true,
    val failed: Boolean = false,
    /** Recognized text of [output]; null until OCR has run (or when it is turned off). */
    val text: PageText? = null,
)

/**
 * The multipage document being assembled. Unlimited length: every page lives on disk, pages
 * render one at a time, and saving streams page by page. Survives configuration changes and
 * process death (specs are persisted in [SavedStateHandle]; renderings are recomputed).
 */
class ScanSessionViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    private val sessionDir = File(app.cacheDir, "session").apply { mkdirs() }
    private val processor = PageProcessor(DocumentLocator.get(app))
    private val documents = DocumentRepository.get(app)
    private val settings = SettingsRepository(app)

    /** One page renders at a time: full-resolution buffers are large. */
    private val renderGate = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    /** OCR runs after rendering, one page at a time, without blocking the next rendering. */
    private val ocrGate = Mutex()
    private val ocrJobs = mutableMapOf<String, Job>()
    /** Renderings OCR has run on (or failed on): never retried automatically. */
    private val ocrAttempted = mutableSetOf<String>()

    private val _pages = MutableStateFlow<List<PageState>>(emptyList())
    val pages: StateFlow<List<PageState>> = _pages.asStateFlow()

    private val _editing = MutableStateFlow<EditingDocument?>(null)
    /** Set when pages are being added to a saved document (saving can replace it). */
    val editing: StateFlow<EditingDocument?> = _editing.asStateFlow()

    private var openFailed = false

    /** True once after a document failed to open. */
    fun consumeOpenError(): Boolean = openFailed.also { openFailed = false }

    private val _opening = MutableStateFlow(false)
    /** True while a saved PDF's pages are being read. */
    val opening: StateFlow<Boolean> = _opening.asStateFlow()

    init {
        saved.get<String>(KEY_EDITING)?.let { json -> _editing.value = runCatching { Json.decodeFromString<EditingDocument>(json) }.getOrNull() }
        saved.get<String>(KEY_PAGES)?.let { json ->
            val specs = runCatching { Json.decodeFromString<List<PageSpec>>(json) }.getOrDefault(emptyList())
                .filter { File(it.sourcePath).exists() }
            _pages.value = specs.map { PageState(it) }
            specs.forEach(::render)
        }
    }

    /** A fresh file the camera can write the full-resolution photo into. */
    fun newCaptureFile(): File = File(sessionDir, "src-${UUID.randomUUID()}.jpg")

    fun addCapture(photo: File, liveHint: Quad?) {
        viewModelScope.launch {
            addSpec(PageSpec(UUID.randomUUID().toString(), photo.absolutePath, settings.defaultFilter.first(), hint = liveHint))
        }
    }

    fun importImage(uri: Uri) = importImages(listOf(uri))

    /** Adds images as pages, in the given order (copied one by one so the order is kept). */
    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val filter = settings.defaultFilter.first()
            for (uri in uris) {
                val file = newCaptureFile()
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        }
                    }.isSuccess
                }
                if (ok) addSpec(PageSpec(UUID.randomUUID().toString(), file.absolutePath, filter)) else file.delete()
            }
        }
    }

    /**
     * Starts a session from a saved PDF to add pages to it. FoglioScan's own PDFs give back their
     * page images byte for byte; other PDFs are rasterized at ≈300 dpi. Replaces the current
     * session.
     */
    fun openPdf(uri: Uri, name: String, ownDocument: Boolean = true) {
        discard()
        _editing.value = if (ownDocument) EditingDocument(uri.toString(), name) else null
        persist()
        _opening.value = true
        viewModelScope.launch {
            try {
                val copy = File(sessionDir, "open-${UUID.randomUUID()}.pdf")
                documents.copyTo(uri, copy)
                val specs = withContext(Dispatchers.IO) {
                    try { extractPages(copy) } finally { copy.delete() }
                }
                for (spec in specs) addSpec(spec)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Opening $uri failed", t)
                openFailed = true
                _editing.value = null
                persist()
            } finally {
                _opening.value = false
            }
        }
    }

    private fun extractPages(pdf: File): List<PageSpec> {
        fun spec(file: File, filter: PageFilter) = PageSpec(
            UUID.randomUUID().toString(), file.absolutePath, filter,
            quad = Quad.FULL, oriented = true, fromPdf = true,
        )
        PdfPageExtractor.open(pdf)?.use { x ->
            return (0 until x.pageCount).map { i ->
                when (val img = x.page(i)) {
                    is PdfImage.Jpeg -> spec(newCaptureFile().apply { writeBytes(img.bytes) }, PageFilter.ORIGINAL)
                    is PdfImage.Bilevel -> spec(
                        File(sessionDir, "src-${UUID.randomUUID()}.png").also { f -> PdfExporter.bilevelToPng(img, f) },
                        PageFilter.BLACK_WHITE,
                    )
                }
            }
        }
        // Not a FoglioScan PDF: rasterize every page at ≈300 dpi.
        return android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { r ->
                (0 until r.pageCount).map { i ->
                    r.openPage(i).use { page ->
                        val scale = RASTER_DPI / 72f
                        val bmp = android.graphics.Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        try {
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val f = newCaptureFile()
                            f.outputStream().buffered().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                            spec(f, PageFilter.ORIGINAL)
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        }
    }

    fun setFilter(id: String, filter: PageFilter) {
        viewModelScope.launch { settings.setDefaultFilter(filter) }
        updateSpec(id) { it.copy(filter = filter) }
    }

    fun rotate(id: String) = updateSpec(id) { it.copy(rotation = (it.rotation + 90) % 360, oriented = true) }

    fun setQuad(id: String, quad: Quad) = updateSpec(id) { it.copy(quad = quad) }

    /** Renders a page again (after a failure). */
    fun retry(id: String) = updateSpec(id) { it }

    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        ocrJobs.remove(id)?.cancel()
        val page = _pages.value.firstOrNull { it.spec.id == id } ?: return
        _pages.update { list -> list.filterNot { it.spec.id == id } }
        persist()
        deleteRendering(page.output, page.previewFile)
        File(page.spec.sourcePath).delete()
    }

    fun move(from: Int, to: Int) {
        _pages.update { list ->
            if (from !in list.indices || to !in list.indices) list
            else list.toMutableList().apply { add(to, removeAt(from)) }
        }
        persist()
    }

    /** Waits until every page is rendered. */
    suspend fun awaitRendered() {
        while (true) {
            // The jobs map is owned by the main thread.
            withContext(Dispatchers.Main.immediate) { jobs.values.toList() }.joinAll()
            if (_pages.value.none { it.processing }) return
        }
    }

    /**
     * Waits until every page's text is recognized, starting OCR for pages that don't have it
     * yet (e.g. OCR was just turned on).
     */
    suspend fun awaitRecognized() {
        while (true) {
            awaitRendered()
            // Reading a page can turn it upright, which renders it again: loop until settled.
            val pending = withContext(Dispatchers.Main.immediate) {
                _pages.value.forEach { p ->
                    val out = p.output
                    if (out != null && p.text == null && p.spec.id !in ocrJobs && out.path !in ocrAttempted) {
                        recognize(p.spec.id, out, p.widthPx, p.heightPx, force = true)
                    }
                }
                ocrJobs.values.toList() + jobs.values.toList()
            }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    /** The whole document's recognized text, pages separated by a blank line. */
    suspend fun documentText(): String {
        awaitRecognized()
        return _pages.value.mapNotNull { it.text?.plain?.takeIf(String::isNotBlank) }.joinToString("\n\n")
    }

    /**
     * Estimated PDF size per compression level, from real encodings of up to three sample pages
     * (first, middle, last) scaled to the whole document.
     */
    suspend fun estimateSizes(): Map<PdfCompression, Long> {
        awaitRendered()
        return withContext(Dispatchers.Default) { estimate(exportPages()) }
    }

    private fun estimate(pages: List<ExportPage>): Map<PdfCompression, Long> = run {
        if (pages.isEmpty()) return@run emptyMap()
        // Black & white pages compress an order of magnitude better than colour ones, so each
        // kind is sampled separately (up to 3 evenly spaced pages) and weighted by its count.
        val groups = pages.groupBy { it.filter == PageFilter.BLACK_WHITE }.values
        PdfCompression.entries.associateWith { level ->
            groups.sumOf { group ->
                val samples = listOf(0, group.size / 2, group.lastIndex).distinct().map { group[it] }
                val avg = samples.sumOf { PdfExporter.encodedSize(it, level) } / samples.size
                avg * group.size
            } + PDF_OVERHEAD_PER_PAGE * pages.size
        }
    }

    /**
     * Writes all pages to a PDF in Documents/FoglioScan and clears the session. With [replace]
     * and a document being extended ([editing]), that document is rewritten instead (or a new
     * one is saved if it can no longer be written).
     */
    suspend fun save(
        name: String,
        compression: PdfCompression = PdfCompression.MAXIMUM,
        onProgress: (Float) -> Unit = {},
        replace: Boolean = true,
    ): Uri {
        if (settings.ocr.first()) awaitRecognized() else awaitRendered()
        val pages = exportPages()
        check(pages.isNotEmpty() && pages.size == _pages.value.size) { "Pages not ready" }
        val write = { out: java.io.OutputStream ->
            PdfExporter.write(out, pages, compression, name) { done -> onProgress(done.toFloat() / pages.size) }
        }
        val target = _editing.value?.takeIf { replace }
        val uri = withContext(Dispatchers.Default) {
            if (target == null) documents.savePdf(name, write)
            else try {
                documents.replacePdf(Uri.parse(target.uri), name, write)
            } catch (e: SecurityException) {
                Log.w(TAG, "Can't rewrite ${target.uri}, saving a new document", e)
                documents.savePdf(name, write)
            }
        }
        withContext(Dispatchers.Main.immediate) { discard() }
        return uri
    }

    fun discard() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        ocrJobs.values.forEach { it.cancel() }
        ocrJobs.clear()
        ocrAttempted.clear()
        _pages.value = emptyList()
        _editing.value = null
        persist()
        PageImages.clear()
        sessionDir.listFiles()?.forEach { it.delete() }
    }

    // --- internals ------------------------------------------------------------------------------

    private fun exportPages() = _pages.value.mapNotNull { p ->
        p.output?.let { ExportPage(it, p.widthPx, p.heightPx, p.spec.filter, p.text) }
    }

    private fun addSpec(spec: PageSpec) {
        _pages.update { it + PageState(spec) }
        persist()
        render(spec)
    }

    private fun updateSpec(id: String, change: (PageSpec) -> PageSpec) {
        var updated: PageSpec? = null
        _pages.update { list ->
            list.map { p ->
                if (p.spec.id == id) {
                    val s = change(p.spec); updated = s
                    p.copy(spec = s, processing = true, failed = false, text = null)
                } else p
            }
        }
        persist()
        updated?.let(::render)
    }

    private fun render(spec: PageSpec) {
        jobs.remove(spec.id)?.cancel()
        ocrJobs.remove(spec.id)?.cancel()
        val job = viewModelScope.launch {
            val stamp = System.nanoTime()
            val out = File(sessionDir, "out-${spec.id}-$stamp.jpg")
            val preview = File(sessionDir, "prev-${spec.id}-$stamp.jpg")
            val result = renderGate.withLock {
                runCatching {
                    if (spec.fromPdf && spec.filter == PageFilter.ORIGINAL && spec.rotation == 0 && spec.sourcePath.endsWith(".jpg")) {
                        processor.passThrough(File(spec.sourcePath), out, preview)
                    } else {
                        processor.process(File(spec.sourcePath), out, preview, spec.filter, spec.rotation, spec.quad, spec.hint)
                    }
                }.recoverCatching { t ->
                    // Low memory: free what we can and try once more.
                    if (t !is OutOfMemoryError) throw t
                    Log.w(TAG, "Out of memory rendering ${spec.id}, retrying", t)
                    PageImages.clear()
                    System.gc()
                    processor.process(File(spec.sourcePath), out, preview, spec.filter, spec.rotation, spec.quad, spec.hint)
                }
            }
            result.onSuccess { r ->
                var old: PageState? = null
                _pages.update { list ->
                    list.map { p ->
                        if (p.spec.id != spec.id) p else {
                            old = p
                            p.copy(
                                spec = p.spec.copy(quad = p.spec.quad ?: r.quad),
                                previewFile = r.previewFile,
                                output = r.file, widthPx = r.widthPx, heightPx = r.heightPx,
                                processing = false, failed = false, text = null,
                            )
                        }
                    }
                }
                if (old == null) deleteRendering(r.file, r.previewFile) // page deleted meanwhile
                else {
                    deleteRendering(old?.output, old?.previewFile)
                    recognize(spec.id, r.file, r.widthPx, r.heightPx)
                }
                persist()
            }.onFailure { t ->
                deleteRendering(out, preview)
                if (t is CancellationException) throw t
                Log.e(TAG, "Rendering ${spec.id} failed", t)
                _pages.update { list -> list.map { if (it.spec.id == spec.id) it.copy(processing = false, failed = true) else it } }
            }
        }
        jobs[spec.id] = job
        job.invokeOnCompletion { if (jobs[spec.id] === job) jobs.remove(spec.id) }
    }

    /** Recognizes the text of rendering [file] of page [id] in the background (if OCR is on, or [force]). */
    private fun recognize(id: String, file: File, widthPx: Int, heightPx: Int, force: Boolean = false) {
        ocrJobs.remove(id)?.cancel()
        val job = viewModelScope.launch {
            if (!force && !settings.ocr.first()) return@launch
            ocrAttempted += file.path
            val text = ocrGate.withLock {
                try {
                    TextRecognizer.recognize(file, widthPx, heightPx)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "OCR of $id failed", t)
                    null
                }
            } ?: return@launch
            val page = _pages.value.firstOrNull { it.spec.id == id && it.output == file } ?: return@launch
            if (!page.spec.oriented) {
                // First reading of this page: turn it upright if the text says it's sideways or
                // upside down (re-renders, then reads again).
                val fix = PageOrientation.correction(text)
                if (fix != 0) {
                    Log.i(TAG, "Page $id: text is rotated, turning it by $fix°")
                    updateSpec(id) { it.copy(rotation = (it.rotation + fix) % 360, oriented = true) }
                    return@launch
                }
            }
            _pages.update { list ->
                list.map { if (it.spec.id == id && it.output == file) it.copy(spec = it.spec.copy(oriented = true), text = text) else it }
            }
            persist()
        }
        ocrJobs[id] = job
        job.invokeOnCompletion { if (ocrJobs[id] === job) ocrJobs.remove(id) }
    }

    private fun deleteRendering(vararg files: File?) {
        files.filterNotNull().forEach { PageImages.evict(it); it.delete() }
    }

    private fun persist() {
        saved[KEY_PAGES] = Json.encodeToString(_pages.value.map { it.spec })
        saved[KEY_EDITING] = _editing.value?.let { Json.encodeToString(it) }
    }

    private companion object {
        const val TAG = "ScanSession"
        const val KEY_PAGES = "pages"
        const val KEY_EDITING = "editing"
        const val RASTER_DPI = 300f
        const val PDF_OVERHEAD_PER_PAGE = 600L
    }
}
