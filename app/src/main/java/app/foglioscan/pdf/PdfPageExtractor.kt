package app.foglioscan.pdf

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the page images back out of a PDF written by FoglioScan ([PdfWriter]), byte for byte:
 * JPEG pages are returned exactly as embedded and 1-bit pages as their G4 (or Flate) data, so reopening a
 * document to add pages loses no quality. Objects are located through the cross-reference table
 * and read one at a time, so memory does not depend on the document's length.
 *
 * Any other PDF (or one modified by another app) is not ours to parse: [pageCount] and
 * [forEachPage] return null / false, and callers rasterize it with the platform renderer.
 */
class PdfPageExtractor private constructor(private val raf: RandomAccessFile, private val offsets: LongArray) : AutoCloseable {

    /** Page object numbers and their image object numbers, in page order. */
    private lateinit var pages: List<Int>
    private lateinit var images: List<Int>

    val pageCount: Int get() = images.size

    /** Reads page [index]'s image. */
    fun page(index: Int): PdfImage {
        val num = images[index]
        val head = header(num)
        val dict = head.substringBefore("stream\n")
        fun int(key: String) = Regex("/$key (\\d+)").find(dict)?.groupValues?.get(1)?.toInt() ?: error("no /$key in $num")
        val w = int("Width"); val h = int("Height"); val bits = int("BitsPerComponent"); val len = int("Length")
        val data = ByteArray(len)
        raf.seek(offsets[num] + head.indexOf("stream\n") + "stream\n".length)
        raf.readFully(data)
        return when {
            "/DCTDecode" in dict && bits == 8 -> PdfImage.Jpeg(data, w, h)
            "/CCITTFaxDecode" in dict && bits == 1 -> PdfImage.Bilevel(data, w, h, PdfImage.Bilevel.Encoding.G4)
            "/FlateDecode" in dict && bits == 1 -> PdfImage.Bilevel(data, w, h, PdfImage.Bilevel.Encoding.FLATE)
            else -> error("unsupported image in object $num")
        }
    }

    /**
     * The recognized text of page [index], one line per line of the invisible text layer
     * ([PdfTextLayer]), or "" if the page has none.
     */
    fun pageText(index: Int): String {
        val page = header(pages[index]).substringBefore("endobj")
        val contents = Regex("/Contents (\\d+) 0 R").find(page)?.groupValues?.get(1)?.toInt() ?: return ""
        val head = header(contents)
        val len = Regex("/Length (\\d+)").find(head.substringBefore("stream\n"))?.groupValues?.get(1)?.toInt() ?: return ""
        val data = ByteArray(len)
        raf.seek(offsets[contents] + head.indexOf("stream\n") + "stream\n".length)
        raf.readFully(data)
        return textLines(String(data, Charsets.ISO_8859_1)).joinToString("\n")
    }

    override fun close() = raf.close()

    /** The first bytes of object [num] (its dictionary and the start of its stream). */
    private fun header(num: Int, max: Int = 4096): String {
        val off = offsets.getOrNull(num)?.takeIf { it > 0 } ?: error("object $num not in xref")
        raf.seek(off)
        val buf = ByteArray(minOf(max.toLong(), raf.length() - off).toInt())
        raf.readFully(buf)
        val s = String(buf, Charsets.ISO_8859_1)
        check(s.startsWith("$num 0 obj")) { "object $num not at its xref offset" }
        return s
    }

    companion object {
        /** The strings shown with `Tj` in a content stream, decoded from WinAnsi. */
        internal fun textLines(content: String): List<String> {
            val lines = ArrayList<String>()
            var i = 0
            while (i < content.length) {
                if (content[i] != '(') { i++; continue }
                // A literal string: balanced parentheses, backslash escapes.
                val sb = StringBuilder()
                var depth = 1
                i++
                while (i < content.length && depth > 0) {
                    val c = content[i]
                    when {
                        c == '\\' && i + 1 < content.length -> {
                            val n = content[i + 1]
                            when (n) {
                                'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                                'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                                in '0'..'7' -> {
                                    var j = i + 1; var v = 0
                                    while (j < content.length && j < i + 4 && content[j] in '0'..'7') { v = v * 8 + (content[j] - '0'); j++ }
                                    sb.append((v and 0xFF).toChar()); i = j - 2
                                }
                                '\n' -> Unit
                                else -> sb.append(n)
                            }
                            i += 2
                        }
                        c == '(' -> { depth++; sb.append(c); i++ }
                        c == ')' -> { depth--; if (depth > 0) sb.append(c); i++ }
                        else -> { sb.append(c); i++ }
                    }
                }
                var j = i
                while (j < content.length && content[j].isWhitespace()) j++
                if (content.startsWith("Tj", j)) lines += PdfTextLayer.decode(sb.toString())
            }
            return lines
        }

        /** Opens [file] if it is a FoglioScan PDF whose pages can be extracted, else null. */
        fun open(file: File): PdfPageExtractor? {
            val raf = RandomAccessFile(file, "r")
            return try {
                val x = PdfPageExtractor(raf, readXref(raf) ?: error("no xref table"))
                check("/Producer (FoglioScan)" in x.header(3)) { "not written by FoglioScan" }
                val pages = x.header(2).substringBefore("endobj")
                val kids = Regex("(\\d+) 0 R").findAll(pages.substringAfter("/Kids [").substringBefore("]"))
                    .map { it.groupValues[1].toInt() }.toList()
                x.pages = kids
                x.images = kids.map { k ->
                    Regex("/Im0 (\\d+) 0 R").find(x.header(k).substringBefore("endobj"))?.groupValues?.get(1)?.toInt()
                        ?: error("page $k has no image")
                }
                // Validate every image up front: all-or-nothing.
                if (x.images.isEmpty()) error("no pages")
                x.images.forEach { n -> x.header(n).let { check("/Subtype /Image" in it) } }
                x
            } catch (_: Exception) {
                raf.close()
                null
            }
        }

        /** Object offsets indexed by object number (0 unused), from a classic xref table. */
        private fun readXref(raf: RandomAccessFile): LongArray? {
            val tailLen = minOf(1024L, raf.length()).toInt()
            raf.seek(raf.length() - tailLen)
            val tail = ByteArray(tailLen).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            val start = Regex("startxref\\s+(\\d+)\\s+%%EOF").findAll(tail).lastOrNull()?.groupValues?.get(1)?.toLong() ?: return null
            raf.seek(start)
            val first = ByteArray(64).also { raf.read(it) }.toString(Charsets.ISO_8859_1)
            val m = Regex("^xref\\s+0 (\\d+)\\s*\\n").find(first) ?: return null
            val count = m.groupValues[1].toInt()
            raf.seek(start + m.range.last + 1)
            val table = ByteArray(count * 20).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            return LongArray(count) { i -> table.substring(i * 20, i * 20 + 10).toLong() }
        }
    }
}
