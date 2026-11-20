package app.foglioscan.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the outline of a sheet of paper in a grayscale image.
 *
 * Pipeline (all pure Kotlin, ~5–10 ms on a 320 px frame):
 *  1. Gaussian blur + Sobel gradients
 *  2. Canny edges (non-maximum suppression + hysteresis, adaptive thresholds)
 *  3. Hough transform where each edge pixel only votes near its gradient direction
 *     (much less clutter than a classic Hough)
 *  4. Every combination of two "horizontal" and two "vertical" lines becomes a quad
 *     hypothesis, scored by how much of its perimeter lies on real edges.
 *
 * Swappable: a learned detector (LiteRT segmentation / corner model) can implement the same
 * contract — see TODO.md.
 */
class DocumentDetector {

    /** Returns the document outline normalized to [0,1] image coordinates, or null. */
    fun detect(image: GrayImage): Quad? {
        val w = image.width
        val h = image.height
        if (w < 32 || h < 32) return null

        val e = edgeMap(image)
        val lines = houghLines(e.edges, e.gx, e.gy, w, h)
        if (lines.size < 4) return null
        val support = dilate(e.edges, w, h)
        val best = bestQuad(lines, support, w, h) ?: return null
        return best.scaled(1f / w, 1f / h)
    }

    private class EdgeMap(val gx: IntArray, val gy: IntArray, val mag: IntArray, val edges: ByteArray, val w: Int, val h: Int)

    private fun edgeMap(image: GrayImage): EdgeMap {
        val w = image.width
        val h = image.height
        val blurred = gaussianBlur(image.data, w, h)
        val gx = IntArray(w * h)
        val gy = IntArray(w * h)
        val mag = IntArray(w * h)
        sobel(blurred, w, h, gx, gy, mag)
        return EdgeMap(gx, gy, mag, canny(gx, gy, mag, w, h), w, h)
    }

    // --- 1. Filtering -----------------------------------------------------------------------

