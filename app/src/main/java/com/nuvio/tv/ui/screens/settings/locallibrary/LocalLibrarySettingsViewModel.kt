package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.locallibrary.LocalLibraryManager
import com.nuvio.tv.data.locallibrary.match.MediaMatcher
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import com.nuvio.tv.domain.model.locallibrary.SourceKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalLibrarySettingsViewModel @Inject constructor(
    private val manager: LocalLibraryManager,
    private val matcher: MediaMatcher
) : ViewModel() {

    data class UiState(
        val sources: List<LocalLibrarySourceConfig> = emptyList(),
        val progress: Map<String, LocalLibraryManager.ScanProgress> = emptyMap()
    )

    val uiState: StateFlow<UiState> = combine(
        manager.sources,
        manager.scanProgress
    ) { sources, progress -> UiState(sources, progress) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val _addResult = MutableStateFlow<AddResult?>(null)
    val addResult: StateFlow<AddResult?> = _addResult.asStateFlow()

    private val _unmatched = MutableStateFlow<List<ScannedItem>>(emptyList())
    val unmatched: StateFlow<List<ScannedItem>> = _unmatched.asStateFlow()

    private val _candidates = MutableStateFlow<List<TmdbDiscoverResult>>(emptyList())
    val candidates: StateFlow<List<TmdbDiscoverResult>> = _candidates.asStateFlow()

    fun addJellyfin(displayName: String, url: String, username: String, password: String) {
        viewModelScope.launch {
            val result = manager.addJellyfin(displayName.ifBlank { url }, url, username, password)
            _addResult.value = if (result.isSuccess) AddResult.Success
            else AddResult.Failure(result.exceptionOrNull()?.message ?: "Failed")
        }
    }

    fun addSmb(displayName: String, url: String, username: String?, password: String?, domain: String?) {
        viewModelScope.launch {
            val result = manager.addSmb(displayName.ifBlank { url }, url, username, password, domain)
            _addResult.value = if (result.isSuccess) AddResult.Success
            else AddResult.Failure(result.exceptionOrNull()?.message ?: "Failed")
        }
    }

    fun addLocalFile(displayName: String, treeUri: String) {
        viewModelScope.launch {
            val result = manager.addLocalFile(displayName.ifBlank { "Local Files" }, treeUri)
            _addResult.value = if (result.isSuccess) AddResult.Success
            else AddResult.Failure(result.exceptionOrNull()?.message ?: "Failed")
        }
    }

    fun clearAddResult() {
        _addResult.value = null
    }

    fun rescan(sourceId: String) = manager.rescan(sourceId)

    fun setEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch { manager.setEnabled(sourceId, enabled) }
    }

    fun removeSource(sourceId: String) {
        viewModelScope.launch { manager.removeSource(sourceId) }
    }

    fun loadUnmatched(sourceId: String) {
        viewModelScope.launch { _unmatched.value = manager.unmatchedItems(sourceId) }
    }

    fun loadCandidates(item: ScannedItem) {
        viewModelScope.launch { _candidates.value = matcher.candidates(item) }
    }

    fun pickCandidate(item: ScannedItem, candidate: TmdbDiscoverResult, contentType: ContentType) {
        viewModelScope.launch {
            matcher.setOverride(item, candidate.id, contentType)
            _unmatched.value = manager.unmatchedItems(item.sourceId)
        }
    }

    fun findUnmatchedItem(sourceId: String, itemKey: String): ScannedItem? =
        _unmatched.value.firstOrNull { it.itemKey == itemKey && it.sourceId == sourceId }

    fun kindLabel(kind: SourceKind): String = when (kind) {
        SourceKind.JELLYFIN -> "Jellyfin"
        SourceKind.SMB -> "SMB"
        SourceKind.LOCAL_FILE -> "On-device"
    }

    sealed class AddResult {
        object Success : AddResult()
        data class Failure(val message: String) : AddResult()
    }
}
