package app.foglioscan.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.math.roundToInt

data class SavedDocument(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)

/**
 * Saved PDFs live in the shared `Documents/FoglioScan` folder via MediaStore, so they are
 * visible to every file manager and need no storage permission (API 29+).
 */
class DocumentRepository private constructor(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val thumbnails = LruCache<String, Bitmap>(24)
    private val _version = MutableStateFlow(0)

    /** Increments whenever documents are added or removed. */
    val version: StateFlow<Int> = _version

    suspend fun savePdf(displayName: String, write: (OutputStream) -> Unit): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${sanitize(displayName)}.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, PDF)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.buffered().use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        _version.value++
        uri
    }

    suspend fun list(): List<SavedDocument> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
        val args = arrayOf("$RELATIVE_DIR%", PDF)
        buildList {
            resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val date = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    add(
                        SavedDocument(
                            uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                            name = c.getString(name).removeSuffix(".pdf"),
                            sizeBytes = c.getLong(size),
                            modifiedMillis = c.getLong(date) * 1000,
                        ),
                    )
                }
            }
        }
    }

    suspend fun delete(doc: SavedDocument) = withContext(Dispatchers.IO) {
        resolver.delete(doc.uri, null, null)
        _version.value++
    }

    /** First-page thumbnail and page count, rendered with the platform PdfRenderer. */
    suspend fun thumbnail(doc: SavedDocument, widthPx: Int): Pair<Bitmap, Int>? = withContext(Dispatchers.IO) {
        val key = "${doc.uri}@${doc.modifiedMillis}"
        runCatching {
            resolver.openFileDescriptor(doc.uri, "r")!!.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val cached = thumbnails.get(key)
                    if (cached != null) return@use cached to renderer.pageCount
                    renderer.openPage(0).use { page ->
                        val h = (widthPx * page.height.toFloat() / page.width).roundToInt()
                        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        thumbnails.put(key, bmp)
                        bmp to renderer.pageCount
                    }
                }
            }
        }.getOrNull()
    }

    private fun sanitize(name: String) =
        name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().ifEmpty { "Scan" }.take(120)

    companion object {
        private const val PDF = "application/pdf"
        val RELATIVE_DIR = "${Environment.DIRECTORY_DOCUMENTS}/FoglioScan/"

        @Volatile private var instance: DocumentRepository? = null
        fun get(context: Context): DocumentRepository =
            instance ?: synchronized(this) {
                instance ?: DocumentRepository(context.applicationContext).also { instance = it }
            }
    }
}
