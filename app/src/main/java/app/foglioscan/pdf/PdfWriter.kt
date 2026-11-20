package app.foglioscan.pdf

import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** An image placed full-page on a PDF page. */
sealed interface PdfImage {
    val width: Int
    val height: Int

    /** JPEG passed through untouched (DCTDecode). */
    class Jpeg(val bytes: ByteArray, override val width: Int, override val height: Int) : PdfImage

    /**
     * 1-bit black & white, rows packed MSB-first (1 = white), Flate-compressed. The format
     * professional scanners use for text: tiny files and perfectly crisp edges.
     */
    class Bilevel(val deflated: ByteArray, override val width: Int, override val height: Int) : PdfImage
}

/**
 * Minimal PDF 1.7 writer. Pages are produced one at a time by [write]'s `page` callback and
 * written straight to the stream, so memory use is independent of the number of pages.
 *
 * TODO(OCR): add an invisible text layer per page (text render mode 3) to make the PDF
 *  searchable/selectable. See TODO.md.
 */
object PdfWriter {

    fun write(out: OutputStream, pageCount: Int, title: String, page: (index: Int) -> PdfImage) {
        require(pageCount > 0)
        val w = CountingStream(out)
        val offsets = LongArray(3 + pageCount * 3)

        fun beginObj(num: Int) {
            offsets[num - 1] = w.count
            w.ascii("$num 0 obj\n")
        }

        w.ascii("%PDF-1.7\n")
        w.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        // 1: catalog, 2: page tree, 3: info; then per page: page, content stream, image.
        beginObj(1); w.ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        beginObj(2)
        w.ascii("<< /Type /Pages /Kids [")
        for (i in 0 until pageCount) w.ascii(if (i == 0) "${4 + i * 3} 0 R" else " ${4 + i * 3} 0 R")
        w.ascii("] /Count $pageCount >>\nendobj\n")
        beginObj(3)
        w.ascii("<< /Title ${utf16Hex(title)} /Producer (FoglioScan) /Creator (FoglioScan) /CreationDate (${pdfDate()}) >>\nendobj\n")

        for (index in 0 until pageCount) {
            val image = page(index)
            val pageObj = 4 + index * 3
            val (pw, ph) = pageSizePoints(image.width, image.height)
            val content = "q ${fmt(pw)} 0 0 ${fmt(ph)} 0 0 cm /Im0 Do Q\n"

            beginObj(pageObj)
            w.ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pw)} ${fmt(ph)}] " +
                    "/Resources << /XObject << /Im0 ${pageObj + 2} 0 R >> /ProcSet [/PDF /ImageC /ImageB] >> " +
                    "/Contents ${pageObj + 1} 0 R >>\nendobj\n",
            )
            beginObj(pageObj + 1)
            w.ascii("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
            beginObj(pageObj + 2)
            when (image) {
                is PdfImage.Jpeg -> {
                    val colorSpace = if (jpegComponents(image.bytes) == 1) "/DeviceGray" else "/DeviceRGB"
                    w.ascii(
                        "<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                            "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${image.bytes.size} >>\nstream\n",
                    )
                    w.write(image.bytes)
                }
                is PdfImage.Bilevel -> {
                    w.ascii(
                        "<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                            "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode /Length ${image.deflated.size} >>\nstream\n",
                    )
                    w.write(image.deflated)
                }
            }
            w.ascii("\nendstream\nendobj\n")
        }

        val xref = w.count
        w.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) w.ascii(String.format(Locale.ROOT, "%010d 00000 n \n", o))
        val id = MessageDigest.getInstance("MD5").digest("$title${System.nanoTime()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        w.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R /ID [<$id> <$id>] >>\nstartxref\n$xref\n%%EOF\n")
        w.flush()
    }

    /**
     * Physical page size in points. Snaps to A4 or US Letter when the aspect ratio matches
     * (the common case), otherwise keeps the scanned aspect with an A4-sized long side.
     */
    fun pageSizePoints(wPx: Int, hPx: Int): Pair<Float, Float> {
        val landscape = wPx > hPx
        val long = max(wPx, hPx).toFloat()
        val short = min(wPx, hPx).toFloat()
        val ratio = long / short
        val (s, l) = when {
            abs(ratio - A4_RATIO) < 0.05f -> 595.28f to 841.89f
            abs(ratio - LETTER_RATIO) < 0.03f -> 612f to 792f
            else -> 841.89f / ratio to 841.89f
        }
        return if (landscape) l to s else s to l
    }

    /** Number of colour components from the first SOF marker, 3 if unknown. */
    fun jpegComponents(b: ByteArray): Int {
        var i = 2
        while (i + 9 < b.size) {
            if (b[i].toInt() and 0xFF != 0xFF) { i++; continue }
            val marker = b[i + 1].toInt() and 0xFF
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return b[i + 9].toInt() and 0xFF
            }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            i += 2 + len
        }
        return 3
    }

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    private fun utf16Hex(s: String): String {
        val sb = StringBuilder("<FEFF")
        for (ch in s) sb.append(String.format(Locale.ROOT, "%04X", ch.code))
        return sb.append('>').toString()
    }

    private fun pdfDate(): String {
        val f = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "D:${f.format(Date())}Z"
    }

    private const val A4_RATIO = 1.4142f
    private const val LETTER_RATIO = 1.2941f

    private class CountingStream(private val out: OutputStream) {
        var count = 0L
            private set
        fun write(b: ByteArray) { out.write(b); count += b.size }
        fun ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
        fun flush() = out.flush()
    }
}
