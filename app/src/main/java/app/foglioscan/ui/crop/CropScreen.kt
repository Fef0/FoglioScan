package app.foglioscan.ui.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.foglioscan.R
import app.foglioscan.imaging.PageProcessor
import app.foglioscan.vision.Point2
import app.foglioscan.vision.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    sourcePath: String,
    initial: Quad?,
    highlightColor: Color,
    onApply: (Quad) -> Unit,
    onCancel: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null, sourcePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { PageProcessor.decode(File(sourcePath), 1800).asImageBitmap() }.getOrNull()
        }
    }
    var quad by rememberSaveable(stateSaver = QuadSaver) { mutableStateOf(initial ?: Quad.FULL) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.adjust_corners)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.cancel)) }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { quad = initial ?: Quad.FULL }, Modifier.weight(1f).height(56.dp)) {
                        Icon(painterResource(R.drawable.ic_restart_alt), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.reset))
                    }
                    OutlinedButton(onClick = { quad = Quad.FULL }, Modifier.weight(1f).height(56.dp)) {
                        Icon(painterResource(R.drawable.ic_fit_screen), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.full_image))
                    }
                    Button(onClick = { onApply(quad) }, Modifier.weight(1f).height(56.dp)) {
                        Icon(painterResource(R.drawable.ic_check), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.apply))
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            val img = image
            if (img == null) CircularProgressIndicator()
            else CornerEditor(img, quad, highlightColor) { quad = it }
        }
    }
}

@Composable
private fun CornerEditor(image: ImageBitmap, quad: Quad, color: Color, onChange: (Quad) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val scale = min(maxW / image.width, maxH / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        val density = androidx.compose.ui.platform.LocalDensity.current
        var dragging by remember { mutableIntStateOf(-1) }
        var current by remember { mutableStateOf(quad) }
        current = quad

        Canvas(
            Modifier
                .size(with(density) { dw.toDp() }, with(density) { dh.toDp() })
                .pointerInput(image) {
                    val touchSlop = 36.dp.toPx()
                    detectDragGestures(
                        onDragStart = { pos ->
                            val corners = current.corners.map { Offset(it.x * dw, it.y * dh) }
                            val nearest = corners.indices.minBy { (corners[it] - pos).getDistance() }
                            dragging = if ((corners[nearest] - pos).getDistance() < touchSlop * 2) nearest else -1
                        },
                        onDragEnd = { dragging = -1 },
                        onDragCancel = { dragging = -1 },
                        onDrag = { change, delta ->
                            if (dragging < 0) return@detectDragGestures
                            change.consume()
                            val pts = current.corners.toMutableList()
                            val p = pts[dragging]
                            pts[dragging] = Point2((p.x + delta.x / dw).coerceIn(0f, 1f), (p.y + delta.y / dh).coerceIn(0f, 1f))
                            val candidate = Quad(pts[0], pts[1], pts[2], pts[3])
                            if (candidate.isConvex()) onChange(candidate)
                        },
                    )
                },
        ) {
            drawImage(image, dstSize = IntSize(dw.roundToInt(), dh.roundToInt()))
            val pts = quad.corners.map { Offset(it.x * size.width, it.y * size.height) }
            val outline = Path().apply {
                moveTo(pts[0].x, pts[0].y); for (i in 1 until 4) lineTo(pts[i].x, pts[i].y); close()
            }
            // Dim everything outside the page.
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size)); addPath(outline)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.45f))
            drawPath(outline, color, style = Stroke(3.dp.toPx()))
            pts.forEachIndexed { i, p ->
                drawCircle(Color.White, if (i == dragging) 14.dp.toPx() else 11.dp.toPx(), p)
                drawCircle(color, if (i == dragging) 11.dp.toPx() else 8.dp.toPx(), p)
            }

            // Magnifier loupe for precise placement.
            if (dragging >= 0) {
                val p = pts[dragging]
                val r = 56.dp.toPx()
                val zoom = 2.5f
                val center = Offset(
                    if (p.x < size.width / 2) size.width - r - 8.dp.toPx() else r + 8.dp.toPx(),
                    r + 8.dp.toPx(),
                )
                val circle = Path().apply { addOval(Rect(center, r)) }
                clipPath(circle) {
                    drawRect(Color.Black, Offset(center.x - r, center.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r))
                    val srcHalf = (r / zoom / scale)
                    val srcSide = (2 * srcHalf).roundToInt().coerceAtMost(min(image.width, image.height))
                    val sx = (p.x / scale - srcHalf).roundToInt().coerceIn(0, image.width - srcSide)
                    val sy = (p.y / scale - srcHalf).roundToInt().coerceIn(0, image.height - srcSide)
                    drawImage(
                        image,
                        srcOffset = IntOffset(sx, sy),
                        srcSize = IntSize(srcSide, srcSide),
                        dstOffset = IntOffset((center.x - r).roundToInt(), (center.y - r).roundToInt()),
                        dstSize = IntSize((2 * r).roundToInt(), (2 * r).roundToInt()),
                    )
                    drawLine(color, Offset(center.x - 12.dp.toPx(), center.y), Offset(center.x + 12.dp.toPx(), center.y), 2.dp.toPx())
                    drawLine(color, Offset(center.x, center.y - 12.dp.toPx()), Offset(center.x, center.y + 12.dp.toPx()), 2.dp.toPx())
                }
                drawCircle(Color.White, r, center, style = Stroke(3.dp.toPx()))
            }
        }
    }
}

private val QuadSaver = androidx.compose.runtime.saveable.Saver<Quad, FloatArray>(
    save = { q -> q.corners.flatMap { listOf(it.x, it.y) }.toFloatArray() },
    restore = { a -> Quad(Point2(a[0], a[1]), Point2(a[2], a[3]), Point2(a[4], a[5]), Point2(a[6], a[7])) },
)
