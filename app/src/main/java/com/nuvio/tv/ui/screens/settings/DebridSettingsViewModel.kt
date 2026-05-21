package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DebridFormatterConfigServer
import com.nuvio.tv.core.server.DebridFormatterSettings
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.domain.model.DEBRID_PREPARE_INSTANT_PLAYBACK_DEFAULT_LIMIT
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamCodecFilter
import com.nuvio.tv.domain.model.DebridStreamFeatureFilter
import com.nuvio.tv.domain.model.DebridStreamMinimumQuality
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamSortMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebridSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DebridSettingsDataStore,
    private val torboxApi: TorboxApi
) : ViewModel() {
    private var formatterServer: DebridFormatterConfigServer? = null
    private var logoBytes: ByteArray? = null

    private val _uiState = MutableStateFlow(DebridSettingsUiState())
    val uiState: StateFlow<DebridSettingsUiState> = _uiState.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val validationError: SharedFlow<String> = _validationError.asSharedFlow()

    init {
        loadLogoBytes()
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
    }

    private fun loadLogoBytes() {
        try {
            val inputStream = context.resources.openRawResource(R.drawable.app_logo_wordmark)
            logoBytes = inputStream.use { it.readBytes() }
        } catch (_: Exception) { }
    }

    fun onEvent(event: DebridSettingsEvent) {
        when (event) {
            is DebridSettingsEvent.ToggleEnabled -> {
                if (event.enabled && !_uiState.value.hasAnyApiKey) return
                update { dataStore.setEnabled(event.enabled) }
            }
        }
    }

    fun startFormatterQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _uiState.update { it.copy(serverError = context.getString(R.string.error_network_required)) }
            return
        }
        stopFormatterServer()
        formatterServer = DebridFormatterConfigServer.startOnAvailablePort(
            currentSettingsProvider = {
                val state = _uiState.value
                DebridFormatterSettings(
                    nameTemplate = state.streamNameTemplate,
                    descriptionTemplate = state.streamDescriptionTemplate,
                    streamPreferences = state.streamPreferences
                )
            },
            onSettingsChanged = { settings ->
                viewModelScope.launch {
                    dataStore.setStreamTemplates(
                        nameTemplate = settings.nameTemplate,
                        descriptionTemplate = settings.descriptionTemplate
                    )
                    dataStore.setStreamPreferences(settings.streamPreferences)
                }
            },
            context = context,
            logoProvider = { logoBytes }
        )
        val server = formatterServer
        if (server == null) {
            _uiState.update { it.copy(serverError = context.getString(R.string.error_server_ports_unavailable)) }
            return
        }
        val url = "http://$ip:${server.listeningPort}"
        _uiState.update {
            it.copy(
                isFormatterQrModeActive = true,
                formatterQrCodeBitmap = QrCodeGenerator.generate(url, 512),
                formatterServerUrl = url,
                serverError = null
            )
        }
    }

    fun stopFormatterQrMode() {
        stopFormatterServer()
        _uiState.update {
            it.copy(
                isFormatterQrModeActive = false,
                formatterQrCodeBitmap = null,
                formatterServerUrl = null
            )
        }
    }

    fun resetFormatterTemplates() {
        update { dataStore.resetStreamTemplates() }
    }

    fun setInstantPlaybackPreparationEnabled(enabled: Boolean) {
        val nextLimit = if (enabled) {
            DEBRID_PREPARE_INSTANT_PLAYBACK_DEFAULT_LIMIT
        } else {
            0
        }
        update { dataStore.setInstantPlaybackPreparationLimit(nextLimit) }
    }

    fun setInstantPlaybackPreparationLimit(limit: Int) {
        update { dataStore.setInstantPlaybackPreparationLimit(limit) }
    }

    fun setStreamMaxResults(maxResults: Int) {
        update { dataStore.setStreamMaxResults(maxResults) }
    }

    fun setStreamSortMode(mode: DebridStreamSortMode) {
        update { dataStore.setStreamSortMode(mode) }
    }

    fun setStreamMinimumQuality(quality: DebridStreamMinimumQuality) {
        update { dataStore.setStreamMinimumQuality(quality) }
    }

    fun setStreamDolbyVisionFilter(filter: DebridStreamFeatureFilter) {
        update { dataStore.setStreamDolbyVisionFilter(filter) }
    }

    fun setStreamHdrFilter(filter: DebridStreamFeatureFilter) {
        update { dataStore.setStreamHdrFilter(filter) }
    }

    fun setStreamCodecFilter(filter: DebridStreamCodecFilter) {
        update { dataStore.setStreamCodecFilter(filter) }
    }

    fun setStreamPreferences(preferences: DebridStreamPreferences) {
        update { dataStore.setStreamPreferences(preferences) }
    }

    fun validateAndSaveTorboxApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch { dataStore.setTorboxApiKey("") }
            onSuccess()
            return
        }
        viewModelScope.launch {
            _validating.value = true
            val valid = try {
                val response = torboxApi.getUser("Bearer $trimmed")
                response.body()?.close()
                response.errorBody()?.close()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
            _validating.value = false
            if (valid) {
                dataStore.setTorboxApiKey(trimmed)
                onSuccess()
            } else {
                _validationError.tryEmit(context.getString(R.string.debrid_invalid_torbox_api_key))
            }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }

    private fun stopFormatterServer() {
        formatterServer?.stop()
        formatterServer = null
    }

    override fun onCleared() {
        stopFormatterServer()
        super.onCleared()
    }
}

data class DebridSettingsUiState(
    val enabled: Boolean = false,
    val torboxApiKey: String = "",
    val realDebridApiKey: String = "",
    val instantPlaybackPreparationLimit: Int = 0,
    val streamMaxResults: Int = 0,
    val streamSortMode: DebridStreamSortMode = DebridStreamSortMode.DEFAULT,
    val streamMinimumQuality: DebridStreamMinimumQuality = DebridStreamMinimumQuality.ANY,
    val streamDolbyVisionFilter: DebridStreamFeatureFilter = DebridStreamFeatureFilter.ANY,
    val streamHdrFilter: DebridStreamFeatureFilter = DebridStreamFeatureFilter.ANY,
    val streamCodecFilter: DebridStreamCodecFilter = DebridStreamCodecFilter.ANY,
    val streamPreferences: DebridStreamPreferences = DebridStreamPreferences(),
    val streamNameTemplate: String = "",
    val streamDescriptionTemplate: String = "",
    val isFormatterQrModeActive: Boolean = false,
    val formatterQrCodeBitmap: Bitmap? = null,
    val formatterServerUrl: String? = null,
    val serverError: String? = null
) {
    val hasAnyApiKey: Boolean
        get() = torboxApiKey.isNotBlank()

    fun fromSettings(settings: DebridSettings): DebridSettingsUiState = copy(
        enabled = settings.enabled,
        torboxApiKey = settings.torboxApiKey,
        realDebridApiKey = settings.realDebridApiKey,
        instantPlaybackPreparationLimit = settings.instantPlaybackPreparationLimit,
        streamMaxResults = settings.streamMaxResults,
        streamSortMode = settings.streamSortMode,
        streamMinimumQuality = settings.streamMinimumQuality,
        streamDolbyVisionFilter = settings.streamDolbyVisionFilter,
        streamHdrFilter = settings.streamHdrFilter,
        streamCodecFilter = settings.streamCodecFilter,
        streamPreferences = settings.streamPreferences,
        streamNameTemplate = settings.streamNameTemplate,
        streamDescriptionTemplate = settings.streamDescriptionTemplate
    )
}

sealed class DebridSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : DebridSettingsEvent()
}
