package app.foglioscan.ocr

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Detects pages scanned sideways or upside down from the direction of their text: ML Kit
 * reads rotated text and reports each line's angle, so the dominant angle (weighted by the
 * amount of text) tells how the page must be turned.
 */
object PageOrientation {

    /**
     * Clockwise rotation (0, 90, 180 or 270) that makes the text upright, or 0 when there is
     * too little text or no clear majority.
     */
    fun correction(text: PageText, minChars: Int = 30, minShare: Float = 0.6f): Int {
        val weights = IntArray(4) // text rotated by 0, 90, 180, 270 degrees clockwise
        for (line in text.lines) {
            val quarter = Math.floorMod((line.angle / 90f).roundToInt(), 4)
            // Lines far from a right angle (slanted handwriting, stamps) don't vote.
            if (abs(line.angle - Math.round(line.angle / 90f) * 90f) > 20f) continue
            weights[quarter] += line.text.length
        }
        val total = weights.sum()
        if (total < minChars) return 0
        val best = weights.indices.maxBy { weights[it] }
        if (weights[best] < total * minShare) return 0
        return (360 - best * 90) % 360
    }
}
