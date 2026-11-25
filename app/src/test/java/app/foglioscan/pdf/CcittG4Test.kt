package app.foglioscan.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Checked against libtiff (tools/testdata/make_g4_fixtures.py): the `.g4` fixtures were encoded
 * by libtiff, and the encoder's output for each image is written to build/g4 so the same script's
 * `--check` mode can decode it with libtiff.
 */
class CcittG4Test {

    private class Bitmap1(val w: Int, val h: Int, val rows: Array<ByteArray>)

    private fun resource(name: String) = File(javaClass.classLoader!!.getResource("g4/$name")!!.toURI())

    private fun png(name: String): Bitmap1 {
        val img = ImageIO.read(resource("$name.png"))
        val rows = Array(img.height) { y ->
            ByteArray((img.width + 7) / 8).also { packed ->
                for (x in 0 until img.width) if (img.getRGB(x, y) and 0xFF > 127) {
                    packed[x shr 3] = (packed[x shr 3].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return Bitmap1(img.width, img.height, rows)
    }

    private fun decode(data: ByteArray, w: Int, h: Int): Array<ByteArray> {
        val rows = arrayOfNulls<ByteArray>(h)
        CcittG4.decode(data, w, h) { y, packed -> rows[y] = packed.copyOf() }
        return rows.requireNoNulls()
    }

    private val names = listOf("runs", "text", "noise")

    @Test
    fun decodesLibtiffOutput() {
        for (name in names) {
            val ref = png(name)
            val got = decode(resource("$name.g4").readBytes(), ref.w, ref.h)
            for (y in 0 until ref.h) assertArrayEquals("$name row $y", ref.rows[y], got[y])
        }
    }

    @Test
    fun roundTripsAndIsSmallerThanLibtiffOrClose() {
        val outDir = File("build/g4").apply { mkdirs() }
        for (name in names) {
            val ref = png(name)
            val data = CcittG4.encode(ref.w, ref.h) { y, packed -> ref.rows[y].copyInto(packed) }
            File(outDir, "$name.g4").writeBytes(data)
            val back = decode(data, ref.w, ref.h)
            for (y in 0 until ref.h) assertArrayEquals("$name row $y", ref.rows[y], back[y])
            // Same algorithm as libtiff: sizes should match within a few bytes (EOFB, padding).
            val lib = resource("$name.g4").length()
            assertTrue("$name: ${data.size} bytes vs libtiff $lib", kotlin.math.abs(data.size - lib) <= 8)
        }
    }

    @Test
    fun codeTablesArePrefixFree() {
        for (table in listOf(CcittG4.WHITE_CODES, CcittG4.BLACK_CODES)) {
            val codes = table.values.map { (c, l) -> Integer.toBinaryString(c).padStart(l, '0') }
            for (a in codes) for (b in codes) if (a != b) assertTrue("$a is a prefix of $b", !b.startsWith(a))
        }
    }
}
