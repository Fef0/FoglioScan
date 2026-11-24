package app.foglioscan.ui.review

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.foglioscan.R
import app.foglioscan.imaging.PageFilter
import app.foglioscan.pdf.PdfCompression
import app.foglioscan.session.PageState
import app.foglioscan.ui.rememberPageImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Long side of the on-screen page rendering. */
private const val PREVIEW_SIDE = 1600
private const val THUMB_SIDE = 200

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    pages: List<PageState>,
    snackbar: SnackbarHostState,
    defaultCompression: PdfCompression,
    estimateSizes: suspend () -> Map<PdfCompression, Long>,
    onFilter: (String, PageFilter) -> Unit,
    onRotate: (String) -> Unit,
    onCrop: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRetry: (String) -> Unit,
    onAddPage: () -> Unit,
    onImport: () -> Unit,
    onSave: suspend (name: String, compression: PdfCompression, replace: Boolean, progress: (Float) -> Unit) -> Unit,
    onClose: () -> Unit,
    ocrEnabled: Boolean = true,
    documentText: suspend () -> String = { "" },
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    /** Name of the saved document being extended, if any. */
    editingName: String? = null,
    /** A saved document's pages are being read. */
    opening: Boolean = false,
) {
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    var askName by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveProgress by remember { mutableFloatStateOf(0f) }
    var zoomed by remember { mutableStateOf(false) }
    var showText by rememberSaveable { mutableStateOf(false) }

    // Jump to a newly added page.
    var lastCount by remember { mutableStateOf(pages.size) }
    LaunchedEffect(pages.size) {
        if (pages.size > lastCount) pager.animateScrollToPage(pages.lastIndex)
        lastCount = pages.size
    }
    val current = pages.getOrNull(pager.currentPage.coerceAtMost(pages.lastIndex.coerceAtLeast(0)))

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (pages.size <= 1) stringResource(R.string.review_title_single)
                        else stringResource(R.string.page_x_of_y, pager.currentPage + 1, pages.size),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.discard)) }
                },
                actions = {
                    if (current != null) {
                        IconButton(onClick = { onCrop(current.spec.id) }) {
                            Icon(painterResource(R.drawable.ic_crop), stringResource(R.string.adjust_corners))
                        }
                        IconButton(onClick = { showText = true }, modifier = Modifier.testTag("text")) {
                            Icon(painterResource(R.drawable.ic_notes), stringResource(R.string.recognized_text))
                        }
                        IconButton(onClick = { onRotate(current.spec.id) }) {
                            Icon(painterResource(R.drawable.ic_rotate_right), stringResource(R.string.rotate))
                        }
                        IconButton(onClick = { onDelete(current.spec.id) }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete_page))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                    if (current != null) {
                        FilterRow(current.spec.filter) { onFilter(current.spec.id, it) }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalIconButton(
                            onClick = onImport,
                            enabled = !saving,
                            modifier = Modifier.size(56.dp).testTag("review-import"),
                        ) {
                            Icon(painterResource(R.drawable.ic_upload_file), stringResource(R.string.import_file))
                        }
                        OutlinedButton(
                            onClick = onAddPage,
                            modifier = Modifier.weight(1f).height(56.dp),
                            enabled = !saving,
                        ) {
                            Icon(painterResource(R.drawable.ic_add), null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_page))
                        }
                        Button(
                            onClick = { askName = true },
                            modifier = Modifier.weight(1f).height(56.dp).testTag("save"),
                            enabled = !saving && pages.isNotEmpty() && pages.none { it.failed },
                        ) {
                            if (saving) {
                                CircularProgressIndicator(
                                    progress = { saveProgress }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("${(saveProgress * pages.size).toInt()}/${pages.size}")
                            } else {
                                Icon(painterResource(R.drawable.ic_check), null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.save_pdf))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (opening && pages.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.opening_document), style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                userScrollEnabled = !zoomed,
                key = { pages.getOrNull(it)?.spec?.id ?: it },
            ) { index ->
                pages.getOrNull(index)?.let {
                    PageCard(
                        page = it,
                        isCurrentPage = index == pager.currentPage,
                        onZoomedChange = { z -> zoomed = z },
                        onRetry = { onRetry(it.spec.id) },
                    )
                }
            }
            if (pages.size > 1) {
                Thumbnails(
                    pages, pager.currentPage,
                    onClick = { scope.launch { pager.animateScrollToPage(it) } },
                    onMove = { from, to ->
                        onMove(from, to)
                        // Keep showing the same page (moves are between neighbours).
                        when (pager.currentPage) {
                            from -> scope.launch { pager.scrollToPage(to) }
                            to -> scope.launch { pager.scrollToPage(from) }
                        }
                    },
                )
            }
        }
    }

    if (showText && current != null) {
        TextSheet(
            page = current,
            ocrEnabled = ocrEnabled,
            documentText = documentText,
            snackbar = snackbar,
            onDismiss = { showText = false },
        )
    }

    if (askName) {
        SaveDialog(
            pageCount = pages.size,
            defaultCompression = defaultCompression,
            estimateSizes = estimateSizes,
            onDismiss = { askName = false },
            editingName = editingName,
            onConfirm = { name, compression, replace ->
                askName = false
                saving = true
                saveProgress = 0f
                scope.launch {
                    try { onSave(name, compression, replace) { saveProgress = it } } finally { saving = false }
                }
            },
        )
    }
}

