package app.foglioscan.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer

/**
 * Neural page-corner detector: DocAligner (LCNet-100 backbone + BiFPN, heatmap regression,
 * Apache-2.0 code, weights released without restrictions) running on LiteRT with the GPU
 * accelerator, falling back to CPU.
 *
 * Input: 256×256 BGR in [0,1], NHWC, the whole frame stretched (no letterboxing),
 * exactly like the reference implementation. Output: 128×128×4 heatmaps (TL, TR, BR, BL).
 */
class NeuralDocumentDetector private constructor(
    private val env: Environment,
    private val model: CompiledModel,
    val accelerator: Accelerator,
) : AutoCloseable {

    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()
    private val pixels = IntArray(INPUT * INPUT)
    private val input = FloatArray(INPUT * INPUT * 3)
    private val scratch = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(scratch)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    /**
     * Detects the page in [frame], first rotating it clockwise by [rotationDegrees] so the
     * result is in upright (display) coordinates. Returns a normalized quad or null.
     */
    @Synchronized
    fun detect(frame: Bitmap, rotationDegrees: Int = 0): Quad? {
        // Rotate + stretch into the 256×256 input in a single draw.
        val rot = ((rotationDegrees % 360) + 360) % 360
        val upW = if (rot % 180 == 0) frame.width else frame.height
        val upH = if (rot % 180 == 0) frame.height else frame.width
        matrix.reset()
        matrix.postTranslate(-frame.width / 2f, -frame.height / 2f)
        matrix.postRotate(rot.toFloat())
        matrix.postScale(INPUT.toFloat() / upW, INPUT.toFloat() / upH)
        matrix.postTranslate(INPUT / 2f, INPUT / 2f)
        return infer(frame)
    }

    private fun infer(src: Bitmap): Quad? {
        canvas.drawBitmap(src, matrix, paint)
        scratch.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)

        var j = 0
        for (c in pixels) {
            input[j++] = (c and 0xFF) / 255f          // B
            input[j++] = ((c shr 8) and 0xFF) / 255f  // G
            input[j++] = ((c shr 16) and 0xFF) / 255f // R
        }
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        return decode(outputs[0].readFloat())
    }

    /** Largest above-threshold blob per heatmap, value-weighted centroid for sub-cell accuracy. */
    private fun decode(hm: FloatArray): Quad? {
        val n = HEATMAP * HEATMAP
        val label = IntArray(n)
        val stack = IntArray(n)
        val points = ArrayList<Point2>(4)
        for (ch in 0 until 4) {
            label.fill(0)
            var bestW = 0f; var bestX = 0f; var bestY = 0f; var bestArea = 0
            var nextLabel = 1
            for (start in 0 until n) {
                if (label[start] != 0 || hm[start * 4 + ch] < THRESHOLD) continue
                var sp = 0; stack[sp++] = start; label[start] = nextLabel
                var area = 0; var wsum = 0f; var xs = 0f; var ys = 0f
                while (sp > 0) {
                    val p = stack[--sp]
                    val px = p % HEATMAP; val py = p / HEATMAP
                    val v = hm[p * 4 + ch]
                    area++; wsum += v; xs += v * px; ys += v * py
                    if (px > 0) push(p - 1, ch, hm, label, nextLabel, stack, sp).also { sp = it }
                    if (px < HEATMAP - 1) push(p + 1, ch, hm, label, nextLabel, stack, sp).also { sp = it }
                    if (py > 0) push(p - HEATMAP, ch, hm, label, nextLabel, stack, sp).also { sp = it }
                    if (py < HEATMAP - 1) push(p + HEATMAP, ch, hm, label, nextLabel, stack, sp).also { sp = it }
                }
                if (area > bestArea) { bestArea = area; bestW = wsum; bestX = xs; bestY = ys }
                nextLabel++
            }
            if (bestArea == 0) return null
            points += Point2((bestX / bestW + 0.5f) / HEATMAP, (bestY / bestW + 0.5f) / HEATMAP)
        }
        val quad = Quad.fromPoints(points)
        if (!quad.isConvex() || quad.area() < MIN_AREA) return null
        return quad
    }

    private fun push(q: Int, ch: Int, hm: FloatArray, label: IntArray, l: Int, stack: IntArray, sp: Int): Int {
        if (label[q] != 0 || hm[q * 4 + ch] < THRESHOLD) return sp
        label[q] = l
        stack[sp] = q
        return sp + 1
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
        env.close()
        scratch.recycle()
    }

    companion object {
        private const val TAG = "NeuralDetector"
        const val MODEL_ASSET = "models/docaligner_lcnet100.tflite"
        private const val INPUT = 256
        private const val HEATMAP = 128
        private const val THRESHOLD = 0.3f
        private const val MIN_AREA = 0.04f

        /** Creates the detector on GPU, falling back to CPU; null if the model can't run at all. */
        fun create(context: Context): NeuralDocumentDetector? {
            val env = runCatching { Environment.create(context.applicationContext) }
                .onFailure { Log.w(TAG, "LiteRT environment unavailable", it) }
                .getOrNull() ?: return null
            for (acc in listOf(Accelerator.GPU, Accelerator.CPU)) {
                val model = runCatching {
                    CompiledModel.create(context.assets, MODEL_ASSET, CompiledModel.Options(acc), env)
                }.onFailure { Log.w(TAG, "Model init on $acc failed", it) }.getOrNull()
                if (model != null) {
                    Log.i(TAG, "DocAligner running on $acc")
                    return NeuralDocumentDetector(env, model, acc)
                }
            }
            env.close()
            return null
        }
    }
}
