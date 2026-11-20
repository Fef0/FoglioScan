package app.foglioscan.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.foglioscan.R

private val Swatches = listOf(
    0xFF00C2A8, 0xFF00B0FF, 0xFF2962FF, 0xFF7C4DFF, 0xFFE040FB,
    0xFFFF4081, 0xFFFF5252, 0xFFFF9100, 0xFFFFD600, 0xFF76FF03,
).map { Color(it) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HighlightColorSheet(current: Color, onSelect: (Color) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.highlight_color), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.highlight_color_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Swatches.forEach { c ->
                    val selected = c.toArgb() == current.toArgb()
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .semantics { this.selected = selected; contentDescription = "#%06X".format(c.toArgb() and 0xFFFFFF) }
                            .clickable(role = Role.RadioButton) { onSelect(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(painterResource(R.drawable.ic_check), null, tint = Color.Black.copy(alpha = 0.7f))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.custom_color), style = MaterialTheme.typography.titleSmall)
            HueSlider(current, onSelect)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HueSlider(current: Color, onSelect: (Color) -> Unit) {
    val hsv = remember(current) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(current.toArgb(), it) }
    }
    var hue by remember(current) { mutableFloatStateOf(hsv[0]) }
    val rainbow = remember { Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }) }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(rainbow))
        Slider(
            value = hue,
            onValueChange = { hue = it },
            onValueChangeFinished = { onSelect(Color.hsv(hue, 1f, 1f)) },
            valueRange = 0f..359f,
            colors = SliderDefaults.colors(
                thumbColor = Color.hsv(hue, 1f, 1f),
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
        )
    }
}
