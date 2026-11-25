package app.foglioscan.pdf

import app.foglioscan.ocr.PageText
import app.foglioscan.ocr.TextLine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Writes realistic sample PDFs to build/pdfa (real JPEG and G4 pages, text layer, every page size)
 * for validation with veraPDF (see README), and checks the PDF/A identification is in place.
 */
class PdfaSamplesTest {

    private fun jpeg(w: Int, h: Int, gray: Boolean): ByteArray {
        val img = BufferedImage(w, h, if (gray) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(250, 248, 240); g.fillRect(0, 0, w, h)
        g.color = Color(30, 40, 120); g.font = g.font.deriveFont(40f); g.drawString("Fattura 2026 — totale 1.234,56 €", 60, 120)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun g4Page(): PdfImage.Bilevel {
        val img = ImageIO.read(File(javaClass.classLoader!!.getResource("g4/text.png")!!.toURI()))
        val data = CcittG4.encode(img.width, img.height) { y, packed ->
            packed.fill(0)
            for (x in 0 until img.width) if (img.getRGB(x, y) and 0xFF > 127) packed[x shr 3] = (packed[x shr 3].toInt() or (0x80 ushr (x and 7))).toByte()
        }
        return PdfImage.Bilevel(data, img.width, img.height)
    }

    private val text = PageText(
        1240, 1754,
        listOf(
            TextLine("Fattura 2026 — totale 1.234,56 €", 0, floatArrayOf(60f, 80f, 900f, 80f, 900f, 130f, 60f, 130f)),
            TextLine("Città: Perugia, «consegna» entro venerdì", 1, floatArrayOf(60f, 200f, 1000f, 210f, 1000f, 255f, 60f, 245f), angle = 1f),
        ),
    )

    @Test
    fun writesSamples() {
        val out = File("build/pdfa").apply { deleteRecursively(); mkdirs() }
        val pages = listOf(
            PdfImage.Jpeg(jpeg(1240, 1754, gray = false), 1240, 1754),
            PdfImage.Jpeg(jpeg(1240, 1754, gray = true), 1240, 1754),
            g4Page(),
            PdfImage.Jpeg(jpeg(600, 1800, gray = false), 600, 1800), // a receipt
        )
        for (size in PageSize.entries) {
            val f = File(out, "sample-${size.name.lowercase()}.pdf")
            f.outputStream().use { o ->
                PdfWriter.write(o, pages.size, "Scansione & <prova> \"citazione\" ✓", text = { if (it == 0) text else null }, pageSize = size) { pages[it] }
            }
            val s = f.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(s.contains("<pdfaid:part>2</pdfaid:part>"))
            assertTrue(s.contains("/OutputIntents"))
            assertTrue("BOM in the XMP header", f.readBytes().toString(Charsets.UTF_8).contains("begin=\"﻿\""))
        }
    }
}
