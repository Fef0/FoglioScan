package app.foglioscan.pdf

import app.foglioscan.ocr.PageText
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Physical size of the PDF pages. */
@kotlinx.serialization.Serializable
enum class PageSize {
    /** The scan's proportions, snapped to A4 or US Letter when within a few percent. */
    AUTO,
    /** Always A4, in the scan's orientation; the scan is fitted and centred. */
    A4,
    /** Always US Letter, in the scan's orientation; the scan is fitted and centred. */
    LETTER,
    /** Exactly the scan's proportions (never snapped), A4-sized long side. */
    ORIGINAL,
}

/** Where the page image goes on the page, in points. */
data class PageLayout(val pw: Float, val ph: Float, val x: Float, val y: Float, val w: Float, val h: Float)

/** An image placed on a PDF page (full-page unless the page size says otherwise). */
sealed interface PdfImage {
    val width: Int
    val height: Int

    /** JPEG passed through untouched (DCTDecode). */
    class Jpeg(val bytes: ByteArray, override val width: Int, override val height: Int) : PdfImage

    /**
     * 1-bit black & white, rows packed MSB-first (1 = white): the format professional scanners
     * use for text, tiny files and perfectly crisp edges. Written as CCITT Group 4 ([CcittG4]);
     * Flate is read back from documents saved by earlier versions.
     */
    class Bilevel(
        val data: ByteArray,
        override val width: Int,
        override val height: Int,
        val encoding: Encoding = Encoding.G4,
    ) : PdfImage {
        enum class Encoding { G4, FLATE }
    }
}

/**
 * Minimal PDF 1.7 writer. Pages are produced one at a time by [write]'s `page` callback and
 * written straight to the stream, so memory use is independent of the number of pages.
 *
 * Pages with recognized text get an invisible text layer ([PdfTextLayer]): the PDF is
 * searchable and its text selectable.
 *
 * Output is PDF/A-2u (ISO 19005-2, archival with Unicode text): XMP metadata matching the document
 * information, an sRGB output intent, and no unembedded fonts except the invisible text layer's,
 * which the standard exempts (text rendering mode 3). Validated with veraPDF (see README).
 */
object PdfWriter {

