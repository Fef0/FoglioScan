package app.foglioscan.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.foglioscan.R
import app.foglioscan.data.SettingsRepository
import app.foglioscan.session.ScanSessionViewModel
import app.foglioscan.ui.camera.CameraScreen
import app.foglioscan.ui.crop.CropScreen
import app.foglioscan.ui.home.HomeScreen
import app.foglioscan.ui.home.HomeViewModel
import app.foglioscan.ui.home.open
import app.foglioscan.ui.review.ReviewScreen
import app.foglioscan.vision.DocumentLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.foglioscan.data.CameraSource
import app.foglioscan.pdf.PdfCompression
import app.foglioscan.ui.settings.SettingsScreen
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable data object HomeKey : NavKey
@Serializable data object CameraKey : NavKey
@Serializable data object ReviewKey : NavKey
@Serializable data class CropKey(val pageId: String) : NavKey
@Serializable data object SettingsKey : NavKey

@Composable
fun FoglioApp(startInCamera: Boolean) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(HomeKey)
    val session: ScanSessionViewModel = viewModel()
    val home: HomeViewModel = viewModel()
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val homeSnackbar = remember { SnackbarHostState() }
    val reviewSnackbar = remember { SnackbarHostState() }

    val pages by session.pages.collectAsStateWithLifecycle()
    val highlight by settings.highlightColor.collectAsStateWithLifecycle(SettingsRepository.DEFAULT_HIGHLIGHT)
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val cameraSource by settings.cameraSource.collectAsStateWithLifecycle(CameraSource.IN_APP)
    val phoneHdr by settings.phoneHdr.collectAsStateWithLifecycle(true)
    val pdfCompression by settings.pdfCompression.collectAsStateWithLifecycle(PdfCompression.BALANCED)
    val ocrEnabled by settings.ocr.collectAsStateWithLifecycle(true)
    val autoCapture by settings.autoCapture.collectAsStateWithLifecycle(true)
    val batchMode by settings.batchMode.collectAsStateWithLifecycle(false)

    // --- Phone camera app (ACTION_IMAGE_CAPTURE) --------------------------------------------------
    // The target path survives process death: the camera app may run while we're killed.
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingCapture?.let(::File)
        pendingCapture = null
        if (ok && file != null && file.length() > 0) {
            session.addCapture(file, liveHint = null)
            showReview(backStack)
        } else {
            file?.delete()
        }
    }
    fun launchSystemCamera() {
        val file = session.newCaptureFile()
        pendingCapture = file.absolutePath
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        try {
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            // No camera app installed: fall back to ours.
            pendingCapture = null
            backStack.add(CameraKey)
        }
    }
    // Declaring CAMERA in the manifest means ACTION_IMAGE_CAPTURE also requires it to be granted.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchSystemCamera() else backStack.add(CameraKey) // in-app screen explains and offers import
    }
    fun startScan(source: CameraSource = cameraSource) {
        if (source == CameraSource.SYSTEM) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (granted) launchSystemCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            backStack.add(CameraKey)
        }
    }
    // --- Import from files (any camera setting) -------------------------------------------------
    // System file picker: Files, Downloads, Drive, gallery folders. Several images become pages,
    // in the order they were selected.
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            session.importImages(uris)
            showReview(backStack)
        }
    }
    fun importFromFiles() = importFiles.launch(arrayOf("image/*"))

    // Launcher shortcut "Scan".
    LaunchedEffect(Unit) {
        if (startInCamera) startScan(settings.cameraSource.first())
    }

    // Compile the detector in the background so the camera recognizes pages from the first frame.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { DocumentLocator.get(context).warmUp() }
    }

    // Leaving review with unsaved pages asks first; otherwise a plain pop.
    fun back() {
        when {
            backStack.lastOrNull() == ReviewKey && pages.isNotEmpty() -> confirmDiscard = true
            backStack.size > 1 -> backStack.removeAt(backStack.lastIndex)
        }
    }

    // All pages deleted from review → nothing left to review.
    LaunchedEffect(pages.isEmpty()) {
        if (pages.isEmpty() && backStack.lastOrNull() == ReviewKey) backStack.removeAt(backStack.lastIndex)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { back() },
        entryProvider = entryProvider {
            entry<HomeKey> {
                HomeScreen(
                    home, homeSnackbar,
                    onScan = { startScan() },
                    onImport = { importFromFiles() },
                    onSettings = { backStack.add(SettingsKey) },
                )
            }
            entry<CameraKey> {
                CameraScreen(
                    highlightColor = Color(highlight),
                    useHdr = phoneHdr,
                    pageCount = pages.size,
                    lastPage = rememberPageImage(pages.lastOrNull()?.previewFile, 200),
                    newCaptureFile = session::newCaptureFile,
                    onCaptured = { file, quad ->
                        session.addCapture(file, quad)
                        // Batch scanning keeps the camera open; the page stack opens review.
                        if (!batchMode) showReview(backStack)
                    },
                    onImport = { uris ->
                        session.importImages(uris)
                        showReview(backStack)
                    },
                    onHighlightColorChange = { c -> scope.launch { settings.setHighlightColor(c.toArgb()) } },
                    onDone = { showReview(backStack) },
                    onClose = { back() },
                    autoCapture = autoCapture,
                    onAutoCaptureChange = { on -> scope.launch { settings.setAutoCapture(on) } },
                    batchMode = batchMode,
                )
            }
            entry<ReviewKey> {
                ReviewScreen(
                    pages = pages,
                    snackbar = reviewSnackbar,
                    defaultCompression = pdfCompression,
                    estimateSizes = { session.estimateSizes() },
                    onRetry = session::retry,
                    onFilter = session::setFilter,
                    onRotate = session::rotate,
                    onCrop = { backStack.add(CropKey(it)) },
                    onDelete = session::delete,
                    onAddPage = { startScan() },
                    onImport = { importFromFiles() },
                    onSave = { name, compression, progress ->
                        scope.launch { settings.setPdfCompression(compression) }
                        runCatching { session.save(name, compression, progress) }
                            .onSuccess { uri ->
                                backStack.retainAll { it == HomeKey }
                                scope.launch {
                                    val r = homeSnackbar.showSnackbar(
                                        context.getString(R.string.saved_as, name),
                                        actionLabel = context.getString(R.string.open),
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (r == SnackbarResult.ActionPerformed) open(context, uri)
                                }
                            }
                            .onFailure { reviewSnackbar.showSnackbar(context.getString(R.string.save_failed)) }
                    },
                    onClose = { back() },
                    ocrEnabled = ocrEnabled,
                    documentText = { session.documentText() },
                )
            }
            entry<SettingsKey> {
                SettingsScreen(settings, onBack = { back() })
            }
            entry<CropKey> { key ->
                val page = pages.firstOrNull { it.spec.id == key.pageId }
                if (page == null) {
                    LaunchedEffect(Unit) { backStack.remove(key) }
                } else {
                    CropScreen(
                        sourcePath = page.spec.sourcePath,
                        initial = page.spec.quad,
                        highlightColor = Color(highlight),
                        onApply = { q -> session.setQuad(page.spec.id, q); backStack.remove(key) },
                        onCancel = { backStack.remove(key) },
                    )
                }
            }
        },
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    session.discard()
                    backStack.retainAll { it == HomeKey }
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_editing)) } },
        )
    }
}

/** Camera → Review: pop back to an existing review, or replace the camera with one. */
private fun showReview(backStack: MutableList<NavKey>) {
    if (backStack.lastOrNull() == CameraKey) backStack.removeAt(backStack.lastIndex)
    if (backStack.lastOrNull() != ReviewKey) backStack.add(ReviewKey)
}
