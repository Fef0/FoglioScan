package app.foglioscan.vision

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

@Serializable
data class Point2(val x: Float, val y: Float) {
    operator fun plus(o: Point2) = Point2(x + o.x, y + o.y)
    operator fun minus(o: Point2) = Point2(x - o.x, y - o.y)
    operator fun times(s: Float) = Point2(x * s, y * s)
    fun distanceTo(o: Point2): Float = hypot(x - o.x, y - o.y)
}

/**
 * A document outline. Corners are ordered clockwise starting at the top-left.
 * Coordinates are normalized to [0, 1] relative to the image they were detected in,
 * unless stated otherwise.
 */
@Serializable
data class Quad(val tl: Point2, val tr: Point2, val br: Point2, val bl: Point2) {

    val corners: List<Point2> get() = listOf(tl, tr, br, bl)

    fun scaled(sx: Float, sy: Float) = Quad(
        Point2(tl.x * sx, tl.y * sy), Point2(tr.x * sx, tr.y * sy),
        Point2(br.x * sx, br.y * sy), Point2(bl.x * sx, bl.y * sy),
    )

    fun lerp(to: Quad, t: Float) = Quad(
        tl + (to.tl - tl) * t, tr + (to.tr - tr) * t,
        br + (to.br - br) * t, bl + (to.bl - bl) * t,
    )

    fun averageCornerDistance(o: Quad): Float =
        (tl.distanceTo(o.tl) + tr.distanceTo(o.tr) + br.distanceTo(o.br) + bl.distanceTo(o.bl)) / 4f

    /** Same physical outline, re-labelled so the output is rotated 90° clockwise [turns] times. */
    fun rotatedClockwise(turns: Int): Quad {
        var q = this
        repeat(((turns % 4) + 4) % 4) { q = Quad(tl = q.bl, tr = q.tl, br = q.tr, bl = q.br) }
        return q
    }

    fun area(): Float {
        val c = corners
        var a = 0f
        for (i in c.indices) {
            val p = c[i]; val n = c[(i + 1) % c.size]
            a += p.x * n.y - n.x * p.y
        }
        return abs(a) / 2f
    }

    fun isConvex(): Boolean {
        val c = corners
        var sign = 0
        for (i in c.indices) {
            val a = c[i]; val b = c[(i + 1) % 4]; val d = c[(i + 2) % 4]
            val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            if (abs(cross) < 1e-9f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    companion object {
        val FULL = Quad(Point2(0f, 0f), Point2(1f, 0f), Point2(1f, 1f), Point2(0f, 1f))

        /** Orders four arbitrary points clockwise (screen coordinates) starting from the top-left. */
        fun fromPoints(points: List<Point2>): Quad {
            require(points.size == 4)
            val cx = points.sumOf { it.x.toDouble() } / 4
            val cy = points.sumOf { it.y.toDouble() } / 4
            val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val o = List(4) { sorted[(start + it) % 4] }
            return Quad(o[0], o[1], o[2], o[3])
        }
    }
}
