package app.foglioscan.imaging

import app.foglioscan.vision.Point2
import app.foglioscan.vision.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A page's shape as a [rows]×[cols] grid of points in source-image pixels: point (r, c) is where
 * the flat page's position (c / (cols-1), r / (rows-1)) lies in the photo. Stored row-major as
 * x, y pairs. This is what the dewarping network predicts for a curved page.
 */
class PageGrid(val rows: Int, val cols: Int, val xy: FloatArray) {
    init { require(xy.size == rows * cols * 2) }

    fun x(r: Int, c: Int) = xy[(r * cols + c) * 2]
    fun y(r: Int, c: Int) = xy[(r * cols + c) * 2 + 1]

    /** The grid of the page turned clockwise by [turns] quarter turns. */
    fun rotatedClockwise(turns: Int): PageGrid {
        var g = this
        repeat(((turns % 4) + 4) % 4) {
            val src = g
            // Turning clockwise: new(r, c) = old(rows-1-c, r); the new grid is cols × rows.
            val out = FloatArray(xy.size)
            for (r in 0 until src.cols) for (c in 0 until src.rows) {
                val o = (r * src.rows + c) * 2
                out[o] = src.x(src.rows - 1 - c, r)
                out[o + 1] = src.y(src.rows - 1 - c, r)
            }
            g = PageGrid(src.cols, src.rows, out)
        }
        return g
    }
}

object GridWarp {

    /**
     * How far the page departs from a flat sheet: the largest distance between a grid point and
     * the best homography through all of them (a flat page in any perspective fits one exactly),
     * as a fraction of the page diagonal. See [CURVED].
     */
    fun curvature(g: PageGrid): Float {
        val fit = fitHomography(g) ?: return Float.POSITIVE_INFINITY
        var worst = 0.0
        for (r in 0 until g.rows) for (c in 0 until g.cols) {
            val (px, py) = fit.map(c.toDouble() / (g.cols - 1), r.toDouble() / (g.rows - 1))
            worst = max(worst, hypot(px - g.x(r, c), py - g.y(r, c)))
        }
        return (worst / fit.diag).toFloat()
    }

    /** Best homography from the unit square to the grid points (least squares). */
    private class Fit(val h: DoubleArray, val mx: Double, val my: Double, val diag: Double) {
        /** Unit-square (u, v) → image pixels. */
        fun map(u: Double, v: Double): Pair<Double, Double> {
            val d = h[6] * u + h[7] * v + 1.0
            return ((h[0] * u + h[1] * v + h[2]) / d * diag + mx) to ((h[3] * u + h[4] * v + h[5]) / d * diag + my)
        }
    }

