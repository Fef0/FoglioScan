package app.foglioscan.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foglioscan.R
import app.foglioscan.camera.FlashMode
import app.foglioscan.camera.ScannerCamera
import app.foglioscan.vision.Quad
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CameraScreen(
    highlightColor: Color,
    useHdr: Boolean,
    pageCount: Int,
    lastPage: ImageBitmap?,
    newCaptureFile: () -> File,
    onCaptured: (photo: File, liveQuad: Quad?) -> Unit,
    onImport: (List<Uri>) -> Unit,
    onHighlightColorChange: (Color) -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
    autoCapture: Boolean = false,
    onAutoCaptureChange: (Boolean) -> Unit = {},
    batchMode: Boolean = false,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it; askedOnce = true
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    // Several photos at once; they become pages in the order picked.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }
    var showColors by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            ScannerViewfinder(
                highlightColor = highlightColor,
                useHdr = useHdr,
                pageCount = pageCount,
                lastPage = lastPage,
                newCaptureFile = newCaptureFile,
                onCaptured = onCaptured,
                onPickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickColor = { showColors = true },
                onDone = onDone,
                onClose = onClose,
                autoCapture = autoCapture,
                onAutoCaptureChange = onAutoCaptureChange,
                batchMode = batchMode,
            )
        } else {
            PermissionRationale(
                permanentlyDenied = askedOnce,
                onGrant = { permission.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onImport = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onClose = onClose,
            )
        }
    }

    if (showColors) {
        HighlightColorSheet(
            current = highlightColor,
            onSelect = onHighlightColorChange,
            onDismiss = { showColors = false },
        )
    }
}

@Composable
private fun ScannerViewfinder(
    highlightColor: Color,
    useHdr: Boolean,
    pageCount: Int,
    lastPage: ImageBitmap?,
    newCaptureFile: () -> File,
    onCaptured: (File, Quad?) -> Unit,
    onPickImage: () -> Unit,
    onPickColor: () -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
    autoCapture: Boolean,
    onAutoCaptureChange: (Boolean) -> Unit,
    batchMode: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val camera = remember { ScannerCamera(context) }
    DisposableEffect(Unit) { onDispose { camera.release() } }
    LaunchedEffect(lifecycleOwner, useHdr) { camera.bind(lifecycleOwner, useHdr) }

    val surfaceRequest by camera.surfaceRequest.collectAsStateWithLifecycle()
    val quad by camera.liveQuad.collectAsStateWithLifecycle()
    val hdrActive by camera.hdrActive.collectAsStateWithLifecycle()
    var flash by rememberSaveable { mutableStateOf(FlashMode.OFF) }
    LaunchedEffect(flash) { camera.flashMode = flash }
    var capturing by remember { mutableStateOf(false) }
    val flashOverlay = remember { Animatable(0f) }

    // Haptic tick the moment a page is first recognized.
    var wasDetected by remember { mutableStateOf(false) }
    LaunchedEffect(quad != null) {
        if (quad != null && !wasDetected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        wasDetected = quad != null
    }

    // In batch mode, a short confirmation after each page instead of leaving the camera.
    var captured by remember { mutableStateOf(0) }
    var showCaptured by remember { mutableStateOf(false) }
    LaunchedEffect(captured) {
        if (captured > 0 && batchMode) { showCaptured = true; delay(1600); showCaptured = false }
    }

    fun shoot() {
        if (capturing) return
        capturing = true
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        val hint = quad
        scope.launch {
            flashOverlay.snapTo(0.8f)
            launch { flashOverlay.animateTo(0f, tween(260)) }
            runCatching { camera.capture(newCaptureFile()) }
                .onSuccess { onCaptured(it, hint); captured++ }
            capturing = false
        }
    }

    // Auto-capture: shoot once the outline has stayed put for HOLD_MS. After a shot, the page
    // must leave the frame (or a different page appear) before the next one, so the same
    // sheet is never captured twice.
    var steadiness by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(autoCapture) {
        steadiness = 0f
        if (!autoCapture) return@LaunchedEffect
        val detector = AutoCaptureDetector()
        while (true) {
            withFrameMillis { now ->
                val fire = detector.update(camera.liveQuad.value, now, busy = capturing)
                steadiness = detector.progress
                if (fire) shoot()
            }
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val transformer = remember { MutableCoordinateTransformer() }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
            }
            Spacer(Modifier.weight(1f))
            AutoCaptureToggle(autoCapture, highlightColor) { onAutoCaptureChange(!autoCapture) }
            if (hdrActive) {
                Icon(
                    painterResource(R.drawable.ic_hdr_on), stringResource(R.string.hdr_active),
                    tint = Color.White, modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            IconButton(onClick = { flash = flash.next() }, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                Icon(
                    painterResource(
                        when (flash) {
                            FlashMode.OFF -> R.drawable.ic_flash_off
                            FlashMode.AUTO -> R.drawable.ic_flash_auto
                            FlashMode.ON -> R.drawable.ic_flash_on
                        },
                    ),
                    stringResource(R.string.flash),
                )
            }
            IconButton(onClick = onPickColor, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(highlightColor).border(2.dp, Color.White, CircleShape))
            }
        }

        // Viewfinder with the live page highlight
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .aspectRatio(if (landscape) 4f / 3f else 3f / 4f)
                    .clip(RoundedCornerShape(20.dp))
                    .testTag("viewfinder")
                    .pointerInput(Unit) {
                        // Tap to focus on (and expose for) that part of the page.
                        detectTapGestures { tap ->
                            val surface = with(transformer) { tap.transform() }
                            camera.focusAt(surface.x, surface.y)
                            focusPoint = tap
                            focusTick++
                        }
                    },
            ) {
                surfaceRequest?.let {
                    CameraXViewfinder(surfaceRequest = it, coordinateTransformer = transformer, modifier = Modifier.fillMaxSize())
                }
                PageHighlight(quad, highlightColor, Modifier.fillMaxSize())
                FocusRing(focusPoint, focusTick, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashOverlay.value)))
                if (capturing) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = highlightColor)
                }
            }
            DetectionHint(
                detected = quad != null,
                color = highlightColor,
                message = when {
                    showCaptured -> R.string.hint_next_page
                    quad == null -> R.string.hint_searching
                    autoCapture && steadiness > 0f -> R.string.hint_hold_steady
                    else -> R.string.hint_detected
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }

        // Bottom controls
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalIconButton(onClick = onPickImage, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_photo_library), stringResource(R.string.import_image))
            }
            ShutterButton(
                enabled = !capturing,
                ringColor = if (quad != null) highlightColor else Color.White,
                progress = steadiness,
                onClick = ::shoot,
            )
            PagesButton(pageCount, lastPage, onDone, Modifier.size(56.dp))
        }
    }
}

