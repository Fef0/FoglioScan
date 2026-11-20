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
    }
}
