package app.foglioscan.pdf

import app.foglioscan.ocr.PageText
import java.text.Normalizer
import java.util.Locale
import kotlin.math.hypot

/**
 * Invisible text layer that makes a scanned page searchable and selectable, as professional
 * scanning software does: each recognized line is drawn in text render mode 3 (no fill, no
 * stroke) exactly over its image, stretched to the line's width and rotated with it.
 *
 * Uses the standard Helvetica font with WinAnsiEncoding: nothing to embed, and every PDF viewer
 * maps it back to Unicode for copy & paste (covers English, Italian and Western European text).
 */
object PdfTextLayer {

    const val FONT_RESOURCE = "F1"
    const val FONT_DICT = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"

    /**
     * Content-stream operators for [text] on a page of [pw]×[ph] points. Each char of the result
     * is one byte (ISO-8859-1), so `length` is the stream length.
     */
    fun content(text: PageText, pw: Float, ph: Float): String {
        if (text.isEmpty || text.widthPx <= 0 || text.heightPx <= 0) return ""
        val sx = pw / text.widthPx
        val sy = ph / text.heightPx
        val sb = StringBuilder("BT 3 Tr\n")
        for (line in text.lines) {
            val c = line.corners
            // Image px (y down) → PDF points (y up).
            val tlx = c[0] * sx; val tly = ph - c[1] * sy
            val brx = c[4] * sx; val bry = ph - c[5] * sy
            val blx = c[6] * sx; val bly = ph - c[7] * sy
            val len = hypot(brx - blx, bry - bly)
            val height = hypot(tlx - blx, tly - bly)
            if (len < 1f || height < 1f) continue
            val cos = (brx - blx) / len
            val sin = (bry - bly) / len
            val encoded = encode(line.text)
            val units = encoded.sumOf { widthOf(it).toInt() }
            if (units <= 0) continue
            // The line box spans descender to ascender (≈0.93 em for Helvetica): size the font
            // to the box and put the baseline at the descender's height.
            val size = height / EM_BOX
            val ox = blx - sin * DESCENT * size
            val oy = bly + cos * DESCENT * size
            val scale = 100f * len / (units / 1000f * size)
            sb.append('/').append(FONT_RESOURCE).append(' ').append(f(size)).append(" Tf ")
                .append(f(scale)).append(" Tz ")
                .append(f(cos)).append(' ').append(f(sin)).append(' ').append(f(-sin)).append(' ').append(f(cos)).append(' ')
                .append(f(ox)).append(' ').append(f(oy)).append(" Tm (")
            for (ch in encoded) {
                if (ch == '(' || ch == ')' || ch == '\\') sb.append('\\')
                sb.append(ch)
            }
            sb.append(") Tj\n")
        }
        sb.append("ET\n")
        return sb.toString()
    }

    /** [s] in WinAnsiEncoding, one char per byte; unencodable characters become their base letter or '?'. */
    fun encode(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val code = ch.code
            when {
                code in 0x20..0x7E || code in 0xA0..0xFF -> sb.append(ch)
                ch.isWhitespace() || ch.isISOControl() -> sb.append(' ')
                else -> {
                    val cp = CP1252_EXTRAS[ch]
                    if (cp != null) sb.append(cp.toChar()) else {
                        val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)[0]
                        sb.append(if (base.code in 0x20..0x7E) base else '?')
                    }
                }
            }
        }
        return sb.toString()
    }

    /** Helvetica advance width (1/1000 em) of a WinAnsi byte. */
    private fun widthOf(b: Char): Short {
        val code = b.code
        if (code in 32..126) return WIDTHS[code - 32]
        if (code >= 0xC0) {
            // Accented letters are as wide as their base letter.
            val base = Normalizer.normalize(b.toString(), Normalizer.Form.NFD)[0].code
            if (base in 32..126) return WIDTHS[base - 32]
        }
        return 556
    }

    private fun f(v: Float) = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')

    private const val EM_BOX = 0.93f
    private const val DESCENT = 0.21f

    /** Helvetica widths for ASCII 32..126 (from the standard AFM). */
    private val WIDTHS = shortArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )

    /** Characters WinAnsiEncoding places in 0x80–0x9F. */
    private val CP1252_EXTRAS = mapOf(
        '€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87,
        'ˆ' to 0x88, '‰' to 0x89, 'Š' to 0x8A, '‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E,
        '‘' to 0x91, '’' to 0x92, '“' to 0x93, '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97,
        '˜' to 0x98, '™' to 0x99, 'š' to 0x9A, '›' to 0x9B, 'œ' to 0x9C, 'ž' to 0x9E, 'Ÿ' to 0x9F,
    )
}
