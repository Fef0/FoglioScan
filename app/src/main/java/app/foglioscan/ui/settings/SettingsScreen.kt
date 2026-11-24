package app.foglioscan.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foglioscan.R
import app.foglioscan.camera.ScannerCamera
import app.foglioscan.data.CameraSource
import app.foglioscan.data.SettingsRepository
import app.foglioscan.ui.camera.HighlightColorSheet
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source by settings.cameraSource.collectAsStateWithLifecycle(CameraSource.IN_APP)
    val hdr by settings.phoneHdr.collectAsStateWithLifecycle(true)
    val ocr by settings.ocr.collectAsStateWithLifecycle(true)
    val autoCapture by settings.autoCapture.collectAsStateWithLifecycle(true)
    val batch by settings.batchMode.collectAsStateWithLifecycle(false)
    val highlight by settings.highlightColor.collectAsStateWithLifecycle(SettingsRepository.DEFAULT_HIGHLIGHT)
    val hdrAvailable by produceState<Boolean?>(null) { value = ScannerCamera.isPhoneHdrAvailable(context) }
    var pickColor by rememberSaveable { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.settings_camera))
            Column(Modifier.selectableGroup()) {
                SourceOption(
                    selected = source == CameraSource.IN_APP,
                    title = stringResource(R.string.camera_in_app),
                    description = stringResource(R.string.camera_in_app_desc),
                    icon = R.drawable.ic_document_scanner,
                ) { scope.launch { settings.setCameraSource(CameraSource.IN_APP) } }
                SourceOption(
                    selected = source == CameraSource.SYSTEM,
                    title = stringResource(R.string.camera_system),
                    description = stringResource(R.string.camera_system_desc),
                    icon = R.drawable.ic_photo_camera,
                ) { scope.launch { settings.setCameraSource(CameraSource.SYSTEM) } }
            }

            val hdrEnabled = source == CameraSource.IN_APP && hdrAvailable == true
            ListItem(
                modifier = Modifier.toggleable(
                    value = hdr && hdrEnabled,
                    enabled = hdrEnabled,
                    role = Role.Switch,
                    onValueChange = { scope.launch { settings.setPhoneHdr(it) } },
                ),
                leadingContent = { Icon(painterResource(R.drawable.ic_hdr_on), null) },
                headlineContent = { Text(stringResource(R.string.phone_hdr)) },
                supportingContent = {
                    Text(
                        when {
                            source == CameraSource.SYSTEM -> stringResource(R.string.phone_hdr_system)
                            hdrAvailable == null -> stringResource(R.string.phone_hdr_checking)
                            hdrAvailable == false -> stringResource(R.string.phone_hdr_unavailable)
                            else -> stringResource(R.string.phone_hdr_desc)
                        },
                    )
                },
                trailingContent = { Switch(checked = hdr && hdrEnabled, onCheckedChange = null, enabled = hdrEnabled) },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.settings_scanning))
            SwitchItem(
                checked = autoCapture,
                enabled = source == CameraSource.IN_APP,
                icon = R.drawable.ic_document_scanner,
                title = stringResource(R.string.auto_capture),
                description = stringResource(if (source == CameraSource.IN_APP) R.string.auto_capture_desc else R.string.in_app_only),
            ) { scope.launch { settings.setAutoCapture(it) } }
            SwitchItem(
                checked = batch,
                enabled = source == CameraSource.IN_APP,
                icon = R.drawable.ic_burst_mode,
                title = stringResource(R.string.batch_mode),
                description = stringResource(if (source == CameraSource.IN_APP) R.string.batch_mode_desc else R.string.in_app_only),
            ) { scope.launch { settings.setBatchMode(it) } }
            ListItem(
                modifier = Modifier.selectable(selected = false, onClick = { pickColor = true }),
                leadingContent = { Icon(painterResource(R.drawable.ic_palette), null) },
                headlineContent = { Text(stringResource(R.string.highlight_color)) },
                supportingContent = { Text(stringResource(R.string.highlight_color_desc)) },
                trailingContent = {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(Color(highlight))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.settings_text))
            SwitchItem(
                checked = ocr,
                icon = R.drawable.ic_notes,
                title = stringResource(R.string.ocr),
                description = stringResource(R.string.ocr_desc),
            ) { scope.launch { settings.setOcr(it) } }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.version_x, versionName(context))) },
            )
        }
    }

    if (pickColor) {
        HighlightColorSheet(
            current = Color(highlight),
            onSelect = { c -> scope.launch { settings.setHighlightColor(c.toArgb()) } },
            onDismiss = { pickColor = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchItem(
    checked: Boolean,
    icon: Int,
    title: String,
    description: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(value = checked && enabled, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        leadingContent = { Icon(painterResource(icon), null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked && enabled, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun SourceOption(selected: Boolean, title: String, description: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        leadingContent = { Icon(painterResource(icon), null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    )
}

private fun versionName(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
