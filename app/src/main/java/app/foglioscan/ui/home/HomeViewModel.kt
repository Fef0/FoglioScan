package app.foglioscan.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.foglioscan.data.DocumentRepository
import app.foglioscan.data.SavedDocument
import app.foglioscan.data.SearchIndex
import app.foglioscan.data.SearchMatch
import app.foglioscan.data.TextSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A saved document matching the search query. */
data class SearchResult(val doc: SavedDocument, val match: SearchMatch)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    val repository = DocumentRepository.get(app)
    private val index = SearchIndex.get(app)

    private val _documents = MutableStateFlow<List<SavedDocument>?>(null)
    val documents: StateFlow<List<SavedDocument>?> = _documents.asStateFlow()

    /** Text of every saved document, by URI (kept in sync with [documents]). */
    private val texts = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Documents matching [query], most recent first; null while there is no query. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val results: StateFlow<List<SearchResult>?> = combine(_query.debounce(150), _documents, texts) { q, docs, t ->
        if (q.isBlank() || docs == null) null
        else docs.mapNotNull { d -> TextSearch.match(q, d.name, t[d.uri.toString()].orEmpty())?.let { SearchResult(d, it) } }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { repository.version.collect { refresh() } }
    }

    fun setQuery(q: String) { _query.value = q }

    fun refresh() {
        viewModelScope.launch {
            val docs = runCatching { repository.list() }.getOrDefault(emptyList())
            _documents.value = docs
            texts.value = runCatching { index.sync(docs) }.getOrDefault(texts.value)
        }
    }

    fun delete(doc: SavedDocument) {
        viewModelScope.launch { runCatching { repository.delete(doc) } }
    }
}
