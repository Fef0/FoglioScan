package app.foglioscan.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class PageOrientationTest {

    private fun page(vararg lines: Pair<String, Float>) =
        PageText(100, 100, lines.map { (t, a) -> TextLine(t, 0, FloatArray(8), a) })

    private val body = "Lorem ipsum dolor sit amet consectetur"

    @Test
    fun turnsTheTextUpright() {
        assertEquals(0, PageOrientation.correction(page(body to 0f, body to 1f)))
        assertEquals(270, PageOrientation.correction(page(body to 90f, body to 89f)))
        assertEquals(180, PageOrientation.correction(page(body to 180f, body to -179f)))
        assertEquals(90, PageOrientation.correction(page(body to -90f, body to -89f)))
    }

    @Test
    fun needsEnoughTextAndAClearMajority() {
        assertEquals(0, PageOrientation.correction(page("Fig. 3" to 90f)))
        // A sideways table header on an upright page doesn't turn it.
        assertEquals(0, PageOrientation.correction(page(body to 0f, body to 0f, "Totale per mese" to 90f)))
        // Half and half: undecided.
        assertEquals(0, PageOrientation.correction(page(body to 0f, body to 180f)))
        // Slanted lines don't vote.
        assertEquals(0, PageOrientation.correction(page(body to 45f, body to 45f)))
    }
}
