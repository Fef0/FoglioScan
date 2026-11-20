package app.foglioscan.ui.review

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import app.foglioscan.imaging.PageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Zoom/pan state of one page; hoisted so tests (and callers) can observe it. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        internal set
    var offset by mutableStateOf(Offset.Zero)
        internal set
    val isZoomed: Boolean get() = scale > ZOOMED_THRESHOLD

    companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
        internal const val ZOOMED_THRESHOLD = 1.01f
        internal const val SHARP_THRESHOLD = 1.3f
    }
}

@Composable
fun rememberZoomState() = remember { ZoomState() }

/**
 * A page that can be pinch-zoomed, double-tap-zoomed and panned.
 *
 * - The gesture area is the whole [modifier] area (the pager page), not just the page outline:
 *   once zoomed, fingers anywhere on screen work, including for zooming back out.
 * - The page shape comes from [aspectRatio] (known before any image loads), so the layout
 *   never jumps.
 * - Past 1.3× the full-resolution rendering is decoded and swapped in. When the page is
 *   re-rendered (filter change, …) the current image stays until the new one is ready.
 * - At 1× single-finger drags are left to the surrounding pager.
 */
@Composable
fun ZoomablePage(
    aspectRatio: Float,
    preview: ImageBitmap?,
    fullRes: File?,
    isCurrentPage: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    state: ZoomState = rememberZoomState(),
    pagePadding: PaddingValues = PaddingValues(),
    content: @Composable (ImageBitmap?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var page by remember { mutableStateOf(IntSize.Zero) }
    var animation by remember { mutableStateOf<Job?>(null) }
    val notifyZoomed by rememberUpdatedState(onZoomedChange)
    var reportedZoomed by remember { mutableStateOf(false) }

    fun publishZoomed() {
        val z = state.isZoomed
        if (z != reportedZoomed) { reportedZoomed = z; notifyZoomed(z) }
    }

    /** Pan limits: the zoomed page may move until its edges reach the viewport's edges. */
    fun clamp(o: Offset, s: Float): Offset {
        if (!o.x.isFinite() || !o.y.isFinite() || !s.isFinite()) return Offset.Zero
        val maxX = ((page.width * s - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((page.height * s - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Offset that keeps [focus] (relative to the viewport centre) still while zooming to [target]. */
    fun offsetFor(target: Float, focus: Offset, from: Float = state.scale): Offset =
        if (target <= 1f) Offset.Zero else clamp(focus - (focus - state.offset) * (target / from), target)

    fun animateTo(targetScale: Float, targetOffset: Offset) {
        animation?.cancel()
        val startScale = state.scale
        val startOffset = state.offset
        animation = scope.launch {
            animate(0f, 1f, animationSpec = spring()) { t, _ ->
                state.scale = startScale + (targetScale - startScale) * t
                state.offset = startOffset + (targetOffset - startOffset) * t
            }
            publishZoomed()
        }
    }

    // Leaving the page (pager, thumbnails) resets it; disposal never leaves the pager locked.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && state.scale != 1f) {
            animation?.cancel(); state.scale = 1f; state.offset = Offset.Zero; publishZoomed()
        }
    }
    DisposableEffect(Unit) { onDispose { if (reportedZoomed) notifyZoomed(false) } }

    // Full-resolution image while zoomed. Kept while the page re-renders (fullRes == null)
    // and replaced only once the new one is decoded; released when zooming out.
    val wantSharp = state.scale > ZoomState.SHARP_THRESHOLD
    var sharp by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(fullRes, wantSharp) {
        if (!wantSharp) { sharp = null; return@LaunchedEffect }
        if (fullRes == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { PageProcessor.decode(fullRes, PageProcessor.MAX_OUTPUT_LONG_SIDE).asImageBitmap() }.getOrNull()
        }?.let { sharp = it }
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag("zoomable-page")
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val target = if (state.scale > 1.05f) 1f else ZoomState.DOUBLE_TAP_SCALE
                        val focus = tap - Offset(viewport.width / 2f, viewport.height / 2f)
                        if (target > 1f && !reportedZoomed) { reportedZoomed = true; notifyZoomed(true) }
                        animateTo(target, offsetFor(target, focus))
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    animation?.cancel()
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || (fingers == 1 && state.isZoomed)) {
                            val zoom = if (fingers > 1) event.calculateZoom() else 1f
                            val pan = event.calculatePan()
                            if (zoom.isFinite() && zoom > 0f && pan.x.isFinite() && pan.y.isFinite()) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val focus = if (centroid.isSpecified && centroid.x.isFinite() && centroid.y.isFinite()) {
                                    centroid - Offset(viewport.width / 2f, viewport.height / 2f)
                                } else Offset.Zero
                                val newScale = (state.scale * zoom).coerceIn(1f, ZoomState.MAX_SCALE)
                                state.offset = clamp(focus - (focus - state.offset) * (newScale / state.scale) + pan, newScale)
                                state.scale = newScale
                                publishZoomed()
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    // Released near 1× → settle exactly at 1×.
                    if (state.scale < 1.05f && state.scale != 1f) animateTo(1f, Offset.Zero)
                    else state.offset = clamp(state.offset, state.scale)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(pagePadding)
                .aspectRatio(aspectRatio)
                .onSizeChanged { page = it }
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x
                    translationY = state.offset.y
                },
        ) {
            content(if (wantSharp) sharp ?: preview else preview)
        }
    }
}
