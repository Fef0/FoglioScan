package app.foglioscan.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The recognized text of every saved document, for in-app search. Always rebuilt from the PDFs
 * themselves (their invisible text layer), so it can never disagree with them: [sync] reads only
 * the documents added or changed since last time (by size and modification time) and forgets
 * deleted ones. Stored as one small JSON file in the app's private storage.
 */
class SearchIndex private constructor(context: Context) {

    @Serializable
    data class Entry(val uri: String, val size: Long, val modified: Long, val pages: List<String>)

    private val file = File(context.filesDir, "search-index.json")
    private val documents = DocumentRepository.get(context)
    private val lock = Mutex()
    private var entries: MutableMap<String, Entry>? = null

    private fun load(): MutableMap<String, Entry> = entries ?: runCatching {
        Json.decodeFromString<List<Entry>>(file.readText()).associateBy { it.uri }.toMutableMap()
    }.getOrElse { mutableMapOf() }.also { entries = it }

    /** Brings the index up to date with [docs]; returns the text of each, by document URI. */
    suspend fun sync(docs: List<SavedDocument>): Map<String, List<String>> = lock.withLock {
        withContext(Dispatchers.IO) {
            val map = load()
            var changed = map.keys.retainAll(docs.map { it.uri.toString() }.toSet())
            for (doc in docs) {
                val key = doc.uri.toString()
                val e = map[key]
                if (e != null && e.size == doc.sizeBytes && e.modified == doc.modifiedMillis) continue
                val pages = runCatching { documents.pageTexts(doc.uri) }
                    .onFailure { Log.w(TAG, "Can't read the text of ${doc.name}", it) }
                    .getOrDefault(emptyList())
                map[key] = Entry(key, doc.sizeBytes, doc.modifiedMillis, pages)
                changed = true
            }
            if (changed) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(Json.encodeToString(map.values.toList()))
                tmp.renameTo(file)
            }
            map.mapValues { it.value.pages }
        }
    }

    companion object {
        private const val TAG = "SearchIndex"
        @Volatile private var instance: SearchIndex? = null
        fun get(context: Context): SearchIndex = instance ?: synchronized(this) {
            instance ?: SearchIndex(context.applicationContext).also { instance = it }
        }
    }
}
