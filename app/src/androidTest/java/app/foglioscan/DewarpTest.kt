package app.foglioscan

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.foglioscan.imaging.GridWarp
import app.foglioscan.imaging.PageDewarper
import app.foglioscan.imaging.PageFilter
import app.foglioscan.imaging.PageGrid
import app.foglioscan.imaging.PageProcessor
import app.foglioscan.imaging.PerspectiveWarp
import app.foglioscan.vision.DocumentLocator
import app.foglioscan.vision.Quad
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot

/**
 * Curved-page flattening on synthetic photos with exact ground truth
 * (tools/testdata/make_curved_pages.py): for every 8th photo pixel, the true position on the page.
 * The error of a rendering is the mean distance, as a fraction of the page width, between where
 * each output pixel comes from on the page and where it should come from.
 */
@RunWith(AndroidJUnit4::class)
class DewarpTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext
    private val out = File(app.getExternalFilesDir(null), "dewarp-test").apply { deleteRecursively(); mkdirs() }
    private val scenes = listOf("book", "curl", "wave", "gentle")

    private fun asset(name: String): File = File(app.cacheDir, name).also { f ->
        instr.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
    }

    /** True page position (u, v) for every 8th pixel; NaN off the page. */
    private class Truth(val w: Int, val h: Int, val uv: FloatArray) {
        fun at(x: Float, y: Float): Pair<Float, Float>? {
            // Nearest sample: bilinear would blend with the "off page" marker at the edges.
            val gx = (x / 8f).toInt(); val gy = (y / 8f).toInt()
            if (gx !in 0 until w || gy !in 0 until h) return null
            val u = uv[(gy * w + gx) * 2]
            return if (u.isNaN()) null else u to uv[(gy * w + gx) * 2 + 1]
        }
    }

    private fun truth(name: String, photoW: Int, photoH: Int): Truth {
        val w = (photoW + 7) / 8; val h = (photoH + 7) / 8
        val bytes = instr.context.assets.open("curved_${name}_uv.bin").use { it.readBytes() }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val uv = FloatArray(w * h * 2)
        for (i in 0 until w * h) {
            val a = shorts.get(2 * i).toInt() and 0xFFFF; val b = shorts.get(2 * i + 1).toInt() and 0xFFFF
            if (a == 65535 && b == 65535) { uv[2 * i] = Float.NaN; uv[2 * i + 1] = Float.NaN }
            else { uv[2 * i] = a / 65535f; uv[2 * i + 1] = b / 65535f }
        }
        return Truth(w, h, uv)
    }

    /**
     * Mean error (fraction of page width) of a rendering given as [map]: output (u, v) in [0,1]²
     * → photo pixel. Output pixels landing off the page count with their distance to the edge.
     */
    private fun error(truth: Truth, map: (Float, Float) -> Pair<Float, Float>): Float {
        val n = 60; var sum = 0.0; var count = 0
        for (j in 0..n) for (i in 0..n) {
            val u = i.toFloat() / n; val v = j.toFloat() / n
            val (x, y) = map(u, v)
            val t = truth.at(x, y)
            if (t == null) { sum += 0.05; count++; continue } // off the page: a fixed penalty
            sum += hypot((t.first - u).toDouble(), ((t.second - v) * A4).toDouble()); count++
        }
        return (sum / count).toFloat()
    }

    private fun gridMap(g: PageGrid): (Float, Float) -> Pair<Float, Float> = { u, v ->
        // Bilinear lookup is enough for scoring (the renderer uses Catmull-Rom).
        val gx = u * (g.cols - 1); val gy = v * (g.rows - 1)
        val c = gx.toInt().coerceAtMost(g.cols - 2); val r = gy.toInt().coerceAtMost(g.rows - 2)
        val fx = gx - c; val fy = gy - r
        fun lerp(f: (Int, Int) -> Float) =
            (f(r, c) * (1 - fx) + f(r, c + 1) * fx) * (1 - fy) + (f(r + 1, c) * (1 - fx) + f(r + 1, c + 1) * fx) * fy
        lerp(g::x) to lerp(g::y)
    }

    private fun quadMap(q: Quad): (Float, Float) -> Pair<Float, Float> {
        val h = PerspectiveWarp.homography(1000, 1000, q)
        return { u, v ->
            val a = u * 999.0; val b = v * 999.0
            val d = h[6] * a + h[7] * b + 1
            ((h[0] * a + h[1] * b + h[2]) / d).toFloat() to ((h[3] * a + h[4] * b + h[5]) / d).toFloat()
        }
    }

    @Test
    fun flattensCurvedPagesBetterThanCorners() {
        val dewarper = assertNotNullAnd(PageDewarper.get(app)) { "UVDoc model unavailable" }
        val locator = DocumentLocator.get(app)
        for (name in scenes) {
            val photo = PageProcessor.decode(asset("curved_$name.jpg"), PageProcessor.MAX_SOURCE_LONG_SIDE)
            val w = photo.width; val h = photo.height
            val src = IntArray(w * h).also { photo.getPixels(it, 0, w, 0, 0, w, h) }
            val detected = locator.locatePrecise(photo)
            photo.recycle()
            val truth = truth(name, w, h)
            val page = (detected ?: Quad.FULL).scaled(w.toFloat(), h.toFloat())
            val xs = page.corners.map { it.x }; val ys = page.corners.map { it.y }
            val mx = (xs.max() - xs.min()) * PageDewarper.CROP_MARGIN; val my = (ys.max() - ys.min()) * PageDewarper.CROP_MARGIN
            val t0 = System.nanoTime()
            val grid = dewarper.predict(src, w,
                (xs.min() - mx).toInt().coerceIn(0, w - 2), (ys.min() - my).toInt().coerceIn(0, h - 2),
                (xs.max() + mx).toInt().coerceIn(2, w), (ys.max() + my).toInt().coerceIn(2, h))
            val ms = (System.nanoTime() - t0) / 1_000_000
            val eGrid = error(truth, gridMap(grid))
            val eQuad = if (detected != null) error(truth, quadMap(page)) else Float.NaN
            Log.i(TAG, "$name: corners ${if (detected == null) "not found" else "%.2f%%".format(eQuad * 100)}, " +
                "UVDoc %.2f%% (curvature %.2f%%) in $ms ms on ${dewarper.accelerator}".format(eGrid * 100, GridWarp.curvature(grid) * 100))
            assertTrue("$name: flattened error ${eGrid * 100}%", eGrid < MAX_FLAT_ERROR)
            // Strong bends: at least 1.5× better than the corners. A gentle bend leaves less to fix.
            val factor = if (name == "gentle") 1f else 1 / 1.5f
            if (detected != null) assertTrue("$name: not enough better than corners ($eGrid vs $eQuad)", eGrid < eQuad * factor)
        }
    }

    @Test
    fun curvedPagesComeOutFlatAndFlatPagesUntouched(): Unit = runBlocking {
        val processor = PageProcessor(DocumentLocator.get(app)) { PageDewarper.get(app) }
        for (name in scenes.map { "curved_$it" } + listOf("tilted", "upright", "projected")) {
            val t0 = System.nanoTime()
            val r = processor.process(asset("$name.jpg"), File(out, "$name.jpg"), File(out, "$name-preview.jpg"), PageFilter.AUTO, 0, null, null, flatten = null)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val aspect = r.heightPx.toFloat() / r.widthPx
            Log.i(TAG, "$name -> flattened=${r.flattened} ${r.widthPx}x${r.heightPx} (aspect %.3f) in $ms ms".format(aspect))
            if (name.startsWith("curved")) {
                assertTrue("$name should be flattened", r.flattened)
                assertEquals("$name: A4 proportions", A4, aspect, 0.06f)
            } else {
                assertFalse("$name is flat", r.flattened)
            }
        }
    }

    private fun <T> assertNotNullAnd(value: T?, message: () -> String): T { assertNotNull(message(), value); return value!! }

    private companion object {
        const val TAG = "DewarpTest"
        const val A4 = 1.41421f
        /**
         * Mean flattening error allowed, fraction of the page width (1% ≈ 25 px at 300 dpi),
         * output off the page counting 5% per sample.
         */
        const val MAX_FLAT_ERROR = 0.025f
    }
}
