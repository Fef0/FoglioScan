package app.foglioscan.vision

import android.graphics.Bitmap
import androidx.core.graphics.scale
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** 8-bit luminance image, row-major, no padding. */
class GrayImage(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {

    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    fun rotated(degrees: Int): GrayImage = when (((degrees % 360) + 360) % 360) {
        90 -> GrayImage(height, width).also { out ->
            // (x, y) -> (h - 1 - y, x)
            for (y in 0 until height) for (x in 0 until width)
                out.data[x * height + (height - 1 - y)] = data[y * width + x]
        }
        180 -> GrayImage(width, height).also { out ->
            val n = data.size
            for (i in 0 until n) out.data[n - 1 - i] = data[i]
        }
        270 -> GrayImage(height, width).also { out ->
            // (x, y) -> (y, w - 1 - x)
            for (y in 0 until height) for (x in 0 until width)
                out.data[(width - 1 - x) * height + y] = data[y * width + x]
        }
        else -> this
    }

    companion object {
        /**
         * Box-downsamples a camera Y plane by an integer factor so the long side is
         * close to [targetLongSide].
         */
        fun fromYPlane(
            buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
            width: Int, height: Int, targetLongSide: Int,
        ): GrayImage {
            val f = max(1, (max(width, height) + targetLongSide - 1) / targetLongSide)
            val ow = width / f
            val oh = height / f
            val out = GrayImage(ow, oh)
            val area = f * f
            for (oy in 0 until oh) {
                for (ox in 0 until ow) {
                    var sum = 0
                    for (dy in 0 until f) {
                        val row = (oy * f + dy) * rowStride
                        for (dx in 0 until f) sum += buffer.get(row + (ox * f + dx) * pixelStride).toInt() and 0xFF
                    }
                    out.data[oy * ow + ox] = (sum / area).toByte()
                }
            }
            return out
        }

        fun fromBitmap(bitmap: Bitmap, targetLongSide: Int): GrayImage {
            val long = max(bitmap.width, bitmap.height)
            val s = targetLongSide.toFloat() / long
            val small = if (s < 1f) bitmap.scale((bitmap.width * s).roundToInt(), (bitmap.height * s).roundToInt()) else bitmap
            val w = small.width
            val h = small.height
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== bitmap) small.recycle()
            val out = GrayImage(w, h)
            for (i in px.indices) {
                val c = px[i]
                out.data[i] = ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
            }
            return out
        }
    }
}
