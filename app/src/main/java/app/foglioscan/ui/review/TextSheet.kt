package app.foglioscan.ui.review

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foglioscan.R
import app.foglioscan.session.PageState
import kotlinx.coroutines.launch

/** The current page's recognized text: selectable and copyable; the whole document shareable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextSheet(
    page: PageState,
    ocrEnabled: Boolean,
    documentText: suspend () -> String,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val text = page.text?.plain
    val label = stringResource(R.string.recognized_text)
    val copiedMsg = stringResource(R.string.text_copied)

    /** Runs [action] on the whole document's text, recognizing pages that still need it. */
    fun withDocumentText(action: (String) -> Unit) {
        busy = true
        scope.launch {
            try { action(documentText()) } finally { busy = false }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 420.dp)) {
                when {
                    !text.isNullOrBlank() -> SelectionContainer {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.verticalScroll(rememberScrollState()).testTag("page-text"),
                        )
                    }
                    text != null -> Text(stringResource(R.string.text_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ocrEnabled || busy || page.processing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.text_reading))
                    }
                    else -> Column {
                        Text(stringResource(R.string.text_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { withDocumentText {} }) { Text(stringResource(R.string.read_now)) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
                            snackbar.showSnackbar(copiedMsg)
                        }
                    },
                    enabled = !text.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(painterResource(R.drawable.ic_content_copy), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_page), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                    onClick = {
                        withDocumentText { all ->
                            if (all.isBlank()) return@withDocumentText
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, all)
                            context.startActivity(Intent.createChooser(send, null))
                        }
                    },
                    enabled = !busy && (ocrEnabled || text != null),
                    modifier = Modifier.weight(1f).testTag("share-text"),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_share), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share_all_text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
