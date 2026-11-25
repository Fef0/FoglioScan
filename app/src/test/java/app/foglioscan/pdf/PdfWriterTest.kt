package app.foglioscan.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfWriterTest {

    /** Smallest byte sequence our JPEG parser treats as a JPEG with [components] channels. */
    private fun fakeJpeg(components: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),                         // SOI
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11,             // SOF0, length 17
        0x08, 0x00, 0x10, 0x00, 0x10, components.toByte(),    // precision, h, w, components
        0, 0, 0, 0, 0, 0, 0, 0, 0,
        0xFF.toByte(), 0xD9.toByte(),                         // EOI
    )

    private fun write(pages: List<PdfImage>): String {
        val out = ByteArrayOutputStream()
        PdfWriter.write(out, pages.size, "Tést ✓") { pages[it] }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    @Test
    fun pageSizesFitAndCentreTheScan() {
        // A receipt (1:3) on A4: full height, centred horizontally.
        val a4 = PdfWriter.layout(1000, 3000, PageSize.A4)
        assertEquals(595.28f, a4.pw, 0.01f); assertEquals(841.89f, a4.ph, 0.01f)
        assertEquals(841.89f, a4.h, 0.01f); assertEquals(280.63f, a4.w, 0.01f)
        assertEquals((595.28f - 280.63f) / 2, a4.x, 0.01f); assertEquals(0f, a4.y, 0.01f)
        // Landscape scans get a landscape page.
        val letter = PdfWriter.layout(3300, 2550, PageSize.LETTER)
        assertEquals(792f, letter.pw, 0.01f); assertEquals(612f, letter.ph, 0.01f)
        // Original: the scan's own shape, never snapped (an almost-A4 page stays as it is).
        val original = PdfWriter.layout(2480, 3600, PageSize.ORIGINAL)
        assertEquals(3600f / 2480f, original.ph / original.pw, 0.001f)
        assertEquals(original.pw, original.w, 0.01f)
        // Auto keeps the previous behaviour.
        val auto = PdfWriter.layout(2480, 3600, PageSize.AUTO)
        assertEquals(PdfWriter.pageSizePoints(2480, 3600), auto.pw to auto.ph)
    }

    @Test
    fun theTextLayerMovesWithTheScan() {
        val text = app.foglioscan.ocr.PageText(1000, 3000, listOf(
            app.foglioscan.ocr.TextLine("Total", 0, floatArrayOf(0f, 0f, 500f, 0f, 500f, 60f, 0f, 60f)),
        ))
        val out = ByteArrayOutputStream()
        PdfWriter.write(out, 1, "r", text = { text }, pageSize = PageSize.A4) { PdfImage.Jpeg(fakeJpeg(3), 1000, 3000) }
        val pdf = String(out.toByteArray(), Charsets.ISO_8859_1)
        val x = Regex("q 280\\.63 0 0 841\\.89 (157\\.3\\d) 0\\.00 cm /Im0 Do Q").find(pdf)!!.groupValues[1]
        assertTrue("text layer translated like the image", pdf.contains("q 1 0 0 1 $x 0.00 cm\n"))
    }

    @Test
    fun xrefOffsetsPointAtTheirObjects() {
        val pdf = write(
            listOf(
                PdfImage.Jpeg(fakeJpeg(3), 2480, 3508),
                PdfImage.Bilevel(byteArrayOf(1, 2, 3), 1700, 2200),
                PdfImage.Jpeg(fakeJpeg(1), 3508, 2480),
            ),
        )
        assertTrue(pdf.startsWith("%PDF-1.7\n"))
        assertTrue(pdf.trimEnd().endsWith("%%EOF"))

        val startxref = Regex("startxref\n(\\d+)\n").find(pdf)!!.groupValues[1].toInt()
        assertTrue(pdf.startsWith("xref\n", startxref))
        val count = Regex("xref\n0 (\\d+)\n").find(pdf, startxref)!!.groupValues[1].toInt()
        assertEquals(3 + 3 * 3 + 3 + 1, count) // + shared font, XMP, ICC
        val entries = Regex("(\\d{10}) 00000 n \n").findAll(pdf, startxref).map { it.groupValues[1].toInt() }.toList()
        assertEquals(count - 1, entries.size)
        entries.forEachIndexed { i, offset ->
            assertTrue("object ${i + 1} at $offset", pdf.startsWith("${i + 1} 0 obj\n", offset))
        }
        assertTrue(pdf.contains("/Count 3"))
        assertTrue(pdf.contains("/Size $count"))
    }

    @Test
    fun imagesUseTheRightEncodings() {
        val pdf = write(
            listOf(
                PdfImage.Jpeg(fakeJpeg(3), 100, 141),
                PdfImage.Jpeg(fakeJpeg(1), 100, 141),
                PdfImage.Bilevel(byteArrayOf(9, 9), 100, 141),
                PdfImage.Bilevel(byteArrayOf(7, 7, 7), 100, 141, PdfImage.Bilevel.Encoding.FLATE),
            ),
        )
        assertTrue(pdf.contains("/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"))
        assertTrue(pdf.contains("/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /DCTDecode"))
        assertTrue(pdf.contains("/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns 100 /Rows 141 >> /Length 2"))
        assertTrue(pdf.contains("/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode /Length 3"))
    }

    @Test
    fun streamLengthsMatchTheData() {
        val jpeg = fakeJpeg(3)
        val pdf = write(listOf(PdfImage.Jpeg(jpeg, 10, 14)))
        val m = Regex("/DCTDecode /Length (\\d+) >>\nstream\n").find(pdf)!!
        assertEquals(jpeg.size, m.groupValues[1].toInt())
        assertTrue(pdf.startsWith("\nendstream", m.range.last + 1 + jpeg.size))
    }

    @Test
    fun pageSizesSnapToPaperFormats() {
        val (aw, ah) = PdfWriter.pageSizePoints(2480, 3508)
        assertEquals(595.28f, aw, 0.01f); assertEquals(841.89f, ah, 0.01f)
        val (lw, lh) = PdfWriter.pageSizePoints(2550, 3300)
        assertEquals(612f, lw, 0.01f); assertEquals(792f, lh, 0.01f)
        val (landW, landH) = PdfWriter.pageSizePoints(3508, 2480)
        assertTrue(landW > landH)
        val (rw, rh) = PdfWriter.pageSizePoints(1000, 3000) // receipt: keeps its shape
        assertEquals(3f, rh / rw, 0.01f)
    }

    @Test
    fun jpegComponentsAreDetected() {
        assertEquals(3, PdfWriter.jpegComponents(fakeJpeg(3)))
        assertEquals(1, PdfWriter.jpegComponents(fakeJpeg(1)))
        assertEquals(3, PdfWriter.jpegComponents(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    @Test
    fun manyPagesStreamWithoutHoldingThemInMemory() {
        var produced = 0
        val out = object : java.io.OutputStream() {
            var size = 0L
            override fun write(b: Int) { size++ }
            override fun write(b: ByteArray, off: Int, len: Int) { size += len }
        }
        val img = PdfImage.Jpeg(ByteArray(200_000).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }, 2480, 3508)
        PdfWriter.write(out, 1000, "big") { produced++; img }
        assertEquals(1000, produced)
        assertTrue(out.size > 200_000L * 1000)
    }

    @Test
    fun bilevelRowPacking() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val row = IntArray(10) { if (it % 2 == 0) white else black }
        val packed = ByteArray(2)
        PdfExporter.packBilevelRow(row, packed)
        assertEquals(0b10101010.toByte(), packed[0])
        assertEquals(0b10000000.toByte(), packed[1]) // x=8 white, x=9 black, padding 0
    }
}
