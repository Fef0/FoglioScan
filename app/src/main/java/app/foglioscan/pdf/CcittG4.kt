package app.foglioscan.pdf

import java.io.ByteArrayOutputStream

/**
 * CCITT Group 4 (ITU-T T.6) codec for 1-bit pages, as used by `/CCITTFaxDecode` with `/K -1`.
 * Pure 2D coding against the previous row, the format document scanners use for black & white:
 * text pages come out several times smaller than Flate.
 *
 * Rows are given and returned as packed bytes, MSB first, 1 = white (the PDF DeviceGray 1-bit
 * convention, so the default `/BlackIs1 false` applies). The encoder ends the data with EOFB.
 */
object CcittG4 {

    /** Encodes [height] rows of [width] pixels; [row] fills a packed row (1 = white). */
    fun encode(width: Int, height: Int, row: (y: Int, packed: ByteArray) -> Unit): ByteArray {
        val out = BitWriter()
        val packed = ByteArray((width + 7) / 8)
        var ref = BooleanArray(width) // black flags; the line above the first is all white
        var cur = BooleanArray(width)
        for (y in 0 until height) {
            row(y, packed)
            for (x in 0 until width) cur[x] = (packed[x shr 3].toInt() and (0x80 ushr (x and 7))) == 0
            encodeLine(cur, ref, width, out)
            val t = ref; ref = cur; cur = t
        }
        out.bits(1, 12); out.bits(1, 12) // EOFB
        return out.toByteArray()
    }

    private fun encodeLine(cur: BooleanArray, ref: BooleanArray, w: Int, out: BitWriter) {
        var a0 = -1
        var color = false // white
        while (a0 < w) {
            val start = a0 + 1
            val a1 = nextColor(cur, start, !color, w)
            val b1 = b1(ref, start, color, w)
            val b2 = nextColor(ref, b1 + 1, color, w)
            if (b2 < a1) {
                out.bits(0b0001, 4) // pass
                a0 = b2
            } else {
                val d = a1 - b1
                if (d in -3..3) {
                    val (code, len) = VERTICAL[d + 3]
                    out.bits(code, len)
                    a0 = a1
                    color = !color
                } else {
                    val a2 = nextColor(cur, a1 + 1, color, w)
                    out.bits(0b001, 3) // horizontal
                    run(out, a1 - maxOf(a0, 0), color)
                    run(out, a2 - a1, !color)
                    a0 = a2
                }
            }
        }
    }

    /** First position ≥ [from] whose pixel is [black] (as a colour), or [w]. */
    private fun nextColor(line: BooleanArray, from: Int, black: Boolean, w: Int): Int {
        var p = maxOf(from, 0)
        while (p < w && line[p] != black) p++
        return p
    }

    /** First changing element ≥ [from] on [ref] whose colour is the opposite of [color]. */
    private fun b1(ref: BooleanArray, from: Int, color: Boolean, w: Int): Int {
        var p = maxOf(from, 0)
        while (p < w) {
            val prev = if (p == 0) false else ref[p - 1]
            if (ref[p] != color && prev == color) return p
            p++
        }
        return w
    }

    private fun run(out: BitWriter, length: Int, black: Boolean) {
        var n = length
        while (n >= 2560) { emit(out, 2560, black); n -= 2560 }
        if (n >= 64) { emit(out, n / 64 * 64, black); n %= 64 }
        emit(out, n, black)
    }

    private fun emit(out: BitWriter, value: Int, black: Boolean) {
        val code = (if (black) BLACK_CODES else WHITE_CODES)[value] ?: error("no code for $value")
        out.bits(code.first, code.second)
    }

