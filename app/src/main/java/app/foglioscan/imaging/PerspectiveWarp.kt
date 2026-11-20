package app.foglioscan.imaging

import app.foglioscan.vision.Quad
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Rectifies a quadrilateral region of an image into an upright rectangle. */
object PerspectiveWarp {

    /**
     * Output size in pixels for a quad in source pixel coordinates, capped at [maxLongSide].
     *
     * The page's true aspect ratio is recovered from the projective geometry of its corners
     * (Zhang & He, "Whiteboard scanning and image enhancement", 2007): the four corners give the
     * camera focal length and, with it, the real width/height ratio of the rectangle. Side
     * lengths alone are biased under perspective. Ratios within 3% of A4 / US Letter snap.
     *
     * The focal length is only observable when the page is tilted about *both* axes. When the
     * phone is tilted about one axis only (the most common way to hold it over a page), the
     * camera's real focal length is used: [focalPx] (from EXIF) or a typical phone main camera.
     */
    fun outputSize(quadPx: Quad, imgW: Int, imgH: Int, maxLongSide: Int, focalPx: Double? = null): Pair<Int, Int> {
        val sideW = max(quadPx.tl.distanceTo(quadPx.tr), quadPx.bl.distanceTo(quadPx.br))
        val sideH = max(quadPx.tl.distanceTo(quadPx.bl), quadPx.tr.distanceTo(quadPx.br))
        val prior = focalPx ?: (TYPICAL_FOCAL * max(imgW, imgH))
        var aspect = rectAspect(quadPx, imgW / 2.0, imgH / 2.0, max(imgW, imgH).toDouble(), prior)
            ?: (sideW / sideH).toDouble()
        aspect = snapToPaper(aspect)
        // Keep the resolution of the better-sampled dimension.
        var w = max(sideW.toDouble(), sideH * aspect)
        var h = w / aspect
        val s = minOf(1.0, maxLongSide / max(w, h))
        w *= s; h *= s
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    /** width/height of the physical rectangle, or null when the geometry is degenerate. */
    private fun rectAspect(q: Quad, u0: Double, v0: Double, longSide: Double, prior: Double): Double? {
        // Homogeneous corners relative to the principal point. Zhang's labelling:
        // m1=(0,0) m2=(w,0) m3=(0,h) m4=(w,h).
        fun v(p: app.foglioscan.vision.Point2) = doubleArrayOf(p.x - u0, p.y - v0, 1.0)
        val m1 = v(q.tl); val m2 = v(q.tr); val m3 = v(q.bl); val m4 = v(q.br)
        val c14 = cross(m1, m4)
        val k2 = dot(c14, m3) / dot(cross(m2, m4), m3)
        val k3 = dot(c14, m2) / dot(cross(m3, m4), m2)
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        if (!k2.isFinite() || !k3.isFinite()) return null

        // How far each page axis points out of the image plane (tangent, measured with the prior
        // focal length). Below ~6° on either axis the focal length is not observable.
        fun outOfPlane(n: DoubleArray) = abs(n[2]) * prior / sqrt(n[0] * n[0] + n[1] * n[1])
        val observable = minOf(outOfPlane(n2), outOfPlane(n3)) > MIN_TILT_TAN
        val denom = n2[2] * n3[2]
        val f2 = if (observable && abs(denom) > 1e-12) -(n2[0] * n3[0] + n2[1] * n3[1]) / denom else Double.NaN
        // Phone cameras: f ≈ 0.5–2.5× the long side; anything else is noise → use the prior.
        val f = if (f2.isFinite() && f2 > 0 && sqrt(f2) in 0.5 * longSide..2.5 * longSide) sqrt(f2) else prior
        val ff = f * f
        val a2 = (n2[0] * n2[0] + n2[1] * n2[1]) / ff + n2[2] * n2[2]
        val a3 = (n3[0] * n3[0] + n3[1] * n3[1]) / ff + n3[2] * n3[2]
        if (a2 <= 0 || a3 <= 0) return null
        val aspect = sqrt(a2 / a3)
        return aspect.takeIf { it.isFinite() && it in 0.1..10.0 }
    }

    private fun snapToPaper(aspect: Double): Double {
        val portrait = aspect < 1
        val r = if (portrait) 1 / aspect else aspect
        val snapped = when {
            abs(r - A4) / A4 < 0.03 -> A4
            abs(r - LETTER) / LETTER < 0.03 -> LETTER
            else -> r
        }
        return if (portrait) 1 / snapped else snapped
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Main phone camera ≈ 26 mm equivalent → f ≈ 0.75 × long side for a 4:3 photo. */
    private const val TYPICAL_FOCAL = 0.75
    /** tan(6°): minimum out-of-plane tilt on both axes to estimate the focal length. */
    private const val MIN_TILT_TAN = 0.105

    /** Focal length in pixels from the EXIF 35 mm-equivalent focal length. */
    fun focalPxFrom35mm(focal35mm: Double, imgW: Int, imgH: Int): Double =
        focal35mm / 43.267 * sqrt(imgW.toDouble() * imgW + imgH.toDouble() * imgH)

    private const val A4 = 1.41421
    private const val LETTER = 1.29412

    /**
     * Inverse-maps every output pixel through the homography and samples the source
     * bilinearly. [src] is ARGB, [quadPx] in source pixel coordinates.
     */
    suspend fun warp(src: IntArray, srcW: Int, srcH: Int, quadPx: Quad, outW: Int, outH: Int): IntArray {
        val hm = homography(outW, outH, quadPx)
        val out = IntArray(outW * outH)
        val maxX = srcW - 1
        val maxY = srcH - 1
        parallelRanges(outH) { y0, y1 ->
            for (y in y0 until y1) {
                val v = y.toDouble()
                var rowIdx = y * outW
                for (x in 0 until outW) {
                    val u = x.toDouble()
                    val d = hm[6] * u + hm[7] * v + 1.0
                    val sx = ((hm[0] * u + hm[1] * v + hm[2]) / d).toFloat().coerceIn(0f, maxX.toFloat())
                    val sy = ((hm[3] * u + hm[4] * v + hm[5]) / d).toFloat().coerceIn(0f, maxY.toFloat())
                    val x0 = sx.toInt(); val y0i = sy.toInt()
                    val x1 = if (x0 < maxX) x0 + 1 else x0
                    val y1i = if (y0i < maxY) y0i + 1 else y0i
                    val fx = ((sx - x0) * 256).toInt(); val fy = ((sy - y0i) * 256).toInt()
                    val p00 = src[y0i * srcW + x0]; val p10 = src[y0i * srcW + x1]
                    val p01 = src[y1i * srcW + x0]; val p11 = src[y1i * srcW + x1]
                    out[rowIdx++] = bilerp(p00, p10, p01, p11, fx, fy)
                }
            }
        }
        return out
    }

    private fun bilerp(p00: Int, p10: Int, p01: Int, p11: Int, fx: Int, fy: Int): Int {
        val ifx = 256 - fx; val ify = 256 - fy
        fun ch(shift: Int): Int {
            val a = (p00 shr shift) and 0xFF; val b = (p10 shr shift) and 0xFF
            val c = (p01 shr shift) and 0xFF; val d = (p11 shr shift) and 0xFF
            return ((a * ifx + b * fx) * ify + (c * ifx + d * fx) * fy) shr 16
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Homography H mapping output (u, v) -> source (x, y):
     * x = (h0 u + h1 v + h2) / (h6 u + h7 v + 1), y = (h3 u + h4 v + h5) / (h6 u + h7 v + 1)
     */
    private fun homography(outW: Int, outH: Int, q: Quad): DoubleArray {
        val uv = arrayOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(outW - 1.0, 0.0),
            doubleArrayOf(outW - 1.0, outH - 1.0), doubleArrayOf(0.0, outH - 1.0),
        )
        val xy = q.corners
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val u = uv[i][0]; val v = uv[i][1]
            val x = xy[i].x.toDouble(); val y = xy[i].y.toDouble()
            a[2 * i] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y)
        }
        // Gaussian elimination with partial pivoting.
        for (col in 0 until 8) {
            var pivot = col
            for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            val p = a[col][col]
            if (abs(p) < 1e-12) continue
            for (c in col..8) a[col][c] /= p
            for (r in 0 until 8) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (c in col..8) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(8) { a[it][8] }
    }
}
