package app.foglioscan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.foglioscan.imaging.PageFilter
import app.foglioscan.pdf.PageSize
import app.foglioscan.pdf.PdfCompression
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where photos come from. */
enum class CameraSource {
    /** FoglioScan's own camera: live page highlight while framing. */
    IN_APP,
    /** The phone's camera app: its own HDR / processing; page detected after the shot. */
    SYSTEM,
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    /** ARGB colour used to highlight the detected page in the viewfinder. */
    val highlightColor: Flow<Int> = store.data.map { it[HIGHLIGHT] ?: DEFAULT_HIGHLIGHT }

    val defaultFilter: Flow<PageFilter> = store.data.map { p ->
        p[FILTER]?.let { runCatching { PageFilter.valueOf(it) }.getOrNull() } ?: PageFilter.AUTO
    }

    val cameraSource: Flow<CameraSource> = store.data.map { p ->
        p[SOURCE]?.let { runCatching { CameraSource.valueOf(it) }.getOrNull() } ?: CameraSource.IN_APP
    }

    /** Use the manufacturer's HDR/Auto processing in the in-app camera when available. */
    val phoneHdr: Flow<Boolean> = store.data.map { it[HDR] ?: true }

    /** Last PDF quality chosen when saving. */
    val pdfCompression: Flow<PdfCompression> = store.data.map { p ->
        p[COMPRESSION]?.let { runCatching { PdfCompression.valueOf(it) }.getOrNull() } ?: PdfCompression.BALANCED
    }

    /** Recognize text on every page and make saved PDFs searchable. */
    val ocr: Flow<Boolean> = store.data.map { it[OCR] ?: true }

    /** Take the photo automatically once the page has been held steady for a moment. */
    val autoCapture: Flow<Boolean> = store.data.map { it[AUTO_CAPTURE] ?: true }

    /** Keep the camera open after each shot, to scan many pages in a row. */
    val batchMode: Flow<Boolean> = store.data.map { it[BATCH] ?: false }

    /** Book mode: every new photo is an open book, split into its two pages. */
    val bookMode: Flow<Boolean> = store.data.map { it[BOOK_MODE] ?: false }
    suspend fun setBookMode(enabled: Boolean) = store.edit { it[BOOK_MODE] = enabled }

    /** Physical size of saved PDF pages. */
    val pageSize: Flow<PageSize> = store.data.map { p ->
        p[PAGE_SIZE]?.let { runCatching { PageSize.valueOf(it) }.getOrNull() } ?: PageSize.AUTO
    }
    suspend fun setPageSize(size: PageSize) = store.edit { it[PAGE_SIZE] = size.name }

    /** Flatten curved pages (books, curled sheets) with the dewarping model. */
    val flattenCurved: Flow<Boolean> = store.data.map { it[FLATTEN] ?: true }
    suspend fun setFlattenCurved(enabled: Boolean) = store.edit { it[FLATTEN] = enabled }

    suspend fun setOcr(enabled: Boolean) = store.edit { it[OCR] = enabled }
    suspend fun setAutoCapture(enabled: Boolean) = store.edit { it[AUTO_CAPTURE] = enabled }
    suspend fun setBatchMode(enabled: Boolean) = store.edit { it[BATCH] = enabled }
    suspend fun setPdfCompression(c: PdfCompression) = store.edit { it[COMPRESSION] = c.name }
    suspend fun setCameraSource(source: CameraSource) = store.edit { it[SOURCE] = source.name }
    suspend fun setPhoneHdr(enabled: Boolean) = store.edit { it[HDR] = enabled }
    suspend fun setHighlightColor(argb: Int) = store.edit { it[HIGHLIGHT] = argb }
    suspend fun setDefaultFilter(filter: PageFilter) = store.edit { it[FILTER] = filter.name }

    companion object {
        const val DEFAULT_HIGHLIGHT = 0xFF00C2A8.toInt()
        private val HIGHLIGHT = intPreferencesKey("highlight_color")
        private val FILTER = stringPreferencesKey("default_filter")
        private val SOURCE = stringPreferencesKey("camera_source")
        private val HDR = booleanPreferencesKey("phone_hdr")
        private val COMPRESSION = stringPreferencesKey("pdf_compression")
        private val OCR = booleanPreferencesKey("ocr")
        private val AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        private val BATCH = booleanPreferencesKey("batch_mode")
        private val FLATTEN = booleanPreferencesKey("flatten_curved")
        private val PAGE_SIZE = stringPreferencesKey("page_size")
        private val BOOK_MODE = booleanPreferencesKey("book_mode")
    }
}
