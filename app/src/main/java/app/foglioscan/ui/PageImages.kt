package app.foglioscan.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.foglioscan.imaging.PageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Page images for the UI, decoded on demand from the files on disk and kept in a size-bounded
 * LRU cache. Memory therefore depends on what is on screen, not on the number of pages.
 */
object PageImages {

    private val maxBytes = min(64L shl 20, Runtime.getRuntime().maxMemory() / 6).toInt()

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private fun key(file: File, maxSide: Int) = "${file.path}@$maxSide"

    fun peek(file: File, maxSide: Int): ImageBitmap? = cache.get(key(file, maxSide))?.asImageBitmap()

    suspend fun load(file: File, maxSide: Int): ImageBitmap? {
        val k = key(file, maxSide)
        cache.get(k)?.let { return it.asImageBitmap() }
        val bmp = withContext(Dispatchers.IO) {
            runCatching { PageProcessor.decode(file, maxSide) }.getOrNull()
        } ?: return null
        cache.put(k, bmp)
        return bmp.asImageBitmap()
    }

    /** Drops every cached size of [file] (called when a page is re-rendered or deleted). */
    fun evict(file: File) {
        val prefix = "${file.path}@"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    fun clear() = cache.evictAll()
}

/**
 * The image of [file] at most [maxSide] px. When [file] changes (e.g. after a filter change)
 * the previous image stays on screen until the new one is decoded — no flash, no blur.
 */
@Composable
fun rememberPageImage(file: File?, maxSide: Int): ImageBitmap? {
    val image by produceState(initialValue = file?.let { PageImages.peek(it, maxSide) }, file, maxSide) {
        if (file != null) PageImages.load(file, maxSide)?.let { value = it }
    }
    return image
}
