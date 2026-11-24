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
        assertEquals(3 + 3 * 3 + 1 + 1, count) // + shared font
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
            ),
        )
        assertTrue(pdf.contains("/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"))
        assertTrue(pdf.contains("/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /DCTDecode"))
        assertTrue(pdf.contains("/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode /Length 2"))
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
