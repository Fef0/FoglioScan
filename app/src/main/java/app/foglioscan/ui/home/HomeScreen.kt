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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    snackbar: SnackbarHostState,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    val docs by viewModel.documents.collectAsStateWithLifecycle()
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
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings)) }
                },
                scrollBehavior = scroll,
            )
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
        when {
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
    onDelete: () -> Unit,
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
        supportingContent = { Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
