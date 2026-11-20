package app.foglioscan.imaging

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Serializable
enum class PageFilter { AUTO, GRAYSCALE, BLACK_WHITE, ORIGINAL }

/**
 * Turns a rectified camera photo into something that looks like it came out of a flatbed
 * scanner:
 *
 *  1. **Illumination flattening** – the paper colour is estimated on a coarse grid (block max,
 *     dilation, smoothing) and every pixel is divided by it. This removes shadows, vignetting
 *     and colour casts in one step and makes the paper pure white.
 *     Regions much darker than the paper (photos, logos) are detected and excluded so they are
 *     not washed out.
 *  2. **Levels + gamma** – ink is pushed to black, near-white to white, mid-tones deepened.
 *  3. **Unsharp mask** on luminance for crisp text.
 *  4. Mode specific: mild saturation boost (AUTO), luminance only (GRAYSCALE), or an
 *     anti-aliased Otsu threshold (BLACK_WHITE).
 */
object PageEnhancer {

    suspend fun enhance(px: IntArray, w: Int, h: Int, filter: PageFilter) {
        if (filter == PageFilter.ORIGINAL) return

        val bg = estimateBackground(px, w, h)
        val y = ByteArray(w * h)

        when (filter) {
            PageFilter.AUTO -> {
                // Pass 1: normalize + saturation; collect luminance for the levels histogram.
                parallelRanges(h) { r0, r1 ->
                    for (row in r0 until r1) bg.forRow(row, w) { x, br, bgc, bb ->
                        val i = row * w + x
                        val c = px[i]
                        var r = min(255, ((c shr 16) and 0xFF) * 255 / br)
                        var g = min(255, ((c shr 8) and 0xFF) * 255 / bgc)
                        var b = min(255, (c and 0xFF) * 255 / bb)
                        val l = (r * 77 + g * 150 + b * 29) shr 8
                        r = clamp(l + (r - l) * 5 / 4); g = clamp(l + (g - l) * 5 / 4); b = clamp(l + (b - l) * 5 / 4)
                        px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        y[i] = l.toByte()
                    }
                }
                val lut = levelsLut(y)
                parallelRanges(w * h) { s, e ->
                    for (i in s until e) {
                        val c = px[i]
                        px[i] = (0xFF shl 24) or (lut[(c shr 16) and 0xFF] shl 16) or
                            (lut[(c shr 8) and 0xFF] shl 8) or lut[c and 0xFF]
                        y[i] = lut[y[i].toInt() and 0xFF].toByte()
                    }
                }
                sharpenColor(px, y, w, h, amount = 0.7f)
            }

            PageFilter.GRAYSCALE, PageFilter.BLACK_WHITE -> {
                parallelRanges(h) { r0, r1 ->
                    for (row in r0 until r1) bg.forRow(row, w) { x, br, bgc, bb ->
                        val i = row * w + x
                        val c = px[i]
                        val r = min(255, ((c shr 16) and 0xFF) * 255 / br)
                        val g = min(255, ((c shr 8) and 0xFF) * 255 / bgc)
                        val b = min(255, (c and 0xFF) * 255 / bb)
                        y[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                    }
                }
                if (filter == PageFilter.GRAYSCALE) {
                    val lut = levelsLut(y)
                    parallelRanges(w * h) { s, e -> for (i in s until e) y[i] = lut[y[i].toInt() and 0xFF].toByte() }
                    sharpenGray(y, w, h, amount = 0.8f)
                } else {
                    sharpenGray(y, w, h, amount = 1.0f)
                    binarize(y)
                }
                parallelRanges(w * h) { s, e ->
                    for (i in s until e) {
                        val v = y[i].toInt() and 0xFF
                        px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
            }

            PageFilter.ORIGINAL -> Unit
        }
    }

    // --- Background (paper) estimation --------------------------------------------------------

    /** Coarse per-channel paper colour, bilinearly interpolated on access. */
    private class Background(
        val gw: Int, val gh: Int, val block: Int,
        val r: IntArray, val g: IntArray, val b: IntArray,
        val imgW: Int, val imgH: Int,
    ) {
        // Precomputed horizontal interpolation (index + 8-bit weight) per column.
        val xi = IntArray(imgW)
        val xf = IntArray(imgW)

        init {
            for (x in 0 until imgW) {
                val gx = ((x + 0.5f) / block - 0.5f).coerceIn(0f, gw - 1f)
                xi[x] = min(gx.toInt(), gw - 2).coerceAtLeast(0)
                xf[x] = if (gw == 1) 0 else ((gx - xi[x]) * 256).toInt().coerceIn(0, 256)
            }
        }

        inline fun forRow(row: Int, w: Int, fn: (x: Int, r: Int, g: Int, b: Int) -> Unit) {
            val gy = ((row + 0.5f) / block - 0.5f).coerceIn(0f, gh - 1f)
            val y0 = min(gy.toInt(), gh - 2).coerceAtLeast(0)
            val y1 = min(y0 + 1, gh - 1)
            val fy = if (gh == 1) 0 else ((gy - y0) * 256).toInt().coerceIn(0, 256)
            val ify = 256 - fy
            for (x in 0 until w) {
                val x0 = xi[x]
                val x1 = min(x0 + 1, gw - 1)
                val fx = xf[x]
                val ifx = 256 - fx
                val i00 = y0 * gw + x0; val i10 = y0 * gw + x1
                val i01 = y1 * gw + x0; val i11 = y1 * gw + x1
                val vr = ((r[i00] * ifx + r[i10] * fx) * ify + (r[i01] * ifx + r[i11] * fx) * fy) shr 16
                val vg = ((g[i00] * ifx + g[i10] * fx) * ify + (g[i01] * ifx + g[i11] * fx) * fy) shr 16
                val vb = ((b[i00] * ifx + b[i10] * fx) * ify + (b[i01] * ifx + b[i11] * fx) * fy) shr 16
                fn(x, max(1, vr), max(1, vg), max(1, vb))
            }
        }
    }

    private fun estimateBackground(px: IntArray, w: Int, h: Int): Background {
        val block = max(4, ceil(max(w, h) / 220f).toInt())
        val gw = (w + block - 1) / block
        val gh = (h + block - 1) / block
        val n = gw * gh
        val r = IntArray(n); val g = IntArray(n); val b = IntArray(n)

        // Block maximum per channel (sub-sampled): any paper pixel in the block wins over ink.
        for (by in 0 until gh) for (bx in 0 until gw) {
            var mr = 0; var mg = 0; var mb = 0
            val ys = by * block; val ye = min(h, ys + block)
            val xs = bx * block; val xe = min(w, xs + block)
            var yy = ys
            while (yy < ye) {
                var xx = xs
                val row = yy * w
                while (xx < xe) {
                    val c = px[row + xx]
                    mr = max(mr, (c shr 16) and 0xFF); mg = max(mg, (c shr 8) and 0xFF); mb = max(mb, c and 0xFF)
                    xx += 2
                }
                yy += 2
            }
            val i = by * gw + bx
            r[i] = mr; g[i] = mg; b[i] = mb
        }

        // Morphological closing: dilate to swallow headings, bold text and table rules, then
        // erode by the same amount so bright regions don't bleed into shadows (which would leave
        // a dark halo just inside every hard shadow edge).
        repeat(2) { dilate(r, gw, gh); dilate(g, gw, gh); dilate(b, gw, gh) }
        repeat(2) { erode(r, gw, gh); erode(g, gw, gh); erode(b, gw, gh) }

        // Paper reference = mean colour of the brightest 10% cells.
        val lum = IntArray(n) { (r[it] * 77 + g[it] * 150 + b[it] * 29) shr 8 }
        val sorted = lum.sortedArray()
        val p90 = max(1, sorted[(n * 0.9f).toInt().coerceIn(0, n - 1)])
        var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0
        for (i in 0 until n) if (lum[i] >= p90 * 0.97f) { sr += r[i]; sg += g[i]; sb += b[i]; cnt++ }
        val pr = (sr / max(1, cnt)).toInt(); val pg = (sg / max(1, cnt)).toInt(); val pb = (sb / max(1, cnt)).toInt()

        // Shadow or content? A shadow dims the paper but keeps its chromaticity; a photo, stamp or
        // coloured box changes it (or is near-black). Content cells fall back to the paper
        // reference so they are not blown out, while shadows are flattened away.
        val pSum = max(1, pr + pg + pb).toFloat()
        val pcr = pr / pSum; val pcg = pg / pSum; val pcb = pb / pSum
        for (i in 0 until n) {
            val sum = max(1, r[i] + g[i] + b[i]).toFloat()
            val chromaDist = max(abs(r[i] / sum - pcr), max(abs(g[i] / sum - pcg), abs(b[i] / sum - pcb)))
            val sameMaterial = 1f - smoothstep(0.035f, 0.08f, chromaDist)
            val notBlack = smoothstep(0.10f, 0.22f, lum[i].toFloat() / p90)
            val t = sameMaterial * notBlack
            r[i] = (pr + (r[i] - pr) * t).toInt()
            g[i] = (pg + (g[i] - pg) * t).toInt()
            b[i] = (pb + (b[i] - pb) * t).toInt()
        }

        repeat(2) { boxBlur(r, gw, gh); boxBlur(g, gw, gh); boxBlur(b, gw, gh) }
        return Background(gw, gh, block, r, g, b, w, h)
    }

    private fun dilate(a: IntArray, w: Int, h: Int) {
        val src = a.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) m = max(m, src[yy * w + xx])
            }
            a[y * w + x] = m
        }
    }

