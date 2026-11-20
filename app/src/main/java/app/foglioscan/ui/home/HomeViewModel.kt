package app.foglioscan.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.foglioscan.data.DocumentRepository
import app.foglioscan.data.SavedDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    val repository = DocumentRepository.get(app)

    private val _documents = MutableStateFlow<List<SavedDocument>?>(null)
    val documents: StateFlow<List<SavedDocument>?> = _documents.asStateFlow()

    init {
        viewModelScope.launch { repository.version.collect { refresh() } }
    }

    fun refresh() {
        viewModelScope.launch { _documents.value = runCatching { repository.list() }.getOrDefault(emptyList()) }
    }

    fun delete(doc: SavedDocument) {
        viewModelScope.launch { runCatching { repository.delete(doc) } }
    }
}
