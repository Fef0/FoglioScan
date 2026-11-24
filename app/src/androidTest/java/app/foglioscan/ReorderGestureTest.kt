package app.foglioscan

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.foglioscan.imaging.PageFilter
import app.foglioscan.pdf.PdfCompression
import app.foglioscan.session.PageSpec
import app.foglioscan.session.PageState
import app.foglioscan.ui.review.ReviewScreen
import app.foglioscan.ui.theme.FoglioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Long-press-and-drag page reordering in the review strip, and its accessibility actions. */
@RunWith(AndroidJUnit4::class)
class ReorderGestureTest {

    @get:Rule val rule = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pages(n: Int): List<PageState> {
        val dir = File(app.cacheDir, "reorder").apply { mkdirs() }
        return (1..n).map { i ->
            val f = File(dir, "p$i.jpg")
            Bitmap.createBitmap(70, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                .let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
            PageState(PageSpec("id$i", f.path, PageFilter.AUTO), previewFile = f, output = f, widthPx = 700, heightPx = 1000, processing = false)
        }
    }

    private fun host(initial: List<PageState>): () -> List<String> {
        var list by mutableStateOf(initial)
        rule.setContent {
            FoglioTheme {
                ReviewScreen(
                    pages = list, snackbar = SnackbarHostState(), defaultCompression = PdfCompression.BALANCED,
                    estimateSizes = { emptyMap() }, onFilter = { _, _ -> }, onRotate = {}, onCrop = {}, onDelete = {},
                    onRetry = {}, onAddPage = {}, onImport = {}, onSave = { _, _, _ -> }, onClose = {},
                    onMove = { from, to -> list = list.toMutableList().apply { add(to, removeAt(from)) } },
                )
            }
        }
        return { list.map { it.spec.id } }
    }

    @Test
    fun longPressAndDragMovesAPage() {
        val order = host(pages(4))
        // Drag page 1 two slots to the right (thumb 44 dp + 8 dp gap per slot).
        val slot = rule.density.density * 52f
        rule.onNodeWithTag("thumb-0").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(20) { moveBy(Offset(slot * 2.1f / 20, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("id2", "id3", "id1", "id4"), order())

        // A plain tap still selects a page and doesn't move anything.
        rule.onNodeWithTag("thumb-3").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Page 4 of 4").assertExists()
        assertEquals(listOf("id2", "id3", "id1", "id4"), order())

        // Dragging left.
        rule.onNodeWithTag("thumb-3").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(20) { moveBy(Offset(-slot * 3.2f / 20, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("id4", "id2", "id3", "id1"), order())
    }

    @Test
    fun accessibilityActionsMovePages() {
        val order = host(pages(3))
        val actions = rule.onNodeWithTag("thumb-0").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { actions.first { it.label == "Move right" }.action() }
        rule.waitForIdle()
        assertEquals(listOf("id2", "id1", "id3"), order())
    }
}