    fun write(
        out: OutputStream,
        pageCount: Int,
        title: String,
        text: (index: Int) -> PageText? = { null },
        pageSize: PageSize = PageSize.AUTO,
        page: (index: Int) -> PdfImage,
    ) {
        require(pageCount > 0)
        val w = CountingStream(out)
        // 1: catalog, 2: page tree, 3: info; per page: page, content, image; then font, XMP
        // metadata, ICC profile.
        val fontObj = 4 + pageCount * 3
        val metadataObj = fontObj + 1
        val iccObj = fontObj + 2
        val offsets = LongArray(iccObj)
        val now = Date()

        fun beginObj(num: Int) {
            offsets[num - 1] = w.count
            w.ascii("$num 0 obj\n")
        }

        w.ascii("%PDF-1.7\n")
        w.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        beginObj(1)
        w.ascii(
            "<< /Type /Catalog /Pages 2 0 R /Metadata $metadataObj 0 R /OutputIntents [<< /Type /OutputIntent " +
                "/S /GTS_PDFA1 /OutputConditionIdentifier (sRGB IEC61966-2.1) /Info (sRGB IEC61966-2.1) " +
                "/DestOutputProfile $iccObj 0 R >>] >>\nendobj\n",
        )
        beginObj(2)
        w.ascii("<< /Type /Pages /Kids [")
        for (i in 0 until pageCount) w.ascii(if (i == 0) "${4 + i * 3} 0 R" else " ${4 + i * 3} 0 R")
        w.ascii("] /Count $pageCount >>\nendobj\n")
        beginObj(3)
        val date = pdfDate(now)
        w.ascii("<< /Title ${utf16Hex(title)} /Producer (FoglioScan) /Creator (FoglioScan) /CreationDate ($date) /ModDate ($date) >>\nendobj\n")

        for (index in 0 until pageCount) {
            val image = page(index)
            val pageObj = 4 + index * 3
            val l = layout(image.width, image.height, pageSize)
            val pw = l.pw; val ph = l.ph
            val offset = l.x != 0f || l.y != 0f
            val content = "q ${fmt(l.w)} 0 0 ${fmt(l.h)} ${fmt(l.x)} ${fmt(l.y)} cm /Im0 Do Q\n" +
                (text(index)?.let { t ->
                    // The text layer covers the image wherever it sits on the page.
                    val layer = PdfTextLayer.content(t, l.w, l.h)
                    if (offset) "q 1 0 0 1 ${fmt(l.x)} ${fmt(l.y)} cm\n${layer}Q\n" else layer
                } ?: "")

            beginObj(pageObj)
            w.ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pw)} ${fmt(ph)}] " +
                    "/Resources << /XObject << /Im0 ${pageObj + 2} 0 R >> /Font << /${PdfTextLayer.FONT_RESOURCE} $fontObj 0 R >> " +
                    "/ProcSet [/PDF /Text /ImageC /ImageB] >> " +
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
                    val filter = when (image.encoding) {
                        PdfImage.Bilevel.Encoding.G4 ->
                            "/Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns ${image.width} /Rows ${image.height} >>"
                        PdfImage.Bilevel.Encoding.FLATE -> "/Filter /FlateDecode"
                    }
                    w.ascii(
                        "<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                            "/ColorSpace /DeviceGray /BitsPerComponent 1 $filter /Length ${image.data.size} >>\nstream\n",
                    )
                    w.write(image.data)
                }
            }
            w.ascii("\nendstream\nendobj\n")
        }
        beginObj(fontObj)
        w.ascii("${PdfTextLayer.FONT_DICT}\nendobj\n")
        val xmp = xmp(title, now).toByteArray(Charsets.UTF_8)
        beginObj(metadataObj)
        w.ascii("<< /Type /Metadata /Subtype /XML /Length ${xmp.size} >>\nstream\n")
        w.write(xmp)
        w.ascii("\nendstream\nendobj\n")
        beginObj(iccObj)
        w.ascii("<< /N 3 /Length ${SRGB_ICC.size} >>\nstream\n")
        w.write(SRGB_ICC)
        w.ascii("\nendstream\nendobj\n")

        val xref = w.count
        w.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) w.ascii(String.format(Locale.ROOT, "%010d 00000 n \n", o))
        val id = MessageDigest.getInstance("MD5").digest("$title${System.nanoTime()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        w.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R /ID [<$id> <$id>] >>\nstartxref\n$xref\n%%EOF\n")
        w.flush()
    }

    /** Page size and image placement (points) for a [wPx]×[hPx] image at [size]. */
    fun layout(wPx: Int, hPx: Int, size: PageSize): PageLayout {
        val landscape = wPx > hPx
        val aspect = max(wPx, hPx).toFloat() / min(wPx, hPx)
        val (short, long) = when (size) {
            PageSize.AUTO -> {
                val (w, h) = pageSizePoints(wPx, hPx)
                return PageLayout(w, h, 0f, 0f, w, h)
            }
            PageSize.ORIGINAL -> 841.89f / aspect to 841.89f
            PageSize.A4 -> 595.28f to 841.89f
            PageSize.LETTER -> 612f to 792f
        }
        val pw = if (landscape) long else short
        val ph = if (landscape) short else long
        if (size == PageSize.ORIGINAL) return PageLayout(pw, ph, 0f, 0f, pw, ph)
        // Fit the image, centred.
        val s = min(pw / wPx, ph / hPx)
        val w = wPx * s; val h = hPx * s
        return PageLayout(pw, ph, (pw - w) / 2, (ph - h) / 2, w, h)
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

    private fun pdfDate(d: Date): String {
        val f = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "D:${f.format(d)}Z"
    }

    /** XMP packet with the PDF/A identification and the same values as the info dictionary. */
    private fun xmp(title: String, d: Date): String {
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
        val t = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .filter { it == '\t' || it >= ' ' }
        return """<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
<pdfaid:part>2</pdfaid:part>
<pdfaid:conformance>U</pdfaid:conformance>
<dc:format>application/pdf</dc:format>
<dc:title><rdf:Alt><rdf:li xml:lang="x-default">$t</rdf:li></rdf:Alt></dc:title>
<xmp:CreateDate>$date</xmp:CreateDate>
<xmp:ModifyDate>$date</xmp:ModifyDate>
<xmp:MetadataDate>$date</xmp:MetadataDate>
<xmp:CreatorTool>FoglioScan</xmp:CreatorTool>
<pdf:Producer>FoglioScan</pdf:Producer>
</rdf:Description>
</rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    /** sRGB ICC v2 profile for the PDF/A output intent (tools/pdf/make_srgb_icc.py). */
    private val SRGB_ICC: ByteArray by lazy {
        PdfWriter::class.java.getResourceAsStream("/pdf/srgb.icc")!!.use { it.readBytes() }
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
