package app.foglioscan.imaging

import app.foglioscan.vision.Point2
import app.foglioscan.vision.Quad
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Splits a photo of an open book (a two-page spread) at its spine. The spread is straightened
 * from its outline, then the gutter is found in the middle band as the darkest valley of the
 * column brightness, separately in the upper and lower halves so a slanted spine is followed.
 * Each half then goes through the normal pipeline (including curved-page flattening).
 */
object BookSplit {

    class Split(
        val left: Quad,
        val right: Quad,
        /** Spine end points, normalized to the photo. */
        val spineTop: Point2,
        val spineBottom: Point2,
        /** Depth of the gutter valley relative to the page brightness; 0 when not found. */
        val confidence: Float,
    )

    /** [quad] is the spread's outline, normalized to the [w]×[h] ARGB image [src]. */
    suspend fun split(src: IntArray, w: Int, h: Int, quad: Quad): Split {
        val q = quad.scaled(w.toFloat(), h.toFloat())
        val sideW = (q.tl.distanceTo(q.tr) + q.bl.distanceTo(q.br)) / 2
        val sideH = (q.tl.distanceTo(q.bl) + q.tr.distanceTo(q.br)) / 2
        val rw = WORK_WIDTH
        val rh = max(50, (WORK_WIDTH * sideH / sideW).roundToInt())
        val img = PerspectiveWarp.warp(src, w, h, q, rw, rh)

        // Spine position in the upper and lower halves of the straightened spread.
        val top = valley(img, rw, (rh * 0.1).toInt(), rh / 2)
        val bottom = valley(img, rw, rh / 2, (rh * 0.9).toInt())
        val (xt, xb) = when {
            top.second >= MIN_DEPTH && bottom.second >= MIN_DEPTH -> top.first to bottom.first
            top.second >= MIN_DEPTH -> top.first to top.first
            bottom.second >= MIN_DEPTH -> bottom.first to bottom.first
            else -> rw / 2f to rw / 2f
        }
        val confidence = max(top.second, bottom.second).takeIf { it >= MIN_DEPTH } ?: 0f
        // The bands' centres are at 30% and 70% of the height: extend the line to the edges.
        val slope = (xb - xt) / 0.4f
        val x0 = xt - slope * 0.3f
        val x1 = xt + slope * 0.7f

        val hm = PerspectiveWarp.homography(rw, rh, q)
        fun toPhoto(x: Float, y: Float): Point2 {
            val d = hm[6] * x + hm[7] * y + 1.0
            return Point2(((hm[0] * x + hm[1] * y + hm[2]) / d / w).toFloat(), ((hm[3] * x + hm[4] * y + hm[5]) / d / h).toFloat())
        }
        val st = toPhoto(x0.coerceIn(0f, rw - 1f), 0f)
        val sb = toPhoto(x1.coerceIn(0f, rw - 1f), rh - 1f)
        return Split(Quad(quad.tl, st, sb, quad.bl), Quad(st, quad.tr, quad.br, sb), st, sb, confidence)
    }

    /**
     * The darkest valley of the column brightness in the middle band, rows [y0, y1): its x and
     * depth (how much darker than the columns around it, relative to the page brightness).
     */
    private fun valley(img: IntArray, w: Int, y0: Int, y1: Int): Pair<Float, Float> {
        val col = FloatArray(w)
        for (y in y0 until y1) {
            var i = y * w
            for (x in 0 until w) {
                val p = img[i++]
                col[x] += (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) / 256f
            }
        }
        val n = (y1 - y0).coerceAtLeast(1)
        for (x in 0 until w) col[x] /= n
        // Light smoothing (text columns are noisy).
        val s = FloatArray(w)
        for (x in 0 until w) {
            var sum = 0f; var c = 0
            for (k in -3..3) { val j = x + k; if (j in 0 until w) { sum += col[j]; c++ } }
            s[x] = sum / c
        }
        val paper = s.sorted()[(w * 0.9).toInt()]
        var bestX = w / 2; var best = 0f
        for (x in (w * 0.3).toInt() until (w * 0.7).toInt()) {
            val left = (x - SIDE_FAR..x - SIDE_NEAR).filter { it >= 0 }.map { s[it] }.average().toFloat()
            val right = (x + SIDE_NEAR..x + SIDE_FAR).filter { it < w }.map { s[it] }.average().toFloat()
            val depth = (minOf(left, right) - s[x]) / max(paper, 1f)
            if (depth > best) { best = depth; bestX = x }
        }
        // Sub-pixel: centre of the valley (weighted by darkness within the deepest part).
        var sw = 0f; var sx = 0f
        for (x in max(0, bestX - 4)..minOf(w - 1, bestX + 4)) {
            val wgt = max(0f, s[bestX] + best * paper * 0.5f - s[x])
            sw += wgt; sx += wgt * x
        }
        return (if (sw > 0) sx / sw else bestX.toFloat()) to best
    }

    private const val WORK_WIDTH = 800
    private const val SIDE_NEAR = 8
    private const val SIDE_FAR = 24
    /** Gutters shallower than this (relative to paper) are not trusted; the middle is used. */
    private const val MIN_DEPTH = 0.06f
}
