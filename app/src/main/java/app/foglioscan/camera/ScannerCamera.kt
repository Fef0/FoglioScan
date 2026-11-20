package app.foglioscan.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import app.foglioscan.vision.DocumentLocator
import app.foglioscan.vision.Quad
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class FlashMode(val cameraX: Int) {
    OFF(ImageCapture.FLASH_MODE_OFF), AUTO(ImageCapture.FLASH_MODE_AUTO), ON(ImageCapture.FLASH_MODE_ON);
    fun next() = entries[(ordinal + 1) % entries.size]
}

/**
 * Camera pipeline for scanning: a 4:3 preview, a maximum-quality still capture and a
 * low-resolution analysis stream on which the page is located on every frame.
 */
class ScannerCamera(context: Context) {

    private val appContext = context.applicationContext
    private val locator = DocumentLocator.get(appContext)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest.asStateFlow()

    /** Smoothed page outline in upright, normalized preview coordinates. */
    private val _liveQuad = MutableStateFlow<Quad?>(null)
    val liveQuad: StateFlow<Quad?> = _liveQuad.asStateFlow()

    private val ratio43 = AspectRatioStrategy(
        androidx.camera.core.AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO,
    )

    private val preview = Preview.Builder()
        .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build())
        .build()
        .also { it.setSurfaceProvider { request -> _surfaceRequest.value = request } }

    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(ratio43)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build(),
        )
        .setJpegQuality(95)
        .build()

    private val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(ratio43)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()

    /** True while the manufacturer's HDR / Auto processing is applied to captures. */
    private val _hdrActive = MutableStateFlow(false)
    val hdrActive: StateFlow<Boolean> = _hdrActive.asStateFlow()

    private var camera: Camera? = null
    private val smoother = QuadSmoother()

    var flashMode: FlashMode = FlashMode.OFF
        set(value) { field = value; imageCapture.flashMode = value.cameraX }

    /**
     * Binds the camera to [owner] until the calling coroutine is cancelled. With [useHdr], the
     * manufacturer's HDR (or Auto) extension is used when the phone supports it together with
     * live analysis, so the page highlight keeps working.
     */
    suspend fun bind(owner: LifecycleOwner, useHdr: Boolean) {
        val provider = ProcessCameraProvider.awaitInstance(appContext)
        analysis.setAnalyzer(analysisExecutor, ::analyze)
        try {
            provider.unbindAll()
            val (selector, hdr) = if (useHdr) extensionSelector(provider) else CameraSelector.DEFAULT_BACK_CAMERA to false
            val withExtension = runCatching {
                provider.bindToLifecycle(owner, selector, preview, imageCapture, analysis)
            }.getOrNull()
            camera = withExtension ?: run {
                // Some vendor extensions refuse this use-case combination: fall back to standard.
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
            }
            _hdrActive.value = hdr && withExtension != null
            awaitCancellation()
        } finally {
            analysis.clearAnalyzer()
            provider.unbindAll()
            camera = null
            _hdrActive.value = false
            _surfaceRequest.value = null
            _liveQuad.value = null
            smoother.reset()
        }
    }

    private suspend fun extensionSelector(provider: ProcessCameraProvider): Pair<CameraSelector, Boolean> {
        val base = CameraSelector.DEFAULT_BACK_CAMERA
        val manager = runCatching { ExtensionsManager.getInstance(appContext, provider) }.getOrNull()
            ?: return base to false
        for (mode in listOf(ExtensionMode.HDR, ExtensionMode.AUTO)) {
            val ok = runCatching {
                manager.isExtensionAvailable(base, mode) && manager.isImageAnalysisSupported(base, mode)
            }.getOrDefault(false)
            if (ok) return manager.getExtensionEnabledCameraSelector(base, mode) to true
        }
        return base to false
    }

    suspend fun capture(target: File): File = suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(target).build(),
            captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = cont.resume(target)
                override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
            },
        )
    }

    companion object {
        /** Whether the phone offers HDR/Auto processing usable together with live analysis. */
        suspend fun isPhoneHdrAvailable(context: Context): Boolean = runCatching {
            val app = context.applicationContext
            val manager = ExtensionsManager.getInstance(app, ProcessCameraProvider.awaitInstance(app))
            val base = CameraSelector.DEFAULT_BACK_CAMERA
            listOf(ExtensionMode.HDR, ExtensionMode.AUTO).any {
                manager.isExtensionAvailable(base, it) && manager.isImageAnalysisSupported(base, it)
            }
        }.getOrDefault(false)
    }

    fun release() {
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val rotation = it.imageInfo.rotationDegrees
            val frame = it.toBitmap()
            val detected = runCatching { locator.locateLive(frame, rotation) }.getOrNull()
            frame.recycle()
            _liveQuad.value = smoother.push(detected)
        }
    }
}

/**
 * Removes jitter from per-frame detections (exponential moving average when the page is
 * stable, snap when it moves a lot) and bridges a few missed frames so the highlight doesn't
 * flicker.
 */
private class QuadSmoother {
    private var current: Quad? = null
    private var misses = 0

    fun push(q: Quad?): Quad? {
        if (q == null) {
            if (++misses > MAX_MISSES) current = null
            return current
        }
        misses = 0
        val c = current
        current = when {
            c == null -> q
            c.averageCornerDistance(q) > SNAP_DISTANCE -> q
            else -> c.lerp(q, ALPHA)
        }
        return current
    }

    fun reset() { current = null; misses = 0 }

    private companion object {
        const val MAX_MISSES = 4
        const val SNAP_DISTANCE = 0.12f
        const val ALPHA = 0.45f
    }
}
