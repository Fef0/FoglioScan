package app.foglioscan.imaging

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Curved-page geometry: UVDoc (Verhoeven et al., "UVDoc: Neural Grid-based Document Unwarping",
 * SIGGRAPH Asia 2023, MIT) on LiteRT, float16 weights. Given a crop around the page, predicts
 * where a 45×31 grid laid evenly over the flattened page lies in the photo.
 *
 * Input: 712×488 (H×W) RGB in [0,1], NHWC, the crop stretched to fill it, as in the reference
 * implementation. Output: 45×31×2, (x, y) in [-1, 1] over the crop (align-corners convention).
 */
class PageDewarper private constructor(
    private val env: Environment,
    private val model: CompiledModel,
    val accelerator: Accelerator,
) : AutoCloseable {

    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()
    private val input = FloatArray(IN_W * IN_H * 3)

    /**
     * Predicts the page grid for the region [x0, x1) × [y0, y1) of the ARGB image [src]
     * (a crop a little larger than the page). The grid is in [src] pixel coordinates.
     */
    @Synchronized
    fun predict(src: IntArray, srcW: Int, x0: Int, y0: Int, x1: Int, y1: Int): PageGrid {
        areaResize(src, srcW, x0, y0, x1, y1)
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        val g = outputs[0].readFloat()
        val cw = (x1 - x0 - 1).toFloat()
        val ch = (y1 - y0 - 1).toFloat()
        val xy = FloatArray(GRID_ROWS * GRID_COLS * 2)
        for (i in 0 until GRID_ROWS * GRID_COLS) {
            xy[2 * i] = (g[2 * i] + 1f) / 2f * cw + x0
            xy[2 * i + 1] = (g[2 * i + 1] + 1f) / 2f * ch + y0
        }
        return PageGrid(GRID_ROWS, GRID_COLS, xy)
    }

    /** Box-filter downscale of the region into the network input (RGB floats). */
    private fun areaResize(src: IntArray, srcW: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val sx = (x1 - x0).toFloat() / IN_W
        val sy = (y1 - y0).toFloat() / IN_H
        var o = 0
        for (ty in 0 until IN_H) {
            val ya = y0 + (ty * sy).toInt()
            val yb = max(ya + 1, min(y1, y0 + ((ty + 1) * sy).toInt()))
            for (tx in 0 until IN_W) {
                val xa = x0 + (tx * sx).toInt()
                val xb = max(xa + 1, min(x1, x0 + ((tx + 1) * sx).toInt()))
                var r = 0; var gr = 0; var b = 0
                for (y in ya until yb) {
                    var i = y * srcW + xa
                    for (x in xa until xb) {
                        val p = src[i++]
                        r += (p shr 16) and 0xFF; gr += (p shr 8) and 0xFF; b += p and 0xFF
                    }
                }
                val k = 1f / (255f * (yb - ya) * (xb - xa))
                input[o++] = r * k
                input[o++] = gr * k
                input[o++] = b * k
            }
        }
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
        env.close()
    }

    companion object {
        private const val TAG = "PageDewarper"
        const val MODEL_ASSET = "models/uvdoc_fp16.tflite"
        const val IN_W = 488
        const val IN_H = 712
        const val GRID_ROWS = 45
        const val GRID_COLS = 31

        /** Crop margin around the detected page, as a fraction of its size (best on test pages). */
        const val CROP_MARGIN = 0.03f

        @Volatile private var instance: PageDewarper? = null
        @Volatile private var unavailable = false

        /** The shared dewarper, created on first use; null if the model can't run here. */
        fun get(context: Context): PageDewarper? {
            instance?.let { return it }
            if (unavailable) return null
            synchronized(this) {
                instance?.let { return it }
                return create(context).also { if (it == null) unavailable = true else instance = it }
            }
        }

        private fun create(context: Context): PageDewarper? {
            val env = runCatching { Environment.create(context.applicationContext) }
                .onFailure { Log.w(TAG, "LiteRT environment unavailable", it) }
                .getOrNull() ?: return null
            // CPU only: UVDoc runs once per page (≈0.3–0.8 s), and compiling it for the GPU
            // crashed natively inside the emulator's GL driver (an uncatchable crash). The live
            // corner detector, which needs the speed, keeps the GPU.
            for (acc in listOf(Accelerator.CPU)) {
                val model = runCatching {
                    CompiledModel.create(context.assets, MODEL_ASSET, CompiledModel.Options(acc), env)
                }.onFailure { Log.w(TAG, "Model init on $acc failed", it) }.getOrNull()
                if (model != null) {
                    Log.i(TAG, "UVDoc running on $acc")
                    return PageDewarper(env, model, acc)
                }
            }
            env.close()
            return null
        }
    }
}