@Composable
private fun PageCard(page: PageState, isCurrentPage: Boolean, onZoomedChange: (Boolean) -> Unit, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val hasShape = page.widthPx > 0 && page.heightPx > 0
        when {
            hasShape -> {
                // Keeps showing the previous image while a new rendering decodes (no flash).
                val preview = rememberPageImage(page.previewFile, PREVIEW_SIDE)
                // New page shape (rotate / crop) → fresh zoom state; filter changes keep the zoom.
                key(page.widthPx, page.heightPx) {
                    ZoomablePage(
                        aspectRatio = page.widthPx.toFloat() / page.heightPx,
                        preview = preview,
                        fullRes = page.output.takeIf { !page.processing },
                        isCurrentPage = isCurrentPage,
                        onZoomedChange = onZoomedChange,
                        pagePadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) { img ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .shadow(8.dp, RoundedCornerShape(4.dp))
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White),
                        ) {
                            img?.let { Image(it, stringResource(R.string.scanned_page), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        }
                    }
                }
                // Small badge instead of an overlay: never covers or fights with the zoomed page.
                if (page.processing) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.enhancing), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            page.failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.processing_failed), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.enhancing), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun FilterRow(selected: PageFilter, onSelect: (PageFilter) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PageFilter.entries.forEach { f ->
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text(stringResource(f.label)) },
                leadingIcon = { Icon(painterResource(f.icon), null, Modifier.size(18.dp)) },
            )
        }
    }
}

private val PageFilter.label
    get() = when (this) {
        PageFilter.AUTO -> R.string.filter_auto
        PageFilter.GRAYSCALE -> R.string.filter_gray
        PageFilter.BLACK_WHITE -> R.string.filter_bw
        PageFilter.ORIGINAL -> R.string.filter_original
    }

private val PageFilter.icon
    get() = when (this) {
        PageFilter.AUTO -> R.drawable.ic_auto_awesome
        PageFilter.GRAYSCALE -> R.drawable.ic_filter_b_and_w
        PageFilter.BLACK_WHITE -> R.drawable.ic_description
        PageFilter.ORIGINAL -> R.drawable.ic_photo_library
    }

/**
 * Page strip. Tap a page to show it; long-press and drag to reorder (with "move left / right"
 * accessibility actions for screen readers).
 */
