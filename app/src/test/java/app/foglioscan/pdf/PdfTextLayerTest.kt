package app.foglioscan.pdf

import app.foglioscan.ocr.PageText
import app.foglioscan.ocr.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfTextLayerTest {

    private fun line(text: String, x0: Float, y0: Float, x1: Float, y1: Float, block: Int = 0) =
        TextLine(text, block, floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))

    @Test
    fun textIsInvisibleAndPlacedOverItsLine() {
        // 1000×1000 px page on a 500×500 pt page: a line from (100,200) to (600,250) px.
        val text = PageText(1000, 1000, listOf(line("Hello", 100f, 200f, 600f, 250f)))
        val c = PdfTextLayer.content(text, 500f, 500f)
        assertTrue(c.startsWith("BT 3 Tr\n"))
        assertTrue(c.trimEnd().endsWith("ET"))
        val m = Regex("""/F1 ([\d.]+) Tf ([\d.]+) Tz 1 0 -?0 1 ([\d.]+) ([\d.]+) Tm \(Hello\) Tj""").find(c)!!
        val (size, tz, x, y) = m.destructured
        // Box height 25 pt → font size ≈ 25 / 0.93.
        assertEquals(26.88f, size.toFloat(), 0.05f)
        // Baseline: bottom of the box (y = 500 - 125 = 375 pt) raised by the descent.
        assertEquals(50f, x.toFloat(), 0.01f)
        assertEquals(375f + 0.21f * 26.88f, y.toFloat(), 0.05f)
        // Stretched so the run is exactly the box's 250 pt wide.
        val units = 722 + 556 + 222 + 222 + 556
        assertEquals(250f, units / 1000f * size.toFloat() * tz.toFloat() / 100f, 0.2f)
    }

    @Test
    fun rotatedLinesFollowTheirBaseline() {
        // Baseline going up-right at 45° in PDF space (y down in image space).
        val l = TextLine("ab", 0, floatArrayOf(10f, 90f, 90f, 10f, 100f, 20f, 20f, 100f))
        val c = PdfTextLayer.content(PageText(200, 200, listOf(l)), 200f, 200f)
        val m = Regex("""Tz ([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) """).find(c)!!
        val (cos, sin, nsin, cos2) = m.destructured.toList().map { it.toFloat() }
        assertEquals(0.7071f, cos, 0.001f); assertEquals(0.7071f, sin, 0.001f)
        assertEquals(-sin, nsin, 0.001f); assertEquals(cos, cos2, 0.001f)
    }

    @Test
    fun stringsAreEscapedAndWinAnsiEncoded() {
        assertEquals("perché € “ok” – ?", PdfTextLayer.encode("perché € “ok” – ✓").let { s ->
            // Map back from WinAnsi bytes for readability.
            s.replace('\u0080', '€').replace('\u0093', '“').replace('\u0094', '”').replace('\u0096', '–')
        })
        assertEquals("Citta", PdfTextLayer.encode("Citta"))
        val c = PdfTextLayer.content(PageText(100, 100, listOf(line("a(b)\\c", 0f, 0f, 90f, 10f))), 100f, 100f)
        assertTrue(c, c.contains("""(a\(b\)\\c) Tj"""))
        assertTrue(c.all { it.code < 256 })
    }

    @Test
    fun plainTextKeepsLinesAndSeparatesBlocks() {
        val t = PageText(10, 10, listOf(line("a", 0f, 0f, 1f, 1f, 0), line("b", 0f, 0f, 1f, 1f, 0), line("c", 0f, 0f, 1f, 1f, 1)))
        assertEquals("a\nb\n\nc", t.plain)
    }

    @Test
    fun pdfWithTextStaysWellFormed() {
        val out = ByteArrayOutputStream()
        val text = PageText(2480, 3508, listOf(line("Fattura n. 42 – €1.234,00", 200f, 300f, 1800f, 360f)))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        PdfWriter.write(out, 2, "t", text = { if (it == 0) text else null }) { PdfImage.Jpeg(jpeg, 2480, 3508) }
        val pdf = String(out.toByteArray(), Charsets.ISO_8859_1)
        // Font object is the last one, referenced by every page.
        assertTrue(pdf.contains("10 0 obj\n${PdfTextLayer.FONT_DICT}"))
        assertEquals(2, Regex("/Font << /F1 10 0 R >>").findAll(pdf).count())
        // Content stream lengths match.
        Regex("<< /Length (\\d+) >>\nstream\n").findAll(pdf).forEach { m ->
            val start = m.range.last + 1
            val len = m.groupValues[1].toInt()
            assertTrue(pdf.startsWith("\nendstream", start + len))
        }
        assertTrue(pdf.contains("3 Tr"))
        val startxref = Regex("startxref\n(\\d+)\n").find(pdf)!!.groupValues[1].toInt()
        Regex("(\\d{10}) 00000 n \n").findAll(pdf, startxref).forEachIndexed { i, e ->
            assertTrue(pdf.startsWith("${i + 1} 0 obj\n", e.groupValues[1].toInt()))
        }
    }
}
