package app.foglioscan.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foglioscan.R
import app.foglioscan.data.DocumentRepository
import app.foglioscan.data.SavedDocument
import app.foglioscan.data.SearchMatch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    snackbar: SnackbarHostState,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onAddPages: (SavedDocument) -> Unit = {},
) {
    val docs by viewModel.documents.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = searching) { searching = false; viewModel.setQuery("") }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var toDelete by remember { mutableStateOf<SavedDocument?>(null) }
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (searching) {
                SearchTopBar(
                    query = query,
                    onQuery = viewModel::setQuery,
                    onClose = { searching = false; viewModel.setQuery("") },
                )
            } else {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        if (!docs.isNullOrEmpty()) {
                            IconButton(onClick = { searching = true }, modifier = Modifier.testTag("search")) {
                                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                            }
                        }
                        IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings)) }
                    },
                    scrollBehavior = scroll,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExtendedFloatingActionButton(
                    onClick = onImport,
                    modifier = Modifier.testTag("import"),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    icon = { Icon(painterResource(R.drawable.ic_upload_file), null) },
                    text = { Text(stringResource(R.string.import_file)) },
                )
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    modifier = Modifier.testTag("scan"),
                    icon = { Icon(painterResource(R.drawable.ic_document_scanner), null) },
                    text = { Text(stringResource(R.string.scan)) },
                )
            }
        },
    ) { padding ->
        val list = docs
        val found = results
        when {
            searching && found != null -> SearchResults(found, query, padding, viewModel.repository, onAddPages) { toDelete = it }
            list == null -> Unit
            list.isEmpty() -> EmptyState(Modifier.padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 168.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.recent),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(list, key = { it.uri.toString() }) { doc ->
                    DocumentRow(
                        doc = doc,
                        repository = viewModel.repository,
                        onOpen = { open(context, doc.uri) },
                        onShare = { share(context, doc.uri) },
                        onAddPages = { onAddPages(doc) },
                        onDelete = { toDelete = doc },
                    )
                }
            }
        }
    }

    toDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(painterResource(R.drawable.ic_delete), null) },
            title = { Text(stringResource(R.string.delete_document_title)) },
            text = { Text(stringResource(R.string.delete_document_text, doc.name)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(doc); toDelete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search-field"),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear)) }
            }
        },
    )
}

@Composable
private fun SearchResults(
    results: List<SearchResult>,
    query: String,
    padding: PaddingValues,
    repository: DocumentRepository,
    onAddPages: (SavedDocument) -> Unit,
    onDelete: (SavedDocument) -> Unit,
) {
    val context = LocalContext.current
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                stringResource(R.string.search_none, query.trim()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("search-none"),
            )
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 168.dp)) {
        item {
            Text(
                pluralStringResource(R.plurals.search_count, results.size, results.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(results, key = { it.doc.uri.toString() }) { r ->
            DocumentRow(
                doc = r.doc,
                repository = repository,
                onOpen = { open(context, r.doc.uri) },
                onShare = { share(context, r.doc.uri) },
                onAddPages = { onAddPages(r.doc) },
                onDelete = { onDelete(r.doc) },
                match = r.match,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_document_scanner), null,
                Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DocumentRow(
    doc: SavedDocument,
    repository: DocumentRepository,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onAddPages: () -> Unit,
    onDelete: () -> Unit,
    match: SearchMatch? = null,
) {
    val context = LocalContext.current
    val thumb by produceState<Pair<ImageBitmap, Int>?>(null, doc) {
        value = repository.thumbnail(doc, 160)?.let { (b, n) -> b.asImageBitmap() to n }
    }
    var menu by remember { mutableStateOf(false) }
    val meta = buildString {
        append(DateUtils.getRelativeTimeSpanString(doc.modifiedMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        thumb?.second?.let { append(" · ").append(context.resources.getQuantityString(R.plurals.pages, it, it)) }
        append(" · ").append(Formatter.formatShortFileSize(context, doc.sizeBytes))
    }
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        leadingContent = {
            Box(
                Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                thumb?.first?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Icon(painterResource(R.drawable.ic_description), null, tint = Color.Gray)
            }
        },
        headlineContent = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (match?.page != null) {
                    val highlight = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        buildAnnotatedString {
                            append(stringResource(R.string.search_page, match.page + 1))
                            append(" · ")
                            val start = length
                            append(match.snippet)
                            for (r in match.highlights) addStyle(highlight, start + r.first, start + r.last + 1)
                        },
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("snippet"),
                    )
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_open_in_new), null) },
                        onClick = { menu = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_pages)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_add), null) },
                        onClick = { menu = false; onAddPages() },
                        modifier = Modifier.testTag("add-pages"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_share), null) },
                        onClick = { menu = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    )
}

fun open(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No PDF viewer installed: fall back to the share sheet.
        share(context, uri)
    }
}

fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
