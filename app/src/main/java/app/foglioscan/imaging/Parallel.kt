package app.foglioscan.imaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.min

private val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

/** Splits [0, count) into contiguous ranges processed on all cores. */
internal suspend fun parallelRanges(count: Int, body: (start: Int, end: Int) -> Unit) = coroutineScope {
    val chunk = (count + workers - 1) / workers
    (0 until workers).map { i ->
        async(Dispatchers.Default) {
            val s = i * chunk
            val e = min(count, s + chunk)
            if (s < e) body(s, e)
        }
    }.awaitAll()
    Unit
}