/** Translucent fill + outline + corner dots in the user's colour, animated in and out. */
@Composable
private fun PageHighlight(quad: Quad?, color: Color, modifier: Modifier) {
    var last by remember { mutableStateOf<Quad?>(null) }
    if (quad != null) last = quad
    val alpha by animateFloatAsState(if (quad != null) 1f else 0f, tween(180), label = "highlight")
    Canvas(modifier) {
        val q = last ?: return@Canvas
        if (alpha <= 0f) return@Canvas
        val pts = q.corners.map { Offset(it.x * size.width, it.y * size.height) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
            close()
        }
        drawPath(path, color.copy(alpha = 0.28f * alpha))
        drawPath(path, color.copy(alpha = alpha), style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
        pts.forEach {
            drawCircle(Color.White.copy(alpha = alpha), radius = 7.dp.toPx(), center = it)
            drawCircle(color.copy(alpha = alpha), radius = 5.dp.toPx(), center = it)
        }
    }
}

/** A ring that shrinks onto the tapped point and fades out. */
@Composable
private fun FocusRing(point: Offset?, tick: Int, modifier: Modifier) {
    val anim = remember { Animatable(1f) }
    LaunchedEffect(tick) {
        if (point == null) return@LaunchedEffect
        anim.snapTo(0f)
        anim.animateTo(1f, tween(900))
    }
    Canvas(modifier) {
        val p = point ?: return@Canvas
        val t = anim.value
        if (t >= 1f) return@Canvas
        val radius = (44f - 12f * minOf(t * 3f, 1f)) * density
        val alpha = if (t < 0.7f) 1f else (1f - t) / 0.3f
        drawCircle(Color.White.copy(alpha = alpha), radius, p, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun DetectionHint(detected: Boolean, color: Color, message: Int, modifier: Modifier) {
    Surface(modifier, shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (detected) color else Color.White.copy(alpha = 0.4f)))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(message), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, ringColor: Color, progress: Float, onClick: () -> Unit) {
    val ring by androidx.compose.animation.animateColorAsState(ringColor, label = "ring")
    Box(
        Modifier
            .size(80.dp)
            .testTag("shutter")
            .clip(CircleShape)
            .border(4.dp, ring.copy(alpha = if (progress > 0f) 0.4f else 1f), CircleShape)
            .drawWithContent {
                drawContent()
                // Auto-capture countdown around the shutter.
                if (progress > 0f) {
                    val w = 4.dp.toPx()
                    drawArc(
                        ring, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                        topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                        style = Stroke(width = w, cap = StrokeCap.Round),
                    )
                }
            }
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClickLabel = stringResource(R.string.scan), onClick = onClick),
    )
}

@Composable
private fun AutoCaptureToggle(on: Boolean, color: Color, onClick: () -> Unit) {
    val label = stringResource(R.string.auto_capture_toggle, stringResource(if (on) R.string.on else R.string.off))
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (on) color else Color.White.copy(alpha = 0.15f),
        contentColor = if (on) Color.Black else Color.White,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .testTag("auto-capture")
            .semantics { contentDescription = label },
    ) {
        Text(
            "AUTO",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).clearAndSetSemantics {},
        )
    }
}

@Composable
private fun PagesButton(count: Int, last: ImageBitmap?, onClick: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(count > 0, enter = fadeIn(), exit = fadeOut()) {
        BadgedBox(badge = { Badge { Text("$count") } }) {
            Box(
                modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.DarkGray)
                    .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = stringResource(R.string.review_pages), onClick = onClick),
            ) {
                last?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
    }
    if (count == 0) Spacer(modifier)
}

@Composable
private fun PermissionRationale(
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_document_scanner), null, Modifier.size(64.dp), tint = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.camera_permission_rationale),
            color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = if (permanentlyDenied) onOpenSettings else onGrant) {
            Text(stringResource(if (permanentlyDenied) R.string.open_settings else R.string.grant_camera))
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(onClick = onImport) { Text(stringResource(R.string.import_image)) }
        androidx.compose.material3.TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
    }
}
