package app.foglioscan.pdf

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the page images back out of a PDF written by FoglioScan ([PdfWriter]), byte for byte:
 * JPEG pages are returned exactly as embedded and 1-bit pages as their Flate data, so reopening a
 * document to add pages loses no quality. Objects are located through the cross-reference table
 * and read one at a time, so memory does not depend on the document's length.
 *
 * Any other PDF (or one modified by another app) is not ours to parse: [pageCount] and
 * [forEachPage] return null / false, and callers rasterize it with the platform renderer.
 */
class PdfPageExtractor private constructor(private val raf: RandomAccessFile, private val offsets: LongArray) : AutoCloseable {

    /** Page image object numbers, in page order. */
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
            "/FlateDecode" in dict && bits == 1 -> PdfImage.Bilevel(data, w, h)
            else -> error("unsupported image in object $num")
        }
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
        /** Opens [file] if it is a FoglioScan PDF whose pages can be extracted, else null. */
        fun open(file: File): PdfPageExtractor? {
            val raf = RandomAccessFile(file, "r")
            return try {
                val x = PdfPageExtractor(raf, readXref(raf) ?: error("no xref table"))
                check("/Producer (FoglioScan)" in x.header(3)) { "not written by FoglioScan" }
                val pages = x.header(2).substringBefore("endobj")
                val kids = Regex("(\\d+) 0 R").findAll(pages.substringAfter("/Kids [").substringBefore("]"))
                    .map { it.groupValues[1].toInt() }.toList()
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