@Composable
private fun Thumbnails(pages: List<PageState>, current: Int, onClick: (Int) -> Unit, onMove: (Int, Int) -> Unit) {
    val state = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val step = with(density) { (THUMB_WIDTH + THUMB_SPACING).toPx() }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentPages by rememberUpdatedState(pages)
    val currentOnMove by rememberUpdatedState(onMove)
    val moveLeft = stringResource(R.string.move_left)
    val moveRight = stringResource(R.string.move_right)

    LaunchedEffect(current) { if (draggedId == null) state.animateScrollToItem(current.coerceAtLeast(0)) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(THUMB_SPACING),
        modifier = Modifier.testTag("thumbnails"),
    ) {
        itemsIndexed(pages, key = { _, p -> p.spec.id }) { i, p ->
            val dragging = p.spec.id == draggedId
            val border = when {
                dragging -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
                i == current -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            }
            val thumb = rememberPageImage(p.previewFile, THUMB_SIDE)
            Box(
                (if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                    .graphicsLayer {
                        if (dragging) { translationX = dragOffset; scaleX = 1.12f; scaleY = 1.12f; shadowElevation = 8.dp.toPx() }
                    }
                    .size(width = THUMB_WIDTH, height = 60.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(border, RoundedCornerShape(6.dp))
                    .background(Color.White)
                    .testTag("thumb-$i")
                    .pointerInput(p.spec.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedId = p.spec.id; dragOffset = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.x
                                val list = currentPages
                                val idx = list.indexOfFirst { it.spec.id == draggedId }
                                // Swap with the neighbour once the page is dragged past its middle.
                                if (dragOffset > step / 2 && idx in 0 until list.lastIndex) {
                                    currentOnMove(idx, idx + 1); dragOffset -= step
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                } else if (dragOffset < -step / 2 && idx > 0) {
                                    currentOnMove(idx, idx - 1); dragOffset += step
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                            },
                            onDragEnd = { draggedId = null; dragOffset = 0f },
                            onDragCancel = { draggedId = null; dragOffset = 0f },
                        )
                    }
                    .semantics {
                        customActions = buildList {
                            if (i > 0) add(CustomAccessibilityAction(moveLeft) { onMove(i, i - 1); true })
                            if (i < pages.lastIndex) add(CustomAccessibilityAction(moveRight) { onMove(i, i + 1); true })
                        }
                    }
                    .clickable { onClick(i) },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    thumb != null -> Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    p.failed -> Icon(painterResource(R.drawable.ic_close), null, tint = MaterialTheme.colorScheme.error)
                    else -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

private val THUMB_WIDTH = 44.dp
private val THUMB_SPACING = 8.dp

@Composable
private fun SaveDialog(
    pageCount: Int,
    defaultCompression: PdfCompression,
    estimateSizes: suspend () -> Map<PdfCompression, Long>,
    onDismiss: () -> Unit,
    editingName: String?,
    onConfirm: (String, PdfCompression, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val default = remember {
        editingName ?: ("Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date()))
    }
    var replace by rememberSaveable { mutableStateOf(editingName != null) }
    var nameField by remember { mutableStateOf(TextFieldValue(default, TextRange(0, default.length))) }
    var compression by rememberSaveable { mutableStateOf(defaultCompression) }
    val estimates by produceState<Map<PdfCompression, Long>?>(null) { value = runCatching { estimateSizes() }.getOrNull() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val confirm = { onConfirm(nameField.text.trim().ifEmpty { default }, compression, replace) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_description), null) },
        title = { Text(stringResource(R.string.save_pdf)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    label = { Text(stringResource(R.string.file_name)) },
                    singleLine = true,
                    suffix = { Text(".pdf") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (editingName != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = replace, role = Role.Checkbox) { replace = it }
                            .testTag("replace-original")
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = replace, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.replace_original, editingName), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(if (replace) R.string.replace_original_on else R.string.replace_original_off),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.pdf_quality) + " · " +
                        context.resources.getQuantityString(R.plurals.pages, pageCount, pageCount),
                    style = MaterialTheme.typography.titleSmall,
                )
                Column(Modifier.selectableGroup()) {
                    PdfCompression.entries.forEach { level ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = level == compression, role = Role.RadioButton) { compression = level }
                                .testTag("quality-${level.name}")
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = level == compression, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(level.title), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(level.description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                estimates?.get(level)?.let { "≈ " + Formatter.formatShortFileSize(context, it) } ?: "…",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = confirm, modifier = Modifier.testTag("save-confirm")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val PdfCompression.title
    get() = when (this) {
        PdfCompression.MAXIMUM -> R.string.quality_maximum
        PdfCompression.BALANCED -> R.string.quality_balanced
        PdfCompression.SMALL -> R.string.quality_small
        PdfCompression.SMALLEST -> R.string.quality_smallest
    }

private val PdfCompression.description
    get() = when (this) {
        PdfCompression.MAXIMUM -> R.string.quality_maximum_desc
        PdfCompression.BALANCED -> R.string.quality_balanced_desc
        PdfCompression.SMALL -> R.string.quality_small_desc
        PdfCompression.SMALLEST -> R.string.quality_smallest_desc
    }
