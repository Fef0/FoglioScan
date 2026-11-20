package app.foglioscan.vision

import android.content.Context
import android.graphics.Bitmap

/**
 * Single entry point for "where is the page?".
 *
 *  - Live: neural detector on the analysis frame (classic Hough detector if LiteRT is
 *    unavailable on the device).
 *  - Precise (after the shutter): neural detection on the full-resolution photo.
 *
 * Design note, measured on real photos (SmartDoc 2015, see SmartDocBenchmark): the network
 * alone is the most accurate option (median corner error 0.31% of the page diagonal). Edge-
 * snapping refinements and a test-time-zoom second pass were both tried and made results
 * *worse* (they lock onto printed rules inside the page), so they were removed.
 */
class DocumentLocator private constructor(context: Context) {

    private val neural: NeuralDocumentDetector? by lazy { NeuralDocumentDetector.create(context) }
    private val classic = DocumentDetector()

    val usesNeuralNetwork: Boolean get() = neural != null

    /** Loads and compiles the model ahead of time (GPU compilation can take a few hundred ms). */
    fun warmUp() { neural }

    /** [frame] as delivered by the camera; [rotationDegrees] makes it upright. */
    fun locateLive(frame: Bitmap, rotationDegrees: Int): Quad? {
        neural?.let { return it.detect(frame, rotationDegrees) }
        return classic.detect(GrayImage.fromBitmap(frame, LIVE_CLASSIC_SIDE).rotated(rotationDegrees))
    }

    /** [photo] must already be upright. */
    fun locatePrecise(photo: Bitmap): Quad? =
        neural?.detect(photo) ?: classic.detect(GrayImage.fromBitmap(photo, CLASSIC_SIDE))

    companion object {
        private const val LIVE_CLASSIC_SIDE = 320
        private const val CLASSIC_SIDE = 720

        @Volatile private var instance: DocumentLocator? = null
        fun get(context: Context): DocumentLocator =
            instance ?: synchronized(this) {
                instance ?: DocumentLocator(context.applicationContext).also { instance = it }
            }
    }
}
