package app.foglioscan.ui.camera

import app.foglioscan.vision.Quad

/**
 * Decides when to take the photo by itself: the page outline must stay within [steadyDistance]
 * of where it settled for [holdMs]. After a shot it waits for the page to leave the frame (or a
 * clearly different page to appear) so one sheet is never captured twice — which also makes
 * batch scanning hands-free: swap the sheet, hold, next page.
 *
 * Pure logic driven by the frame clock; see AutoCaptureDetectorTest.
 */
class AutoCaptureDetector(
    private val holdMs: Long = 900,
    private val steadyDistance: Float = 0.015f,
    private val rearmDistance: Float = 0.12f,
    private val minArea: Float = 0.08f,
) {
    private var anchor: Quad? = null
    private var since = 0L
    private var lastShot: Quad? = null

    /** 0 → 1 while the page is held steady; drives the countdown ring. */
    var progress = 0f
        private set

    /** Feeds one frame's outline (normalized). Returns true when the photo should be taken now. */
    fun update(quad: Quad?, nowMs: Long, busy: Boolean = false): Boolean {
        if (quad == null) {
            reset(); lastShot = null
            return false
        }
        lastShot?.let { shot ->
            if (quad.averageCornerDistance(shot) < rearmDistance) { reset(); return false }
            lastShot = null
        }
        if (busy || quad.area() < minArea) { reset(); return false }
        val a = anchor
        if (a == null || quad.averageCornerDistance(a) > steadyDistance) {
            anchor = quad; since = nowMs; progress = 0f
            return false
        }
        progress = ((nowMs - since).toFloat() / holdMs).coerceIn(0f, 1f)
        if (progress < 1f) return false
        lastShot = quad
        reset()
        return true
    }

    private fun reset() { anchor = null; progress = 0f }
}
