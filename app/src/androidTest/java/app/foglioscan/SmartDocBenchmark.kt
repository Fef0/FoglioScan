package app.foglioscan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.foglioscan.imaging.PageProcessor
import app.foglioscan.vision.DocumentDetector
import app.foglioscan.vision.DocumentLocator
import app.foglioscan.vision.GrayImage
import app.foglioscan.vision.Point2
import app.foglioscan.vision.Quad
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Real-photo benchmark on a subset of SmartDoc 2015 Challenge 1 (CC BY 4.0). Build the data
 * first with `python tools/fetch_smartdoc.py testdata/smartdoc`; it is packaged into the test
 * APK's assets (never into the app).
 *
 * For every frame, three locators are compared against the ground-truth quadrilateral:
 *  - precise: the shutter path (neural network on the full-resolution photo)
 *  - live:    the viewfinder path (neural network on a 640 px frame)
 *  - classic: the Canny/Hough fallback alone
 *
 * Metric: polygon IoU in image space (a frame counts as correct at IoU ≥ 0.9, the usual bar for
 * a usable crop) plus mean corner error as % of the document diagonal.
 * Results go to logcat (tag SmartDoc) and results.csv; overlays of the worst frames are saved.
 */
@RunWith(AndroidJUnit4::class)
class SmartDocBenchmark {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext
    private val assets = instr.context.assets
    private val cache = File(app.cacheDir, "smartdoc").apply { mkdirs() }

    /** Copies a dataset image out of the test APK so it can be decoded like a real capture. */
    private fun image(name: String): File = File(cache, name).also { f ->
        if (!f.exists()) assets.open("smartdoc/images/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
    }
    private val out = File(app.getExternalFilesDir(null), "smartdoc-results").apply { deleteRecursively(); mkdirs() }

    private class Row(val image: String, val background: String, val doctype: String, val gt: Quad)
    private class Result(val method: String, val row: Row, val quad: Quad?, val iou: Double, val cornerErr: Double, val ms: Double)

    @Test
    fun benchmark() {
        val gtText = runCatching { assets.open("smartdoc/groundtruth.csv").bufferedReader().readText() }.getOrNull()
        assumeTrue("SmartDoc data missing: run tools/fetch_smartdoc.py testdata/smartdoc", gtText != null)
        val rows = gtText!!.lines().drop(1).filter { it.isNotBlank() }.map { line ->
            val c = line.split(",")
            val v = c.drop(3).map { it.toFloat() }
            Row(c[0], c[1], c[2], Quad(Point2(v[0], v[1]), Point2(v[2], v[3]), Point2(v[4], v[5]), Point2(v[6], v[7])))
        }

        val locator = DocumentLocator.get(app)
        val classic = DocumentDetector()
        locator.warmUp()
        Log.i(TAG, "frames=${rows.size} neural=${locator.usesNeuralNetwork}")

        val results = ArrayList<Result>()
        for ((i, row) in rows.withIndex()) {
            val photo = PageProcessor.decode(image(row.image), 4096)
            val w = photo.width.toDouble(); val h = photo.height.toDouble()

            fun run(method: String, block: () -> Quad?) {
                val t0 = System.nanoTime()
                val q = runCatching(block).getOrNull()
                val ms = (System.nanoTime() - t0) / 1e6
                val iou = if (q == null) 0.0 else iou(q, row.gt, w, h)
                val err = if (q == null) Double.NaN else cornerError(q, row.gt, w, h)
                results += Result(method, row, q, iou, err, ms)
            }

            run("precise") { locator.locatePrecise(photo) }
            val small = PageProcessor.downscale(photo, 640)
            run("live") { locator.locateLive(small, 0) }
            if (small !== photo) small.recycle()
            run("classic") { classic.detect(GrayImage.fromBitmap(photo, 720)) }
            photo.recycle()
            if (i % 25 == 0) Log.i(TAG, "progress ${i + 1}/${rows.size}")
        }

        report(results)
        saveOverlays(results)
    }

    // --- reporting -------------------------------------------------------------------------------

    private fun report(results: List<Result>) {
        val csv = StringBuilder("method,image,background,doctype,iou,corner_err_pct,ms\n")
        results.forEach {
            csv.append("${it.method},${it.row.image},${it.row.background},${it.row.doctype},")
                .append(f(it.iou)).append(',').append(if (it.cornerErr.isNaN()) "" else f(it.cornerErr)).append(',')
                .append(f(it.ms)).append('\n')
        }
        File(out, "results.csv").writeText(csv.toString())

        val lines = ArrayList<String>()
        lines += "method   | found | IoU≥0.9 | IoU≥0.8 | mean IoU | median corner err | avg ms"
        for (m in listOf("precise", "live", "classic")) lines += summary(m, results.filter { it.method == m })
        lines += ""
        lines += "IoU≥0.9 by background (precise / live / classic):"
        for (bg in results.map { it.row.background }.distinct().sorted()) {
            val parts = listOf("precise", "live", "classic").map { m ->
                val r = results.filter { it.method == m && it.row.background == bg }
                pct(r.count { it.iou >= 0.9 }, r.size)
            }
            lines += "  $bg: ${parts.joinToString(" / ")}  (n=${results.count { it.method == "precise" && it.row.background == bg }})"
        }
        lines += ""
        lines += "IoU≥0.9 by document type (precise):"
        for (dt in results.map { it.row.doctype }.distinct().sorted()) {
            val r = results.filter { it.method == "precise" && it.row.doctype == dt }
            lines += "  $dt: ${pct(r.count { it.iou >= 0.9 }, r.size)}"
        }
        File(out, "summary.txt").writeText(lines.joinToString("\n"))
        lines.forEach { Log.i(TAG, it) }
    }

    private fun summary(method: String, r: List<Result>): String {
        val found = r.count { it.quad != null }
        val errs = r.mapNotNull { it.cornerErr.takeIf { e -> !e.isNaN() } }.sorted()
        val median = if (errs.isEmpty()) Double.NaN else errs[errs.size / 2]
        return String.format(
            Locale.ROOT, "%-8s | %5s | %7s | %7s | %8.3f | %16.2f%% | %6.0f",
            method, pct(found, r.size), pct(r.count { it.iou >= 0.9 }, r.size), pct(r.count { it.iou >= 0.8 }, r.size),
            r.map { it.iou }.average(), median, r.map { it.ms }.average(),
        )
    }

    private fun saveOverlays(results: List<Result>) {
        // Worst precise results + a few good ones. Green = ground truth, magenta = prediction.
        val precise = results.filter { it.method == "precise" }.sortedBy { it.iou }
        (precise.take(12) + precise.takeLast(4)).forEachIndexed { i, r ->
            val bmp = PageProcessor.decode(image(r.row.image), 1920).copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(bmp)
            draw(c, r.row.gt, bmp, 0xFF00FF00.toInt())
            r.quad?.let { draw(c, it, bmp, 0xFFFF00FF.toInt()) }
            val p = Paint().apply { color = 0xFFFFFF00.toInt(); textSize = 32f }
            c.drawText("${r.row.image}  IoU=${f(r.iou)}  err=${f(r.cornerErr)}%", 16f, 40f, p)
            File(out, String.format(Locale.ROOT, "overlay_%02d_%s.jpg", i, r.row.image.removeSuffix(".jpg")))
                .outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
        }
    }

    private fun draw(c: Canvas, q: Quad, b: Bitmap, color: Int) {
        val p = Paint().apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
        val path = Path()
        q.corners.forEachIndexed { i, pt ->
            if (i == 0) path.moveTo(pt.x * b.width, pt.y * b.height) else path.lineTo(pt.x * b.width, pt.y * b.height)
        }
        path.close(); c.drawPath(path, p)
    }

    // --- geometry --------------------------------------------------------------------------------

    /** IoU of two convex quads in pixel space (Sutherland–Hodgman clipping). */
    private fun iou(a: Quad, b: Quad, w: Double, h: Double): Double {
        val pa = a.corners.map { doubleArrayOf(it.x * w, it.y * h) }
        val pb = b.corners.map { doubleArrayOf(it.x * w, it.y * h) }
        val inter = area(clip(pa, pb))
        val union = area(pa) + area(pb) - inter
        return if (union <= 0) 0.0 else inter / union
    }

    private fun area(p: List<DoubleArray>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a[0] * b[1] - b[0] * a[1] }
        return abs(s) / 2
    }

