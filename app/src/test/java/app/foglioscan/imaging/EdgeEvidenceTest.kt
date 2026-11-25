package app.foglioscan.imaging

import app.foglioscan.vision.Point2
import app.foglioscan.vision.Quad
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EdgeEvidenceTest {

    private val w = 800
    private val h = 1000

    /** A light page on a dark table; [bow] px of vertical bowing of its top and bottom edges. */
    private fun photo(bow: Float): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val u = (x - 150f) / 500f
        val inside = u in 0f..1f && run {
            val d = bow * sin(PI * u).toFloat()
            y >= 150 + d && y <= 850 + d
        }
        if (inside) 0xFFEEEEEE.toInt() else 0xFF303030.toInt()
    }

    private fun grid(bow: Float) = PageGrid(45, 31, FloatArray(45 * 31 * 2).also { xy ->
        for (r in 0 until 45) for (c in 0 until 31) {
            val u = c / 30f; val v = r / 44f
            xy[(r * 31 + c) * 2] = 150 + 500 * u
            xy[(r * 31 + c) * 2 + 1] = 150 + 700 * v + bow * sin(PI * u).toFloat()
        }
    })

    private val corners = Quad(Point2(150f, 150f), Point2(650f, 150f), Point2(650f, 850f), Point2(150f, 850f))

    @Test
    fun curvedEdgesFavourTheCurvedOutline() {
        val r = EdgeEvidence.ratio(photo(40f), w, h, grid(40f), corners)
        assertTrue("ratio $r", r > EdgeEvidence.CURVED_RATIO)
    }

    @Test
    fun straightEdgesFavourTheStraightOutline() {
        // The page is flat; the network's grid wrongly bends by 15 px.
        val r = EdgeEvidence.ratio(photo(0f), w, h, grid(15f), corners)
        assertTrue("ratio $r", r < 1.05f)
    }
}