    private fun gaussianBlur(src: ByteArray, w: Int, h: Int): IntArray {
        // Separable [1 4 6 4 1] / 16 kernel, clamped borders.
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                fun p(i: Int) = src[row + i.coerceIn(0, w - 1)].toInt() and 0xFF
                tmp[row + x] = p(x - 2) + 4 * p(x - 1) + 6 * p(x) + 4 * p(x + 1) + p(x + 2)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                fun p(j: Int) = tmp[j.coerceIn(0, h - 1) * w + x]
                out[y * w + x] = (p(y - 2) + 4 * p(y - 1) + 6 * p(y) + 4 * p(y + 1) + p(y + 2)) / 256
            }
        }
        return out
    }

    private fun sobel(src: IntArray, w: Int, h: Int, gx: IntArray, gy: IntArray, mag: IntArray) {
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val a = src[i - w - 1]; val b = src[i - w]; val c = src[i - w + 1]
                val d = src[i - 1]; val f = src[i + 1]
                val g = src[i + w - 1]; val hh = src[i + w]; val k = src[i + w + 1]
                val sx = (c + 2 * f + k) - (a + 2 * d + g)
                val sy = (g + 2 * hh + k) - (a + 2 * b + c)
                gx[i] = sx; gy[i] = sy
                mag[i] = abs(sx) + abs(sy)
            }
        }
    }

    // --- 2. Canny ---------------------------------------------------------------------------

    private fun canny(gx: IntArray, gy: IntArray, mag: IntArray, w: Int, h: Int): ByteArray {
        val nms = IntArray(w * h)
        val hist = IntArray(2048)
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                if (m < 8) continue
                val ax = abs(gx[i]); val ay = abs(gy[i])
                val (n1, n2) = when {
                    ay * 1000 < ax * 414 -> mag[i - 1] to mag[i + 1]                 // ~0°
                    ay * 414 > ax * 1000 -> mag[i - w] to mag[i + w]                 // ~90°
                    (gx[i] > 0) == (gy[i] > 0) -> mag[i - w - 1] to mag[i + w + 1]   // ~45°
                    else -> mag[i - w + 1] to mag[i + w - 1]                         // ~135°
                }
                if (m >= n1 && m > n2) {
                    nms[i] = m
                    hist[min(m, hist.size - 1)]++
                    count++
                }
            }
        }
        if (count == 0) return ByteArray(w * h)

        // Adaptive thresholds from the distribution of edge strengths.
        var acc = 0
        var p70 = 0
        val target = (count * 0.70).toInt()
        for (v in hist.indices) { acc += hist[v]; if (acc >= target) { p70 = v; break } }
        val high = max(40, p70)
        val low = max(20, (high * 0.45f).toInt())

        val edges = ByteArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        for (i in nms.indices) {
            if (nms[i] >= high && edges[i].toInt() == 0) {
                edges[i] = 1
                stack[sp++] = i
                while (sp > 0) {
                    val p = stack[--sp]
                    val px = p % w; val py = p / w
                    for (dy in -1..1) for (dx in -1..1) {
                        val qx = px + dx; val qy = py + dy
                        if (qx <= 0 || qy <= 0 || qx >= w - 1 || qy >= h - 1) continue
                        val q = qy * w + qx
                        if (edges[q].toInt() == 0 && nms[q] >= low) { edges[q] = 1; stack[sp++] = q }
                    }
                }
            }
        }
        return edges
    }

    // --- 3. Hough ---------------------------------------------------------------------------

    /** Line in normal form: x·cosθ + y·sinθ = rho, θ ∈ [0, π). */
    private class Line(val theta: Float, val rho: Float, val votes: Int) {
        val c = cos(theta)
        val s = sin(theta)
        /** Near-horizontal lines have their normal pointing up/down. */
        val isHorizontal get() = theta > PI / 4 && theta < 3 * PI / 4
    }

    private fun houghLines(edges: ByteArray, gx: IntArray, gy: IntArray, w: Int, h: Int): List<Line> {
        val thetaBins = 180
        val rhoMax = ceilDiv(hypot(w.toFloat(), h.toFloat()))
        val rhoBins = 2 * rhoMax + 1
        val cosT = FloatArray(thetaBins) { cos(it * PI / thetaBins).toFloat() }
        val sinT = FloatArray(thetaBins) { sin(it * PI / thetaBins).toFloat() }
        val acc = IntArray(thetaBins * rhoBins)
        val spread = 6 // ±6° around the gradient direction

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (edges[i].toInt() == 0) continue
                var a = atan2(gy[i].toDouble(), gx[i].toDouble())
                if (a < 0) a += PI
                val center = ((a / PI) * thetaBins).roundToInt()
                for (d in -spread..spread) {
                    // Wrap θ into [0, π); rho computed from the wrapped angle is still exact.
                    val t = (center + d + thetaBins) % thetaBins
                    val r = (x * cosT[t] + y * sinT[t]).roundToInt()
                    acc[t * rhoBins + r + rhoMax]++
                }
            }
        }

        val minVotes = max(20, (min(w, h) * 0.12f).toInt())
        val candidates = ArrayList<Line>()
        for (t in 0 until thetaBins) {
            for (r in 0 until rhoBins) {
                val v = acc[t * rhoBins + r]
                if (v < minVotes) continue
                var isMax = true
                loop@ for (dt in -3..3) for (dr in -5..5) {
                    if (dt == 0 && dr == 0) continue
                    val tt = t + dt; val rr = r + dr
                    if (tt < 0 || tt >= thetaBins || rr < 0 || rr >= rhoBins) continue
                    val o = acc[tt * rhoBins + rr]
                    if (o > v || (o == v && (dt < 0 || (dt == 0 && dr < 0)))) { isMax = false; break@loop }
                }
                if (isMax) candidates += Line((t * PI / thetaBins).toFloat(), (r - rhoMax).toFloat(), v)
            }
        }
        candidates.sortByDescending { it.votes }

        // Drop near-duplicates (including the θ≈0 / θ≈π wrap-around).
        val result = ArrayList<Line>()
        for (l in candidates) {
            val dup = result.any { o ->
                val dt = abs(l.theta - o.theta)
                (dt < DEG_5 && abs(l.rho - o.rho) < 10f) ||
                    (PI - dt < DEG_5 && abs(l.rho + o.rho) < 10f)
            }
            if (!dup) result += l
            if (result.size >= 16) break
        }
        return result
    }

    // --- 4. Quad hypotheses ------------------------------------------------------------------

    private fun dilate(edges: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (edges[i].toInt() == 0) continue
            out[i - w - 1] = 1; out[i - w] = 1; out[i - w + 1] = 1
            out[i - 1] = 1; out[i] = 1; out[i + 1] = 1
            out[i + w - 1] = 1; out[i + w] = 1; out[i + w + 1] = 1
        }
        return out
    }

    private fun bestQuad(lines: List<Line>, support: ByteArray, w: Int, h: Int): Quad? {
        val hLines = lines.filter { it.isHorizontal }
        val vLines = lines.filter { !it.isHorizontal }
        if (hLines.size < 2 || vLines.size < 2) return null

        val minArea = w * h * 0.12f
        val marginX = w * 0.04f
        val marginY = h * 0.04f
        var best: Quad? = null
        var bestScore = 0f

        for (i in hLines.indices) for (j in i + 1 until hLines.size) {
            var top = hLines[i]; var bottom = hLines[j]
            if (angleBetween(top, bottom) > MAX_SIDE_ANGLE) continue
            if (yAt(top, w / 2f) > yAt(bottom, w / 2f)) { val t = top; top = bottom; bottom = t }

            for (k in vLines.indices) for (m in k + 1 until vLines.size) {
                var left = vLines[k]; var right = vLines[m]
                if (angleBetween(left, right) > MAX_SIDE_ANGLE) continue
                if (xAt(left, h / 2f) > xAt(right, h / 2f)) { val t = left; left = right; right = t }

                val tl = intersect(top, left) ?: continue
                val tr = intersect(top, right) ?: continue
                val br = intersect(bottom, right) ?: continue
                val bl = intersect(bottom, left) ?: continue
                val q = Quad(tl, tr, br, bl)
                if (q.corners.any { it.x < -marginX || it.y < -marginY || it.x > w + marginX || it.y > h + marginY }) continue
                if (!q.isConvex()) continue
                if (q.area() < minArea) continue
                if (!anglesReasonable(q)) continue

                val score = perimeterSupport(q, support, w, h) ?: continue
                if (score > bestScore) { bestScore = score; best = q }
            }
        }
        // Clamp slightly-outside corners back into the frame.
        return best?.let { q ->
            Quad.fromPoints(q.corners.map { Point2(it.x.coerceIn(0f, w - 1f), it.y.coerceIn(0f, h - 1f)) })
        }
    }

    /** Edge-covered perimeter length; null if any side is poorly supported. */
    private fun perimeterSupport(q: Quad, support: ByteArray, w: Int, h: Int): Float? {
        val c = q.corners
        var total = 0f
        for (s in 0 until 4) {
            val a = c[s]; val b = c[(s + 1) % 4]
            val len = a.distanceTo(b)
            val steps = max(8, (len / 1.5f).toInt())
            var hit = 0
            var inside = 0
            for (n in 0..steps) {
                val t = n.toFloat() / steps
                val x = (a.x + (b.x - a.x) * t).toInt()
                val y = (a.y + (b.y - a.y) * t).toInt()
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                inside++
                if (support[y * w + x].toInt() != 0) hit++
            }
            if (inside < steps / 2) return null
            val frac = hit.toFloat() / inside
            if (frac < 0.35f) return null
            total += frac * frac * len
        }
        return total
    }

    private fun anglesReasonable(q: Quad): Boolean {
        val c = q.corners
        for (i in 0 until 4) {
            val p = c[(i + 3) % 4]; val o = c[i]; val n = c[(i + 1) % 4]
            val v1x = p.x - o.x; val v1y = p.y - o.y
            val v2x = n.x - o.x; val v2y = n.y - o.y
            val cosA = (v1x * v2x + v1y * v2y) / (sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y))
            if (abs(cosA) > COS_MIN_CORNER) return false
        }
        return true
    }

    private fun angleBetween(a: Line, b: Line): Float {
        val d = abs(a.theta - b.theta)
        return min(d, (PI - d).toFloat())
    }

    private fun intersect(a: Line, b: Line): Point2? {
        val det = a.c * b.s - a.s * b.c
        if (abs(det) < 1e-4f) return null
        val x = (a.rho * b.s - b.rho * a.s) / det
        val y = (a.c * b.rho - b.c * a.rho) / det
        return Point2(x, y)
    }

    private fun yAt(l: Line, x: Float) = if (abs(l.s) < 1e-4f) Float.MAX_VALUE else (l.rho - x * l.c) / l.s
    private fun xAt(l: Line, y: Float) = if (abs(l.c) < 1e-4f) Float.MAX_VALUE else (l.rho - y * l.s) / l.c

    private fun ceilDiv(v: Float) = kotlin.math.ceil(v).toInt()

    private companion object {
        val DEG_5 = (5 * PI / 180).toFloat()
        val MAX_SIDE_ANGLE = (40 * PI / 180).toFloat()
        /** Corners must be between ~40° and ~140°. */
        val COS_MIN_CORNER = cos(40 * PI / 180).toFloat()
    }
}
