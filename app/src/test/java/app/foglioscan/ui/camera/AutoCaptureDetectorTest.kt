package app.foglioscan.ui.camera

import app.foglioscan.vision.Point2
import app.foglioscan.vision.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureDetectorTest {

    private fun page(dx: Float = 0f) = Quad(Point2(0.2f + dx, 0.15f), Point2(0.8f + dx, 0.15f), Point2(0.8f + dx, 0.85f), Point2(0.2f + dx, 0.85f))

    /** Feeds [quad] every 33 ms for [ms]; returns the number of shots. */
    private fun AutoCaptureDetector.feed(quad: (Long) -> Quad?, from: Long, ms: Long): Int =
        (from until from + ms step 33).count { update(quad(it), it) }

    @Test
    fun shootsOnceAfterHoldingSteady() {
        val d = AutoCaptureDetector(holdMs = 900)
        assertEquals(0, d.feed({ page() }, 0, 850))
        assertTrue(d.progress > 0.8f)
        assertEquals(1, d.feed({ page() }, 850, 3000))   // exactly one shot, never repeated
    }

    @Test
    fun smallJitterIsSteadyButMovementRestarts() {
        val d = AutoCaptureDetector(holdMs = 900)
        // ±0.4% jitter: still steady.
        assertEquals(1, d.feed({ t -> page(if ((t / 33) % 2 == 0L) 0.004f else -0.004f) }, 0, 1200))
        val e = AutoCaptureDetector(holdMs = 900)
        // Sliding 3% every 300 ms: never steady.
        assertEquals(0, e.feed({ t -> page((t / 300) * 0.03f) }, 0, 3000))
    }

    @Test
    fun nextPageNeedsThePageToLeaveOrChange() {
        val d = AutoCaptureDetector(holdMs = 900)
        assertEquals(1, d.feed({ page() }, 0, 1000))
        assertEquals(0, d.feed({ page() }, 1000, 2000))
        // Sheet removed, next sheet placed: shoots again.
        assertEquals(0, d.feed({ null }, 3000, 300))
        assertEquals(1, d.feed({ page() }, 3300, 1000))
        // A clearly different position also counts as a new page.
        assertEquals(1, d.feed({ page(0.15f) }, 4300, 1000))
    }

    @Test
    fun tinyOrBusyDetectionsNeverShoot() {
        val tiny = Quad(Point2(0.45f, 0.45f), Point2(0.55f, 0.45f), Point2(0.55f, 0.55f), Point2(0.45f, 0.55f))
        val d = AutoCaptureDetector()
        assertEquals(0, d.feed({ tiny }, 0, 3000))
        assertFalse((0L until 3000L step 33).any { d.update(page(), it, busy = true) })
    }
}
