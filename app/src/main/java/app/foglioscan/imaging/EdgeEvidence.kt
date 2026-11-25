package app.foglioscan.imaging

import app.foglioscan.vision.Point2
import app.foglioscan.vision.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Is the page really curved? The dewarping network's grid is never perfectly flat, even for a
 * flat page (on 284 real flat photos its bending is as large as on curved pages), so the photo
 * itself decides: the page boundary is a strong edge, and it follows either the straight outline
 * between the detected corners or the network's curved one.
 *
 * Measured on flat SmartDoc photos, the ratio curved/straight has median 1.00 and 90th percentile
 * 1.11; on curved pages it is 1.6–1.7. See [CURVED_RATIO].
 */
object EdgeEvidence {

    const val CURVED_RATIO = 1.3f
    private const val WORK_LONG_SIDE = 1000

    /** Edge support of the grid's boundary divided by that of the straight [quadPx] outline. */
    fun ratio(src: IntArray, srcW: Int, srcH: Int, grid: PageGrid, quadPx: Quad): Float {
        val img = Gradients(src, srcW, srcH)
        val s = img.scale
        val curved = ArrayList<List<Point2>>(4)
        curved += (0 until grid.cols).map { Point2(grid.x(0, it) * s, grid.y(0, it) * s) }
        curved += (0 until grid.rows).map { Point2(grid.x(it, grid.cols - 1) * s, grid.y(it, grid.cols - 1) * s) }
        curved += (grid.cols - 1 downTo 0).map { Point2(grid.x(grid.rows - 1, it) * s, grid.y(grid.rows - 1, it) * s) }
        curved += (grid.rows - 1 downTo 0).map { Point2(grid.x(it, 0) * s, grid.y(it, 0) * s) }
        val c = quadPx.corners.map { Point2(it.x * s, it.y * s) }
        val straight = (0 until 4).map { i -> listOf(c[i], c[(i + 1) % 4]) }
        val a = curved.map { img.support(it) }.average()
        val b = straight.map { img.support(it) }.average()
        return (a / max(b, 1e-6)).toFloat()
    }

    /** Smoothed gray image at ≈1000 px and its gradients. */
    private class Gradients(src: IntArray, srcW: Int, srcH: Int) {
        val f = max(1, (max(srcW, srcH) + WORK_LONG_SIDE - 1) / WORK_LONG_SIDE)
        val scale = 1f / f
        val w = srcW / f
        val h = srcH / f
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)

        init {
            // Box-downscale to gray.
            val g = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0
                for (yy in y * f until y * f + f) {
                    var i = yy * srcW + x * f
                    repeat(f) {
                        val p = src[i++]
                        sum += ((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29
                    }
                }
                g[y * w + x] = sum / (256f * f * f)
            }
            // [1 2 1] smoothing, both directions.
            val t = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val l = g[y * w + max(x - 1, 0)]; val r = g[y * w + min(x + 1, w - 1)]
                t[y * w + x] = (l + 2 * g[y * w + x] + r) / 4
            }
            for (y in 0 until h) for (x in 0 until w) {
                val u = t[max(y - 1, 0) * w + x]; val d = t[min(y + 1, h - 1) * w + x]
                g[y * w + x] = (u + 2 * t[y * w + x] + d) / 4
            }
            // Sobel / 8.
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                gx[i] = ((g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - 1] + g[i + w - 1])) / 8
                gy[i] = ((g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1])) / 8
            }
        }

        private fun bilinear(a: FloatArray, x: Float, y: Float): Float {
            val xc = x.coerceIn(0f, w - 1.001f); val yc = y.coerceIn(0f, h - 1.001f)
            val x0 = xc.toInt(); val y0 = yc.toInt(); val fx = xc - x0; val fy = yc - y0
            val i = y0 * w + x0
            return (a[i] * (1 - fx) + a[i + 1] * fx) * (1 - fy) + (a[i + w] * (1 - fx) + a[i + w + 1] * fx) * fy
        }

        /**
         * Mean edge strength across a polyline: |gradient · normal|, the best within ±3 px of each
         * point, sampled every ≈3 px, ends (corners) left out.
         */
        fun support(line: List<Point2>): Double {
            val seg = DoubleArray(line.size)
            for (i in 1 until line.size) seg[i] = seg[i - 1] + line[i].distanceTo(line[i - 1])
            val total = seg.last()
            val n = max(8, (total / 3).toInt())
            var sum = 0.0; var count = 0
            var k = 0
            for (j in n / 10 until n - n / 10) {
                val t = total * j / (n - 1)
                while (k < line.size - 2 && seg[k + 1] < t) k++
                val a = line[k]; val b = line[k + 1]
                val len = max(1e-6, seg[k + 1] - seg[k])
                val f = ((t - seg[k]) / len).toFloat()
                val px = a.x + (b.x - a.x) * f; val py = a.y + (b.y - a.y) * f
                val dl = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1e-6f)
                val nx = -(b.y - a.y) / dl; val ny = (b.x - a.x) / dl
                var best = 0f
                var o = -3f
                while (o <= 3f) {
                    val x = px + nx * o; val y = py + ny * o
                    best = max(best, abs(bilinear(gx, x, y) * nx + bilinear(gy, x, y) * ny))
                    o += 0.5f
                }
                sum += best; count++
            }
            return if (count == 0) 0.0 else sum / count
        }
    }
}
