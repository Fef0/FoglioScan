package app.foglioscan.pdf

import app.foglioscan.ocr.PageText
import app.foglioscan.ocr.TextLine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class PdfPageExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun jpeg(seed: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + Random(seed).nextBytes(500 + seed * 37) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test
    fun ownPdfPagesComeBackByteForByte() {
        val pages = listOf(
            PdfImage.Jpeg(jpeg(1), 2480, 3508),
            PdfImage.Bilevel(Random(2).nextBytes(321), 1700, 2200),
            PdfImage.Jpeg(jpeg(3), 3508, 2480),
        )
        val text = PageText(2480, 3508, listOf(TextLine("Stream data (with) endobj and /Im0 9 0 R inside", 0, floatArrayOf(0f, 0f, 900f, 0f, 900f, 40f, 0f, 40f))))
        val file = tmp.newFile("doc.pdf")
        file.outputStream().use { PdfWriter.write(it, pages.size, "Doc", text = { text }) { i -> pages[i] } }

        PdfPageExtractor.open(file)!!.use { x ->
            assertEquals(3, x.pageCount)
            pages.forEachIndexed { i, expected ->
                val got = x.page(i)
                assertEquals(expected::class, got::class)
                assertEquals(expected.width, got.width); assertEquals(expected.height, got.height)
                val (a, b) = when (expected) {
                    is PdfImage.Jpeg -> expected.bytes to (got as PdfImage.Jpeg).bytes
                    is PdfImage.Bilevel -> expected.deflated to (got as PdfImage.Bilevel).deflated
                }
                assertArrayEquals("page $i", a, b)
            }
        }
    }

    @Test
    fun otherPdfsAreNotParsed() {
        val notOurs = tmp.newFile("other.pdf")
        notOurs.outputStream().use { PdfWriter.write(it, 1, "x") { PdfImage.Jpeg(jpeg(1), 10, 10) } }
        // Same structure but another producer (e.g. re-saved by an editor).
        notOurs.writeBytes(notOurs.readBytes().toString(Charsets.ISO_8859_1).replace("/Producer (FoglioScan)", "/Producer (Acrobat___)").toByteArray(Charsets.ISO_8859_1))
        assertNull(PdfPageExtractor.open(notOurs))

        val garbage = tmp.newFile("garbage.pdf").apply { writeText("%PDF-1.4\nnot really a pdf\n%%EOF\n") }
        assertNull(PdfPageExtractor.open(garbage))
        val truncated = tmp.newFile("truncated.pdf")
        truncated.outputStream().use { PdfWriter.write(it, 2, "t") { PdfImage.Jpeg(jpeg(it), 10, 10) } }
        truncated.writeBytes(truncated.readBytes().copyOf((truncated.length() * 0.6).toInt()))
        assertNull(PdfPageExtractor.open(truncated))
    }
}
