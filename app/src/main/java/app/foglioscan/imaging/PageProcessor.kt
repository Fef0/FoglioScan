package app.foglioscan.imaging

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.graphics.scale
import app.foglioscan.vision.DocumentLocator
import app.foglioscan.vision.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full "photo → professional scan" pipeline for a single page.
 *
 * Flat pages are rectified from their four corners (exact). Curved pages (books, curled or
 * folded sheets) are flattened with a grid predicted by [dewarper]: the grid is always predicted
 * when allowed, and used only when it shows real bending ([GridWarp.CURVED]), because on a flat
 * page the corner-based correction is more precise.
 */
class PageProcessor(
    private val locator: DocumentLocator,
    private val dewarper: () -> PageDewarper? = { null },
) {

    class Output(
        /** Full-resolution page (JPEG). */
        val file: File,
        /** Screen-size rendering for the review UI (JPEG). Nothing is kept in memory. */
        val previewFile: File,
        val widthPx: Int,
        val heightPx: Int,
        /** The outline actually used, normalized to the source image. */
        val quad: Quad,
        /** The page was flattened as a curved page. */
        val flattened: Boolean = false,
    )

    /**
     * Renders one page. Memory is bounded per call (≈2 full-resolution buffers at a time) and
     * nothing survives it except files on disk, so documents can have any number of pages.
     */
    suspend fun process(
        source: File,
        out: File,
        previewOut: File,
        filter: PageFilter,
        rotation: Int,
        quad: Quad?,
        hint: Quad?,
        /** null: flatten if the page is curved; true: always; false: never (corners only). */
        flatten: Boolean? = false,
    ): Output = withContext(Dispatchers.Default) {
        // Detect + rectify. The source buffers live only inside this block.
        val (used, ow, oh, pixels, flattened) = run {
            val bitmap = decode(source, MAX_SOURCE_LONG_SIDE)
            val sw = bitmap.width
            val sh = bitmap.height
            val used = quad
                ?: locator.locatePrecise(bitmap)
                ?: hint
                ?: Quad.FULL
            coroutineContext.ensureActive()

            val src = IntArray(sw * sh)
            bitmap.getPixels(src, 0, sw, 0, 0, sw, sh)
            bitmap.recycle()

            // Curved page? Predict its grid from a crop around the outline (the whole photo when
            // no page was found: strongly curled pages can defeat the corner detector). Not for
            // a whole image given explicitly, e.g. a finished page taken back from a PDF.
            val tryGrid = flatten == true || (flatten == null && quad != Quad.FULL)
            val grid = if (tryGrid) dewarper()?.let { model ->
                val page = used.scaled(sw.toFloat(), sh.toFloat())
                val xs = page.corners.map { it.x }; val ys = page.corners.map { it.y }
                val mx = (xs.max() - xs.min()) * PageDewarper.CROP_MARGIN
                val my = (ys.max() - ys.min()) * PageDewarper.CROP_MARGIN
                val x0 = (xs.min() - mx).toInt().coerceIn(0, sw - 2)
                val y0 = (ys.min() - my).toInt().coerceIn(0, sh - 2)
                val x1 = (xs.max() + mx).toInt().coerceIn(x0 + 2, sw)
                val y1 = (ys.max() + my).toInt().coerceIn(y0 + 2, sh)
                runCatching { model.predict(src, sw, x0, y0, x1, y1) }
                    .onFailure { android.util.Log.w("PageProcessor", "Dewarping failed", it) }
                    .getOrNull()
                    ?.takeIf { g -> flatten == true || isCurved(g, used, src, sw, sh) }
            } else null
            coroutineContext.ensureActive()
            // The camera's real focal length (EXIF), needed for the page's true aspect ratio
            // when the phone was tilted about a single axis.
            val focal35 = runCatching {
                android.media.ExifInterface(source).getAttributeInt(android.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
            }.getOrDefault(0)
            val focalPx = if (focal35 in 10..300) PerspectiveWarp.focalPxFrom35mm(focal35.toDouble(), sw, sh) else null
            if (grid != null) {
                // Size from the unturned grid (photo coordinates), then turned.
                val (gw0, gh0) = GridWarp.outputSize(grid, sw, sh, MAX_OUTPUT_LONG_SIDE, focalPx)
                val (gw, gh) = if ((rotation / 90) % 2 != 0) gh0 to gw0 else gw0 to gh0
                val turned = grid.rotatedClockwise(rotation / 90)
                return@run Rendered(used, gw, gh, GridWarp.warp(src, sw, sh, turned, gw, gh), true)
            }

            val quadPx = used.scaled(sw.toFloat(), sh.toFloat()).rotatedClockwise(rotation / 90)
            val (ow, oh) = PerspectiveWarp.outputSize(quadPx, sw, sh, MAX_OUTPUT_LONG_SIDE, focalPx)
            // A hair inside the edges so no background pixels bleed into the border (not for a
            // whole image, e.g. a page taken back from a PDF: it would shrink on every edit).
            val sampled = if (used == Quad.FULL) quadPx else inset(quadPx, EDGE_INSET)
            Rendered(used, ow, oh, PerspectiveWarp.warp(src, sw, sh, sampled, ow, oh), false)
        }
        coroutineContext.ensureActive()

        PageEnhancer.enhance(pixels, ow, oh, filter)
        coroutineContext.ensureActive()

        val result = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, ow, 0, 0, ow, oh)
        try {
            withContext(Dispatchers.IO) {
                out.outputStream().buffered().use { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            }
            val preview = downscale(result, PREVIEW_LONG_SIDE)
            try {
                withContext(Dispatchers.IO) {
                    previewOut.outputStream().buffered().use { preview.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, it) }
                }
            } finally {
                if (preview !== result) preview.recycle()
            }
        } finally {
            result.recycle()
        }
        Output(out, previewOut, ow, oh, used, flattened)
    }

    /**
     * A finished page (e.g. taken back from a saved PDF) used as is: the JPEG is copied untouched
     * (no re-encoding) and only the preview is made.
     */
    suspend fun passThrough(source: File, out: File, previewOut: File): Output = withContext(Dispatchers.IO) {
        source.copyTo(out, overwrite = true)
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(out.path, opts)
        val preview = decode(out, PREVIEW_LONG_SIDE)
        try {
            previewOut.outputStream().buffered().use { preview.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, it) }
        } finally {
            preview.recycle()
        }
        Output(out, previewOut, opts.outWidth, opts.outHeight, Quad.FULL)
    }

    /**
     * Curved enough to flatten: the grid bends, and the photo's edges follow its curved
     * boundary rather than the straight outline between the corners. When no page was detected
     * ([used] is the whole photo) there is no straight outline to compare, and bending decides.
     */
    private fun isCurved(grid: PageGrid, used: Quad, src: IntArray, sw: Int, sh: Int): Boolean {
        val bend = GridWarp.curvature(grid)
        if (bend <= GridWarp.CURVED) return false
        if (used == Quad.FULL) return true
        val ratio = EdgeEvidence.ratio(src, sw, sh, grid, used.scaled(sw.toFloat(), sh.toFloat()))
        android.util.Log.i("PageProcessor", "bending %.2f%%, edge ratio %.2f".format(bend * 100, ratio))
        return ratio > EdgeEvidence.CURVED_RATIO
    }

    private data class Rendered(val quad: Quad, val w: Int, val h: Int, val pixels: IntArray, val flattened: Boolean)

    companion object {
        /** A4 at 300 dpi is 3508 px on the long side – the sweet spot for documents. */
        const val MAX_OUTPUT_LONG_SIDE = 3508
        const val MAX_SOURCE_LONG_SIDE = 4096
        const val PREVIEW_LONG_SIDE = 1600
        const val JPEG_QUALITY = 88
        private const val PREVIEW_QUALITY = 85
        private const val EDGE_INSET = 0.004f

        private fun inset(q: Quad, amount: Float): Quad {
            val c = q.corners
            val cx = c.sumOf { it.x.toDouble() }.toFloat() / 4
            val cy = c.sumOf { it.y.toDouble() }.toFloat() / 4
            val m = c.map { app.foglioscan.vision.Point2(it.x + (cx - it.x) * amount * 2, it.y + (cy - it.y) * amount * 2) }
            return Quad(m[0], m[1], m[2], m[3])
        }

        /** Decodes with EXIF orientation applied, downscaled so the long side ≤ [maxLongSide]. */
        fun decode(file: File, maxLongSide: Int): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = max(info.size.width, info.size.height)
                if (long > maxLongSide) {
                    val s = maxLongSide.toFloat() / long
                    decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                }
            }

        fun downscale(b: Bitmap, longSide: Int): Bitmap {
            val long = max(b.width, b.height)
            if (long <= longSide) return b
            val s = longSide.toFloat() / long
            return b.scale((b.width * s).roundToInt(), (b.height * s).roundToInt())
        }
    }
}
