package app.foglioscan.imaging

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enhancer on a synthetic page: warm paper with a strong diagonal shadow, black text lines
 * and a red stamp. After AUTO the paper must be white everywhere (shadow gone), text black, and
 * the stamp still red.
 */
class PageEnhancerTest {

    private val w = 600
    private val h = 800

    private fun isText(x: Int, y: Int) = (y % 40) in 10..13 && x in 60..540
    private fun isStamp(x: Int, y: Int) = x in 100..300 && y in 500..640

    private fun page(): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        // Light falls off towards the bottom-right; a hand shadow darkens it to ~45%.
        val light = 1.0 - 0.25 * (x + y) / (w + h).toDouble()
        val shadow = if (x + y > 900) 0.45 else 1.0
        val k = light * shadow
        val (r, g, b) = when {
            isStamp(x, y) -> Triple(200, 50, 45)
            isText(x, y) -> Triple(35, 35, 35)
            else -> Triple(240, 230, 205) // warm paper
        }
        fun c(v: Int) = (v * k).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    @Test
    fun autoRemovesShadowKeepsTextAndColour() = runBlocking {
        val px = page()
        PageEnhancer.enhance(px, w, h, PageFilter.AUTO)

        val paper = (0 until w * h).filter { i -> val x = i % w; val y = i / w
            !isText(x, y) && !isStamp(x, y) && (y % 40) !in 6..17 && !(x in 90..310 && y in 490..650) }
        val shadowPaper = paper.filter { i -> (i % w) + (i / w) > 950 }
        val minPaper = paper.minOf { lum(px[it]) }
        assertTrue("paper white everywhere (min lum $minPaper)", paper.count { lum(px[it]) >= 235 } > paper.size * 0.98)
        assertTrue("shadowed paper white too", shadowPaper.count { lum(px[it]) >= 235 } > shadowPaper.size * 0.98)

        val text = (0 until w * h).filter { i -> isText(i % w, i / w) && (i / w) % 40 in 11..12 }
        assertTrue("text dark", text.count { lum(px[it]) < 90 } > text.size * 0.95)

        val stamp = (0 until w * h).filter { i -> val x = i % w; val y = i / w; x in 120..280 && y in 520..620 }
        val red = stamp.count { val c = px[it]; ((c shr 16) and 0xFF) > 150 && ((c shr 8) and 0xFF) < 110 }
        assertTrue("stamp still red ($red/${stamp.size})", red > stamp.size * 0.9)
    }

    @Test
    fun blackAndWhiteIsTwoTone() = runBlocking {
        val px = page()
        PageEnhancer.enhance(px, w, h, PageFilter.BLACK_WHITE)
        val nearExtreme = px.count { val l = lum(it); l < 30 || l > 225 }
        assertTrue("mostly pure black/white ($nearExtreme/${px.size})", nearExtreme > px.size * 0.97)
    }

    @Test
    fun originalIsUntouched() = runBlocking {
        val px = page()
        val copy = px.copyOf()
        PageEnhancer.enhance(px, w, h, PageFilter.ORIGINAL)
        assertTrue(px.contentEquals(copy))
    }
}
