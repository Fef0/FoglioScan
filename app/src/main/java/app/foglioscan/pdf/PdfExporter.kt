package app.foglioscan.pdf

import android.graphics.Bitmap
import app.foglioscan.imaging.PageFilter
import app.foglioscan.imaging.PageProcessor
import app.foglioscan.ocr.PageText
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import kotlin.math.max

/**
 * Output size / quality trade-off chosen by the user when saving.
 * Resolutions are for the page's long side (A4 long side = 11.7 in).
 */
@Serializable
enum class PdfCompression(
    /** Long side in px, or null to keep the full rendering (≈300 dpi). */
    val maxLongSide: Int?,
    /** JPEG quality for colour/grey pages, or null to pass the rendering through. */
    val jpegQuality: Int?,
    /** Encode black & white pages as 1-bit images. */
    val bilevel: Boolean,
) {
    /** ≈300 dpi, pages exactly as rendered. */
    MAXIMUM(null, null, false),
    /** ≈200 dpi. Indistinguishable on screen and in print for most documents. */
    BALANCED(2480, 76, true),
    /** ≈150 dpi. Good for email. */
    SMALL(1754, 62, true),
    /** ≈100 dpi. Smallest files, still readable. */
    SMALLEST(1240, 48, true),
}

/** A rendered page ready for export, with its recognized text (searchable layer) if any. */
class ExportPage(
    val file: File,
    val widthPx: Int,
    val heightPx: Int,
    val filter: PageFilter,
    val text: PageText? = null,
)

object PdfExporter {

    /** Writes [pages] as a PDF; memory is bounded to one page at a time. */
    fun write(
        out: OutputStream,
        pages: List<ExportPage>,
        compression: PdfCompression,
        title: String,
        onProgress: (done: Int) -> Unit = {},
    ) {
        PdfWriter.write(out, pages.size, title, text = { pages[it].text }) { i ->
            encode(pages[i], compression).also { onProgress(i + 1) }
        }
    }

    /** Encodes one page for [compression]. */
    fun encode(page: ExportPage, compression: PdfCompression): PdfImage {
        val bilevel = compression.bilevel && page.filter == PageFilter.BLACK_WHITE
        if (compression.jpegQuality == null && !bilevel) {
            return PdfImage.Jpeg(page.file.readBytes(), page.widthPx, page.heightPx)
        }
        val maxSide = compression.maxLongSide ?: max(page.widthPx, page.heightPx)
        val bitmap = PageProcessor.decode(page.file, maxSide)
        try {
            return if (bilevel) toBilevel(bitmap) else {
                val bytes = ByteArrayOutputStream(256 * 1024).also {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, compression.jpegQuality ?: PageProcessor.JPEG_QUALITY, it)
                }.toByteArray()
                PdfImage.Jpeg(bytes, bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Size in bytes [page] will take at [compression] (the actual encoding, not a guess). */
    fun encodedSize(page: ExportPage, compression: PdfCompression): Long = when (val img = encode(page, compression)) {
        is PdfImage.Jpeg -> img.bytes.size.toLong()
        is PdfImage.Bilevel -> img.deflated.size.toLong()
    }

    private fun toBilevel(bitmap: Bitmap): PdfImage.Bilevel {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        val packed = ByteArray((w + 7) / 8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val out = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(64 * 1024)
        fun drain() {
            while (!deflater.needsInput()) {
                val n = deflater.deflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
        }
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            packBilevelRow(row, packed)
            deflater.setInput(packed)
            drain()
        }
        deflater.finish()
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return PdfImage.Bilevel(out.toByteArray(), w, h)
    }

    /** Decodes a 1-bit page (as written by [toBilevel]) into a lossless PNG. */
    fun bilevelToPng(img: PdfImage.Bilevel, target: File) {
        val w = img.width
        val rowBytes = (w + 7) / 8
        val inflater = java.util.zip.Inflater()
        inflater.setInput(img.deflated)
        val bitmap = Bitmap.createBitmap(w, img.height, Bitmap.Config.ARGB_8888)
        try {
            val packed = ByteArray(rowBytes)
            val row = IntArray(w)
            for (y in 0 until img.height) {
                var n = 0
                while (n < rowBytes) {
                    val r = inflater.inflate(packed, n, rowBytes - n)
                    if (r == 0 && (inflater.finished() || inflater.needsInput())) break
                    n += r
                }
                for (x in 0 until w) {
                    val white = (packed[x shr 3].toInt() and (0x80 ushr (x and 7))) != 0
                    row[x] = if (white) -0x1 else -0x1000000
                }
                bitmap.setPixels(row, 0, w, 0, y, w, 1)
            }
            target.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            inflater.end()
            bitmap.recycle()
        }
    }

    /** Packs one ARGB row into 1-bit MSB-first (1 = white), threshold at mid-grey. */
    fun packBilevelRow(row: IntArray, packed: ByteArray) {
        packed.fill(0)
        for (x in row.indices) {
            val c = row[x]
            val lum = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            if (lum >= 128) {
                val i = x shr 3
                packed[i] = (packed[i].toInt() or (0x80 ushr (x and 7))).toByte()
            }
        }
    }
}