    private fun clip(subject: List<DoubleArray>, clipPoly: List<DoubleArray>): List<DoubleArray> {
        val ccw = signedArea(clipPoly) > 0
        var output = subject
        for (i in clipPoly.indices) {
            val a = clipPoly[i]; val b = clipPoly[(i + 1) % clipPoly.size]
            fun inside(p: DoubleArray): Boolean {
                val cross = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
                return if (ccw) cross >= 0 else cross <= 0
            }
            val input = output
            if (input.isEmpty()) break
            output = ArrayList()
            for (j in input.indices) {
                val cur = input[j]; val prev = input[(j + input.size - 1) % input.size]
                val ci = inside(cur); val pi = inside(prev)
                if (ci) { if (!pi) output += intersect(prev, cur, a, b); output += cur }
                else if (pi) output += intersect(prev, cur, a, b)
            }
        }
        return output
    }

    private fun signedArea(p: List<DoubleArray>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a[0] * b[1] - b[0] * a[1] }
        return s / 2
    }

    private fun intersect(p1: DoubleArray, p2: DoubleArray, a: DoubleArray, b: DoubleArray): DoubleArray {
        val x1 = p1[0]; val y1 = p1[1]; val x2 = p2[0]; val y2 = p2[1]
        val x3 = a[0]; val y3 = a[1]; val x4 = b[0]; val y4 = b[1]
        val d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(d) < 1e-12) return p2
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d
        return doubleArrayOf(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
    }

    /**
     * Mean corner distance as % of the ground-truth diagonal. The ground truth labels corners by
     * the document's own orientation (a page lying sideways has its "top-left" elsewhere), so the
     * best cyclic correspondence is used.
     */
    private fun cornerError(q: Quad, gt: Quad, w: Double, h: Double): Double {
        val diag = hypot((gt.br.x - gt.tl.x) * w, (gt.br.y - gt.tl.y) * h)
        val g = gt.corners
        val p = q.corners
        val d = (0 until 4).minOf { shift ->
            (0 until 4).map { i ->
                val a = p[i]; val b = g[(i + shift) % 4]
                hypot((a.x - b.x) * w, (a.y - b.y) * h)
            }.average()
        }
        return 100 * d / diag
    }

    private fun pct(n: Int, total: Int) = if (total == 0) "-" else "${(100.0 * n / total).toInt()}%"
    private fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    private companion object { const val TAG = "SmartDoc" }
}
