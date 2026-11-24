package app.foglioscan

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.foglioscan.imaging.PageFilter
import app.foglioscan.pdf.PdfCompression
import app.foglioscan.session.PageSpec
import app.foglioscan.session.PageState
import app.foglioscan.ui.review.ReviewScreen
import app.foglioscan.ui.review.ZoomState
import app.foglioscan.ui.review.ZoomablePage
import app.foglioscan.ui.theme.FoglioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real touch input (pinch, double-tap, pan, swipe) against the review screen's zoom. */
@RunWith(AndroidJUnit4::class)
class ZoomGestureTest {

    @get:Rule val rule = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    private fun image(color: Int = Color.WHITE): ImageBitmap =
        Bitmap.createBitmap(70, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()

    private fun setZoomable(state: ZoomState, preview: () -> ImageBitmap?, onZoomed: (Boolean) -> Unit = {}) {
        rule.setContent {
            Box(Modifier.size(360.dp, 600.dp).testTag("host")) {
                ZoomablePage(
                    aspectRatio = 0.7f,
                    preview = preview(),
                    fullRes = null,
                    isCurrentPage = true,
                    onZoomedChange = onZoomed,
                    state = state,
                    // The page outline is much smaller than the gesture area.
                    pagePadding = PaddingValues(horizontal = 60.dp, vertical = 60.dp),
                ) { Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) }
            }
        }
    }

    @Test
    fun pinchZoomInThenOutWithFingersOutsideThePageOutline() {
        val state = ZoomState()
        var zoomed = false
        setZoomable(state, { image() }) { zoomed = it }

        rule.onNodeWithTag("zoomable-page").performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(300f, 0f), center + Offset(40f, 0f), center + Offset(300f, 0f))
        }
        rule.waitForIdle()
        assertTrue("zoomed in, scale=${state.scale}", state.scale > 2f)
        assertTrue(zoomed)

        // Fingers start near the screen edges — outside the un-zoomed page outline.
        rule.onNodeWithTag("zoomable-page").performTouchInput {
            pinch(Offset(8f, centerY), Offset(centerX - 10f, centerY), Offset(width - 8f, centerY), Offset(centerX + 10f, centerY))
        }
        rule.waitForIdle()
        assertEquals("back to 1×", 1f, state.scale, 0.001f)
        assertEquals(Offset.Zero, state.offset)
        assertFalse(zoomed)
    }

    @Test
    fun doubleTapTogglesZoom() {
        val state = ZoomState()
        setZoomable(state, { image() })
        rule.onNodeWithTag("zoomable-page").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(ZoomState.DOUBLE_TAP_SCALE, state.scale, 0.01f)
        rule.onNodeWithTag("zoomable-page").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(1f, state.scale, 0.001f)
    }

    @Test
    fun panIsClampedToThePageEdges() {
        val state = ZoomState()
        setZoomable(state, { image() })
        rule.onNodeWithTag("zoomable-page").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        val before = state.offset
        // Drag far further than the page allows, in both directions.
        repeat(2) {
            rule.onNodeWithTag("zoomable-page").performTouchInput {
                down(center); moveBy(Offset(2000f, 2000f)); up()
            }
        }
        rule.waitForIdle()
        val o = state.offset
        assertTrue("moved: $before -> $o", o != before)
        assertTrue("finite offset", o.x.isFinite() && o.y.isFinite())
        // Still zoomed and the page still covers the centre of the viewport.
        assertEquals(ZoomState.DOUBLE_TAP_SCALE, state.scale, 0.01f)
        rule.onNodeWithTag("zoomable-page").performTouchInput { down(center); moveBy(Offset(-4000f, -4000f)); up() }
        rule.waitForIdle()
        assertTrue("clamped symmetrically", kotlin.math.abs(state.offset.x + o.x) < 1f && kotlin.math.abs(state.offset.y + o.y) < 1f)
    }

    @Test
    fun reRenderingWhileZoomedKeepsZoomAndImage() {
        val state = ZoomState()
        var preview by mutableStateOf<ImageBitmap?>(image(Color.WHITE))
        setZoomable(state, { preview })
        rule.onNodeWithTag("zoomable-page").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        val zoom = state.scale
        val offset = state.offset

        // Filter change: new rendering of the same shape arrives.
        rule.runOnIdle { preview = image(Color.LTGRAY) }
        rule.waitForIdle()
        assertEquals(zoom, state.scale, 0.001f)
        assertEquals(offset, state.offset)

        // Zooming out still works afterwards.
        rule.onNodeWithTag("zoomable-page").performTouchInput {
            pinch(Offset(10f, centerY), Offset(centerX - 5f, centerY), Offset(width - 10f, centerY), Offset(centerX + 5f, centerY))
        }
        rule.waitForIdle()
        assertEquals(1f, state.scale, 0.001f)
    }

    /** The real ReviewScreen: swiping pages is blocked while zoomed and works again after. */
    @Test
    fun pagerSwipeIsBlockedOnlyWhileZoomed() {
        val dir = File(app.cacheDir, "zoomtest").apply { mkdirs() }
        val pages = (1..3).map { i ->
            val f = File(dir, "p$i.jpg")
            Bitmap.createBitmap(700, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                .let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
            PageState(
                spec = PageSpec("id$i", f.path, PageFilter.AUTO),
                previewFile = f, output = f, widthPx = 700, heightPx = 1000, processing = false,
            )
        }
        rule.setContent {
            FoglioTheme {
                ReviewScreen(
                    pages = pages, snackbar = SnackbarHostState(), defaultCompression = PdfCompression.BALANCED,
                    estimateSizes = { emptyMap() }, onFilter = { _, _ -> }, onRotate = {}, onCrop = {}, onDelete = {},
                    onRetry = {}, onAddPage = {}, onImport = {}, onSave = { _, _, _, _ -> }, onClose = {},
                )
            }
        }
        rule.onNodeWithText("Page 1 of 3").assertExists()
        fun current() = rule.onAllNodesWithTag("zoomable-page")[0]

        // Swipe → page 2.
        rule.onNodeWithText("Page 1 of 3").assertExists()
        current().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("Page 2 of 3").assertExists()

        // Zoom page 2 (double-tap), then swipe: the page pans, the pager must not move.
        rule.onAllNodesWithTag("zoomable-page").fetchSemanticsNodes().size.let { assertTrue(it >= 1) }
        rule.onNodeWithText("Page 2 of 3").assertExists()
        currentPageNode().performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        currentPageNode().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("Page 2 of 3").assertExists()

        // Zoom out with a pinch, then swipe works again → page 3.
        currentPageNode().performTouchInput {
            pinch(Offset(10f, centerY), Offset(centerX - 5f, centerY), Offset(width - 10f, centerY), Offset(centerX + 5f, centerY))
        }
        rule.waitForIdle()
        currentPageNode().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("Page 3 of 3").assertExists()
    }

    /** The zoomable node that is fully on screen (the pager keeps neighbours composed). */
    private fun currentPageNode() = rule.onAllNodesWithTag("zoomable-page").let { all ->
        val nodes = all.fetchSemanticsNodes()
        val idx = nodes.indexOfFirst { it.boundsInRoot.left >= -1f && it.boundsInRoot.left < 50f }
        all[if (idx >= 0) idx else 0]
    }
}
