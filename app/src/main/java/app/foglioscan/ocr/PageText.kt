package app.foglioscan.ocr

/**
 * Text recognized on a rendered page.
 *
 * @property widthPx width of the image the coordinates refer to (the full-resolution rendering).
 */
class PageText(val widthPx: Int, val heightPx: Int, val lines: List<TextLine>) {
    /** Plain text in reading order, one recognized line per line, blocks separated by a blank line. */
    val plain: String
        get() = buildString {
            var block = -1
            for (l in lines) {
                if (isNotEmpty()) append(if (l.block != block) "\n\n" else "\n")
                block = l.block
                append(l.text)
            }
        }

    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * One line of text and its (possibly rotated) box in image pixels: top-left, top-right,
 * bottom-right, bottom-left. The baseline runs from bottom-left to bottom-right.
 */
class TextLine(val text: String, val block: Int, val corners: FloatArray) {
    init { require(corners.size == 8) }
}
