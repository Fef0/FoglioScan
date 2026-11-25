package app.foglioscan.imaging

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class GridWarpTest {

    private fun grid(rows: Int = 45, cols: Int = 31, f: (u: Double, v: Double) -> Pair<Double, Double>): PageGrid {
        val xy = FloatArray(rows * cols * 2)
        for (r in 0 until rows) for (c in 0 until cols) {
            val (x, y) = f(c.toDouble() / (cols - 1), r.toDouble() / (rows - 1))
            xy[(r * cols + c) * 2] = x.toFloat(); xy[(r * cols + c) * 2 + 1] = y.toFloat()
        }
        return PageGrid(rows, cols, xy)
    }

    /** A flat page in strong perspective: a homography of the unit square. */
    private fun flat() = grid { u, v ->
        val d = 1 + 0.4 * u + 0.25 * v
        (300 + 1800 * u + 200 * v) / d to (400 + 150 * u + 2600 * v) / d
    }

    @Test
    fun flatPagesHaveNoCurvature() {
        assertEquals(0f, GridWarp.curvature(flat()), 1e-4f)
    }

    @Test
    fun bentPagesAreCurved() {
        // A page bowed by 5% of its width in the middle, like an open book.
        val bowed = grid { u, v -> 300 + 1800 * u to 400 + 2600 * v + 90 * sin(PI * u) }
        val c = GridWarp.curvature(bowed)
        assertTrue("curvature $c", c > GridWarp.CURVED)
        // A barely bent one (0.3%) is not.
        val almost = grid { u, v -> 300 + 1800 * u to 400 + 2600 * v + 6 * sin(PI * u) }
        assertTrue(GridWarp.curvature(almost) < GridWarp.CURVED)
    }

    @Test
    fun turningTheGridTurnsTheOutput() {
        val g = grid(3, 2) { u, v -> u to v } // (x, y) = (u, v)
        val t = g.rotatedClockwise(1)
        assertEquals(2, t.rows); assertEquals(3, t.cols)
        // Turned clockwise, the output's top-left comes from the page's bottom-left.
        assertEquals(0f, t.x(0, 0)); assertEquals(1f, t.y(0, 0))
        assertEquals(0f, t.x(0, 2)); assertEquals(0f, t.y(0, 2))
        assertEquals(1f, t.x(1, 0)); assertEquals(1f, t.y(1, 0))
        val back = g.rotatedClockwise(4)
        assertTrue(back.xy.contentEquals(g.xy))
    }

    @Test
    fun outputSizeFollowsThePageSurface() {
        // An A4 page seen straight on at 1 px per unit, 1000 × 1414.
        val (w, h) = GridWarp.outputSize(grid { u, v -> 1000 * u to 1414.2 * v }, 1000, 1414, 3508)
        assertEquals(1000f, w.toFloat(), 2f)
        assertEquals(1414f, h.toFloat(), 3f)
        // Capped at the maximum long side, aspect kept.
        val (w2, h2) = GridWarp.outputSize(grid { u, v -> 4000 * u to 5657 * v }, 4000, 5657, 3508)
        assertEquals(3508, h2)
        assertEquals(2481f, w2.toFloat(), 2f)
    }

    @Test
    fun warpingAlongAnAffineGridIsAPlainResample() = runBlocking {
        // Source: horizontal gradient. Grid: the whole image. Output equals the source.
        val w = 64; val h = 48
        val src = IntArray(w * h) { i -> val v = (i % w) * 4; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val g = grid(5, 4) { u, v -> u * (w - 1) to v * (h - 1) }
        val out = GridWarp.warp(src, w, h, g, w, h)
        // Within one level (float rounding of the sampling position).
        for (i in out.indices) assertEquals("pixel $i", (src[i] and 0xFF).toFloat(), (out[i] and 0xFF).toFloat(), 1f)
    }
}