    /**
     * Decodes [height] rows of [width] pixels; [onRow] receives each packed row (1 = white).
     * Throws on malformed data.
     */
    fun decode(data: ByteArray, width: Int, height: Int, onRow: (y: Int, packed: ByteArray) -> Unit) {
        val bits = BitReader(data)
        val packed = ByteArray((width + 7) / 8)
        var ref = BooleanArray(width)
        var cur = BooleanArray(width)
        for (y in 0 until height) {
            var a0 = -1
            var color = false
            while (a0 < width) {
                val start = a0 + 1
                when (val mode = readMode(bits)) {
                    MODE_PASS -> {
                        val b2 = nextColor(ref, b1(ref, start, color, width) + 1, color, width)
                        cur.fill(color, maxOf(a0, 0), b2)
                        a0 = b2
                    }
                    MODE_HORIZONTAL -> {
                        val from = maxOf(a0, 0)
                        val r1 = readRun(bits, color)
                        val r2 = readRun(bits, !color)
                        val a1 = minOf(from + r1, width)
                        val a2 = minOf(a1 + r2, width)
                        cur.fill(color, from, a1)
                        cur.fill(!color, a1, a2)
                        a0 = a2
                    }
                    else -> { // vertical, mode = offset
                        val a1 = (b1(ref, start, color, width) + mode).coerceIn(maxOf(a0, 0), width)
                        cur.fill(color, maxOf(a0, 0), a1)
                        a0 = a1
                        color = !color
                    }
                }
            }
            packed.fill(0)
            for (x in 0 until width) if (!cur[x]) packed[x shr 3] = (packed[x shr 3].toInt() or (0x80 ushr (x and 7))).toByte()
            onRow(y, packed)
            val t = ref; ref = cur; cur = t
        }
    }

    private const val MODE_PASS = 100
    private const val MODE_HORIZONTAL = 101

    private fun readMode(b: BitReader): Int {
        if (b.bit() == 1) return 0                         // 1        V0
        if (b.bit() == 1) return if (b.bit() == 1) 1 else -1 // 011 VR1, 010 VL1
        if (b.bit() == 1) return MODE_HORIZONTAL           // 001
        if (b.bit() == 1) return MODE_PASS                 // 0001
        if (b.bit() == 1) return if (b.bit() == 1) 2 else -2 // 000011 VR2, 000010 VL2
        if (b.bit() == 1) return if (b.bit() == 1) 3 else -3 // 0000011 VR3, 0000010 VL3
        error("unsupported G4 code (extension or EOFB inside the image)")
    }

    private fun readRun(b: BitReader, black: Boolean): Int {
        val table = if (black) BLACK_DECODE else WHITE_DECODE
        var total = 0
        while (true) {
            var code = 0; var len = 0
            var value: Int? = null
            while (value == null) {
                code = (code shl 1) or b.bit(); len++
                check(len <= 13) { "bad run code" }
                value = table[(len shl 16) or code]
            }
            total += value
            if (value < 64) return total
        }
    }

    private fun BooleanArray.fill(v: Boolean, from: Int, to: Int) { for (i in from until minOf(to, size)) this[i] = v }

    private class BitWriter {
        private val out = ByteArrayOutputStream(64 * 1024)
        private var acc = 0
        private var n = 0
        fun bits(code: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                acc = (acc shl 1) or ((code ushr i) and 1)
                if (++n == 8) { out.write(acc); acc = 0; n = 0 }
            }
        }
        fun toByteArray(): ByteArray {
            if (n > 0) { out.write(acc shl (8 - n)); acc = 0; n = 0 }
            return out.toByteArray()
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var pos = 0L
        fun bit(): Int {
            val i = (pos ushr 3).toInt()
            check(i < data.size) { "G4 data ended early" }
            val v = (data[i].toInt() ushr (7 - (pos and 7).toInt())) and 1
            pos++
            return v
        }
    }

    private val VERTICAL = arrayOf( // d = -3..3: (code, length)
        0b0000010 to 7, 0b000010 to 6, 0b010 to 3, 0b1 to 1, 0b011 to 3, 0b000011 to 6, 0b0000011 to 7,
    )

