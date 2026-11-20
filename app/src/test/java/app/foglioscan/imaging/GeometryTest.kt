package app.foglioscan.imaging

import app.foglioscan.vision.Point2
import app.foglioscan.vision.Quad
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class GeometryTest {

    // --- Quad ------------------------------------------------------------------------------------

    @Test
    fun cornersAreOrderedClockwiseFromTopLeft() {
        val shuffled = listOf(Point2(1f, 1f), Point2(0f, 0f), Point2(0f, 1f), Point2(1f, 0f))
        assertEquals(Quad.FULL, Quad.fromPoints(shuffled))
        // A page rotated by ~30° still gets a consistent clockwise order.
        val c = Point2(0.5f, 0.5f)
        val rot = listOf(Point2(0.2f, 0.1f), Point2(0.8f, 0.3f), Point2(0.7f, 0.9f), Point2(0.1f, 0.7f))
        val q = Quad.fromPoints(rot.reversed())
        assertTrue(q.isConvex())
        val cross = (q.tr.x - q.tl.x) * (q.br.y - q.tr.y) - (q.tr.y - q.tl.y) * (q.br.x - q.tr.x)
        assertTrue("clockwise in screen coordinates", cross > 0)
        assertTrue(q.tl.x + q.tl.y <= q.corners.minOf { it.x + it.y } + 1e-6f)
        assertEquals(c.x, q.corners.map { it.x }.average().toFloat(), 0.1f)
    }

    @Test
    fun rotationRelabelsCorners() {
        val q = Quad(Point2(0f, 0f), Point2(1f, 0f), Point2(1f, 1f), Point2(0f, 1f))
        assertEquals(q.bl, q.rotatedClockwise(1).tl)
        assertEquals(q, q.rotatedClockwise(4))
        assertEquals(q.rotatedClockwise(3), q.rotatedClockwise(-1))
    }

    @Test
    fun convexityAndArea() {
        assertTrue(Quad.FULL.isConvex())
        assertEquals(1f, Quad.FULL.area(), 1e-6f)
        val bowtie = Quad(Point2(0f, 0f), Point2(1f, 1f), Point2(1f, 0f), Point2(0f, 1f))
        assertFalse(bowtie.isConvex())
    }

    // --- Aspect recovery -------------------------------------------------------------------------

    /** Corners of a [wMm]×[hMm] sheet rotated in 3D and seen by a pinhole camera. */
    private fun project(wMm: Double, hMm: Double, rx: Double, ry: Double, rz: Double, imgW: Int = 3000, imgH: Int = 4000): Quad {
        val f = 0.95 * max(imgW, imgH)
        fun rotate(p: DoubleArray): DoubleArray {
            var (x, y, z) = Triple(p[0], p[1], p[2])
            val ax = Math.toRadians(rx); val ay = Math.toRadians(ry); val az = Math.toRadians(rz)
            var y1 = y * cos(ax) - z * sin(ax); var z1 = y * sin(ax) + z * cos(ax); y = y1; z = z1
            val x1 = x * cos(ay) + z * sin(ay); z1 = -x * sin(ay) + z * cos(ay); x = x1; z = z1
            val x2 = x * cos(az) - y * sin(az); y1 = x * sin(az) + y * cos(az)
            return doubleArrayOf(x2, y1, z)
        }
        val pts = listOf(-wMm / 2 to -hMm / 2, wMm / 2 to -hMm / 2, wMm / 2 to hMm / 2, -wMm / 2 to hMm / 2).map { (x, y) ->
            val r = rotate(doubleArrayOf(x, y, 0.0))
            val z = r[2] + 2.2 * hMm
            Point2((imgW / 2 + f * r[0] / z).toFloat(), (imgH / 2 + f * r[1] / z).toFloat())
        }
        return Quad(pts[0], pts[1], pts[2], pts[3])
    }

    @Test
    fun recoversA4WhenTiltedAboutBothAxesWithoutKnowingTheCamera() {
        // The focal length is estimated from the corners (the synthetic camera's f differs from
        // the built-in prior, so this only passes if the estimate is used).
        for ((rx, ry, rz) in listOf(Triple(0.0, 0.0, 0.0), Triple(30.0, -15.0, 8.0), Triple(-20.0, 20.0, -5.0), Triple(35.0, 12.0, 0.0))) {
            val (w, h) = PerspectiveWarp.outputSize(project(210.0, 297.0, rx, ry, rz), 3000, 4000, 3508)
            assertEquals("A4 at ($rx,$ry,$rz)", 1.4142f, h.toFloat() / w, 0.005f)
        }
    }

    @Test
    fun recoversA4WhenTiltedAboutOneAxisUsingTheCameraFocalLength() {
        // Tilt about one axis only: the focal length is unobservable, EXIF provides it.
        val f = 0.95 * 4000
        for ((rx, ry, rz) in listOf(Triple(25.0, 0.0, 3.0), Triple(40.0, 2.0, 0.0), Triple(0.0, 30.0, -4.0))) {
            val (w, h) = PerspectiveWarp.outputSize(project(210.0, 297.0, rx, ry, rz), 3000, 4000, 3508, focalPx = f)
            assertEquals("A4 at ($rx,$ry,$rz)", 1.4142f, h.toFloat() / w, 0.005f)
        }
    }

    @Test
    fun focalLengthFromExif() {
        // 26 mm-equivalent on a 4000×3000 photo ≈ 3004 px.
        assertEquals(3004.0, PerspectiveWarp.focalPxFrom35mm(26.0, 4000, 3000), 2.0)
    }

    @Test
    fun recoversLetterAndOddShapes() {
        val (lw, lh) = PerspectiveWarp.outputSize(project(215.9, 279.4, 28.0, -10.0, 4.0), 3000, 4000, 3508)
        assertEquals(1.2941f, lh.toFloat() / lw, 0.005f)
        // A receipt (80×250 mm) is not snapped to a paper format and keeps its proportions.
        val (rw, rh) = PerspectiveWarp.outputSize(project(80.0, 250.0, 20.0, 0.0, 0.0), 3000, 4000, 3508)
        assertEquals(250.0 / 80.0, rh.toDouble() / rw, 0.08)
    }

    @Test
    fun outputIsCappedAtTheMaximumSide() {
        val (w, h) = PerspectiveWarp.outputSize(Quad.FULL.scaled(6000f, 8000f), 6000, 8000, 3508)
        assertEquals(3508, max(w, h))
    }

    // --- Warp ------------------------------------------------------------------------------------

    @Test
    fun warpMapsQuadCornersToOutputCorners() = runBlocking {
        // 100×100 source: red top-left quadrant, everything else blue.
        val w = 100
        val src = IntArray(w * w) { i -> if (i % w < 50 && i / w < 50) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        // Identity warp of the full image keeps the colours in place.
        val out = PerspectiveWarp.warp(src, w, w, Quad.FULL.scaled(99f, 99f), 50, 50)
        assertEquals(0xFFFF0000.toInt(), out[5 * 50 + 5])
        assertEquals(0xFF0000FF.toInt(), out[45 * 50 + 45])
        // Warping only the top-left quadrant gives an all-red page.
        val red = PerspectiveWarp.warp(src, w, w, Quad.FULL.scaled(45f, 45f), 20, 20)
        assertTrue(red.all { it == 0xFFFF0000.toInt() })
    }
}