    private fun fitHomography(g: PageGrid): Fit? {
        // h8 = 1, on coordinates centred and scaled by the page diagonal for conditioning.
        val n = g.rows * g.cols
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += g.xy[2 * i]; my += g.xy[2 * i + 1] }
        mx /= n; my /= n
        val diag = hypot(
            (g.x(g.rows - 1, g.cols - 1) - g.x(0, 0)).toDouble(),
            (g.y(g.rows - 1, g.cols - 1) - g.y(0, 0)).toDouble(),
        ).coerceAtLeast(1.0)
        val s = 1.0 / diag
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        val row = DoubleArray(8)
        fun accumulate(b: Double) {
            for (i in 0 until 8) {
                atb[i] += row[i] * b
                for (j in 0 until 8) ata[i][j] += row[i] * row[j]
            }
        }
        for (r in 0 until g.rows) for (c in 0 until g.cols) {
            val u = c.toDouble() / (g.cols - 1); val v = r.toDouble() / (g.rows - 1)
            val x = (g.x(r, c) - mx) * s; val y = (g.y(r, c) - my) * s
            row.fill(0.0); row[0] = u; row[1] = v; row[2] = 1.0; row[6] = -u * x; row[7] = -v * x
            accumulate(x)
            row.fill(0.0); row[3] = u; row[4] = v; row[5] = 1.0; row[6] = -u * y; row[7] = -v * y
            accumulate(y)
        }
        return solve(ata, atb)?.let { Fit(it, mx, my, diag) }
    }

    /**
     * Output size of the flattened page, capped at [maxLongSide], snapped to A4 / Letter.
     *
     * Lengths measured along the grid in the photo are skewed by perspective, so the proportions
     * come from the plane that best fits the grid (true aspect ratio from its four corners, as for
     * a flat page, see [PerspectiveWarp.outputSize]), each side then lengthened by how much longer
     * the page is along its curve than straight across. Within 3% of A4 on test pages, against up
     * to 8% for raw lengths. [imgW]×[imgH] is the photo, [focalPx] its focal length if known.
     */
    fun outputSize(g: PageGrid, imgW: Int, imgH: Int, maxLongSide: Int, focalPx: Double? = null): Pair<Int, Int> {
        var rowArc = 0.0; var rowBend = 0.0
        for (r in 0 until g.rows) {
            var len = 0.0
            for (c in 1 until g.cols) len += hypot((g.x(r, c) - g.x(r, c - 1)).toDouble(), (g.y(r, c) - g.y(r, c - 1)).toDouble())
            val chord = hypot((g.x(r, g.cols - 1) - g.x(r, 0)).toDouble(), (g.y(r, g.cols - 1) - g.y(r, 0)).toDouble())
            rowArc += len; rowBend += len / chord.coerceAtLeast(1e-6)
        }
        rowArc /= g.rows; rowBend /= g.rows
        var colArc = 0.0; var colBend = 0.0
        for (c in 0 until g.cols) {
            var len = 0.0
            for (r in 1 until g.rows) len += hypot((g.x(r, c) - g.x(r - 1, c)).toDouble(), (g.y(r, c) - g.y(r - 1, c)).toDouble())
            val chord = hypot((g.x(g.rows - 1, c) - g.x(0, c)).toDouble(), (g.y(g.rows - 1, c) - g.y(0, c)).toDouble())
            colArc += len; colBend += len / chord.coerceAtLeast(1e-6)
        }
        colArc /= g.cols; colBend /= g.cols

        val plane = fitHomography(g)?.let { fit ->
            fun p(u: Double, v: Double) = fit.map(u, v).let { (x, y) -> Point2(x.toFloat(), y.toFloat()) }
            PerspectiveWarp.planeAspect(Quad(p(0.0, 0.0), p(1.0, 0.0), p(1.0, 1.0), p(0.0, 1.0)), imgW, imgH, focalPx)
        }
        val aspect = PerspectiveWarp.snapToPaper(plane?.let { it * rowBend / colBend } ?: (rowArc / colArc))
        var ow = max(rowArc, colArc * aspect)
        var oh = ow / aspect
        val scale = minOf(1.0, maxLongSide / max(ow, oh))
        ow *= scale; oh *= scale
        return max(1, ow.roundToInt()) to max(1, oh.roundToInt())
    }

    /**
     * Renders the page described by [g] as an [outW]×[outH] image: the grid is interpolated
     * smoothly (Catmull-Rom, through every grid point) and the source sampled bilinearly.
     */
    suspend fun warp(src: IntArray, srcW: Int, srcH: Int, g: PageGrid, outW: Int, outH: Int): IntArray {
        val out = IntArray(outW * outH)
        val maxX = (srcW - 1).toFloat()
        val maxY = (srcH - 1).toFloat()
        parallelRanges(outH) { y0, y1 ->
            val rowX = FloatArray(g.cols)
            val rowY = FloatArray(g.cols)
            for (y in y0 until y1) {
                // The grid interpolated down to this output row: one point per grid column.
                val gy = if (outH > 1) y.toFloat() * (g.rows - 1) / (outH - 1) else 0f
                for (c in 0 until g.cols) {
                    rowX[c] = catmullRom(gy, g.rows) { r -> g.x(r, c) }
                    rowY[c] = catmullRom(gy, g.rows) { r -> g.y(r, c) }
                }
                var o = y * outW
                for (x in 0 until outW) {
                    val gx = if (outW > 1) x.toFloat() * (g.cols - 1) / (outW - 1) else 0f
                    val sx = catmullRom(gx, g.cols) { rowX[it] }.coerceIn(0f, maxX)
                    val sy = catmullRom(gx, g.cols) { rowY[it] }.coerceIn(0f, maxY)
                    out[o++] = PerspectiveWarp.sample(src, srcW, srcH, sx, sy)
                }
            }
        }
        return out
    }

    /**
     * Catmull-Rom interpolation of samples 0 until [n] at position [t]. Beyond the ends the
     * samples are extended linearly, so a straight run of points stays exactly straight.
     */
    private inline fun catmullRom(t: Float, n: Int, value: (Int) -> Float): Float {
        val i = t.toInt().coerceIn(0, n - 2)
        val f = t - i
        val p1 = value(i)
        val p2 = value(i + 1)
        val p0 = if (i > 0) value(i - 1) else 2 * p1 - p2
        val p3 = if (i + 2 < n) value(i + 2) else 2 * p2 - p1
        val f2 = f * f; val f3 = f2 * f
        return 0.5f * (2 * p1 + (p2 - p0) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f2 + (3 * p1 - p0 - 3 * p2 + p3) * f3)
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var p = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[p][col])) p = r
            if (abs(m[p][col]) < 1e-12) return null
            val t = m[col]; m[col] = m[p]; m[p] = t
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                if (f != 0.0) for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /**
     * Minimum [curvature] for a page to count as curved. Necessary, not sufficient: the network's
     * grid bends this much on many flat pages too, see [EdgeEvidence].
     */
    const val CURVED = 0.015f
}