    private fun erode(a: IntArray, w: Int, h: Int) {
        val src = a.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var m = 255
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) m = min(m, src[yy * w + xx])
            }
            a[y * w + x] = m
        }
    }

    private fun boxBlur(a: IntArray, w: Int, h: Int, radius: Int = 2) {
        val tmp = IntArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var c = 0
            for (d in -radius..radius) { val xx = x + d; if (xx in 0 until w) { s += a[y * w + xx]; c++ } }
            tmp[y * w + x] = s / c
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var c = 0
            for (d in -radius..radius) { val yy = y + d; if (yy in 0 until h) { s += tmp[yy * w + x]; c++ } }
            a[y * w + x] = s / c
        }
    }

    // --- Tone ---------------------------------------------------------------------------------

    /** Black point from the darkest 1% (ink), white clip at 238, gamma 1.2 for denser text. */
    private fun levelsLut(y: ByteArray): IntArray {
        val hist = histogram(y)
        val total = hist.sum()
        var acc = 0
        var bp = 0
        for (v in 0..255) { acc += hist[v]; if (acc >= total * 0.01f) { bp = v; break } }
        bp = bp.coerceIn(0, 110)
        val wp = 238
        return IntArray(256) { v ->
            val t = ((v - bp).toFloat() / (wp - bp)).coerceIn(0f, 1f)
            (255f * t.pow(1.2f)).toInt()
        }
    }

    /** Otsu threshold with a narrow linear ramp so glyph edges stay anti-aliased. */
    private suspend fun binarize(y: ByteArray) {
        val hist = histogram(y)
        val total = hist.sum().toDouble()
        var sumAll = 0.0
        for (v in 0..255) sumAll += v * hist[v].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var t = 160
        for (v in 0..255) {
            wB += hist[v]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sumB += v * hist[v].toDouble()
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = v }
        }
        // After flattening the paper sits near 255; keep faint pencil but drop paper texture.
        t = t.coerceIn(110, 215)
        val lo = t - 14; val span = 28
        val lut = IntArray(256) { v -> clamp((v - lo) * 255 / span) }
        parallelRanges(y.size) { s, e -> for (i in s until e) y[i] = lut[y[i].toInt() and 0xFF].toByte() }
    }

    // --- Sharpening ---------------------------------------------------------------------------

    private suspend fun blur3(src: ByteArray, w: Int, h: Int): ByteArray {
        val tmp = ByteArray(src.size)
        val out = ByteArray(src.size)
        parallelRanges(h) { r0, r1 ->
            for (y in r0 until r1) {
                val row = y * w
                for (x in 0 until w) {
                    val a = src[row + max(0, x - 1)].toInt() and 0xFF
                    val b = src[row + x].toInt() and 0xFF
                    val c = src[row + min(w - 1, x + 1)].toInt() and 0xFF
                    tmp[row + x] = ((a + 2 * b + c) shr 2).toByte()
                }
            }
        }
        parallelRanges(h) { r0, r1 ->
            for (y in r0 until r1) {
                val up = max(0, y - 1) * w; val row = y * w; val dn = min(h - 1, y + 1) * w
                for (x in 0 until w) {
                    val a = tmp[up + x].toInt() and 0xFF
                    val b = tmp[row + x].toInt() and 0xFF
                    val c = tmp[dn + x].toInt() and 0xFF
                    out[row + x] = ((a + 2 * b + c) shr 2).toByte()
                }
            }
        }
        return out
    }

    private suspend fun sharpenGray(y: ByteArray, w: Int, h: Int, amount: Float) {
        val blurred = blur3(y, w, h)
        val k = (amount * 256).toInt()
        parallelRanges(y.size) { s, e ->
            for (i in s until e) {
                val v = y[i].toInt() and 0xFF
                y[i] = clamp(v + ((v - (blurred[i].toInt() and 0xFF)) * k shr 8)).toByte()
            }
        }
    }

    private suspend fun sharpenColor(px: IntArray, y: ByteArray, w: Int, h: Int, amount: Float) {
        val blurred = blur3(y, w, h)
        val k = (amount * 256).toInt()
        parallelRanges(px.size) { s, e ->
            for (i in s until e) {
                val d = ((y[i].toInt() and 0xFF) - (blurred[i].toInt() and 0xFF)) * k shr 8
                if (d == 0) continue
                val c = px[i]
                px[i] = (0xFF shl 24) or (clamp(((c shr 16) and 0xFF) + d) shl 16) or
                    (clamp(((c shr 8) and 0xFF) + d) shl 8) or clamp((c and 0xFF) + d)
            }
        }
    }

    // --- Utils --------------------------------------------------------------------------------

    private fun histogram(y: ByteArray): IntArray {
        val hist = IntArray(256)
        var i = 0
        while (i < y.size) { hist[y[i].toInt() and 0xFF]++; i += 3 }
        return hist
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