    private fun table(terminating: List<String>, makeup: List<String>): Map<Int, Pair<Int, Int>> {
        val m = HashMap<Int, Pair<Int, Int>>()
        terminating.forEachIndexed { i, s -> m[i] = s.toInt(2) to s.length }
        makeup.forEachIndexed { i, s -> m[(i + 1) * 64] = s.toInt(2) to s.length }
        EXTENDED.forEachIndexed { i, s -> m[1792 + i * 64] = s.toInt(2) to s.length }
        return m
    }

    private val EXTENDED = listOf(
        "00000001000", "00000001100", "00000001101", "000000010010", "000000010011", "000000010100",
        "000000010101", "000000010110", "000000010111", "000000011100", "000000011101", "000000011110",
        "000000011111",
    )

    internal val WHITE_CODES = table(
        listOf(
            "00110101", "000111", "0111", "1000", "1011", "1100", "1110", "1111", "10011", "10100",
            "00111", "01000", "001000", "000011", "110100", "110101", "101010", "101011", "0100111", "0001100",
            "0001000", "0010111", "0000011", "0000100", "0101000", "0101011", "0010011", "0100100", "0011000", "00000010",
            "00000011", "00011010", "00011011", "00010010", "00010011", "00010100", "00010101", "00010110", "00010111", "00101000",
            "00101001", "00101010", "00101011", "00101100", "00101101", "00000100", "00000101", "00001010", "00001011", "01010010",
            "01010011", "01010100", "01010101", "00100100", "00100101", "01011000", "01011001", "01011010", "01011011", "01001010",
            "01001011", "00110010", "00110011", "00110100",
        ),
        listOf(
            "11011", "10010", "010111", "0110111", "00110110", "00110111", "01100100", "01100101", "01101000", "01100111",
            "011001100", "011001101", "011010010", "011010011", "011010100", "011010101", "011010110", "011010111", "011011000", "011011001",
            "011011010", "011011011", "010011000", "010011001", "010011010", "011000", "010011011",
        ),
    )

    internal val BLACK_CODES = table(
        listOf(
            "0000110111", "010", "11", "10", "011", "0011", "0010", "00011", "000101", "000100",
            "0000100", "0000101", "0000111", "00000100", "00000111", "000011000", "0000010111", "0000011000", "0000001000", "00001100111",
            "00001101000", "00001101100", "00000110111", "00000101000", "00000010111", "00000011000", "000011001010", "000011001011", "000011001100", "000011001101",
            "000001101000", "000001101001", "000001101010", "000001101011", "000011010010", "000011010011", "000011010100", "000011010101", "000011010110", "000011010111",
            "000001101100", "000001101101", "000011011010", "000011011011", "000001010100", "000001010101", "000001010110", "000001010111", "000001100100", "000001100101",
            "000001010010", "000001010011", "000000100100", "000000110111", "000000111000", "000000100111", "000000101000", "000001011000", "000001011001", "000000101011",
            "000000101100", "000001011010", "000001100110", "000001100111",
        ),
        listOf(
            "0000001111", "000011001000", "000011001001", "000001011011", "000000110011", "000000110100", "000000110101", "0000001101100", "0000001101101", "0000001001010",
            "0000001001011", "0000001001100", "0000001001101", "0000001110010", "0000001110011", "0000001110100", "0000001110101", "0000001110110", "0000001110111", "0000001010010",
            "0000001010011", "0000001010100", "0000001010101", "0000001011010", "0000001011011", "0000001100100", "0000001100101",
        ),
    )

    private fun decodeTable(codes: Map<Int, Pair<Int, Int>>): Map<Int, Int> =
        codes.entries.associate { (value, c) -> ((c.second shl 16) or c.first) to value }

    private val WHITE_DECODE = decodeTable(WHITE_CODES)
    private val BLACK_DECODE = decodeTable(BLACK_CODES)
}
