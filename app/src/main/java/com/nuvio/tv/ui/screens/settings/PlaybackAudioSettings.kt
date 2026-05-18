@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors

internal fun LazyListScope.trailerAndAudioSettingsItems(
    playerSettings: PlayerSettings,
    trailerSettings: TrailerSettings,
    onShowAudioLanguageDialog: () -> Unit,
    onShowSecondaryAudioLanguageDialog: () -> Unit,
    onShowDecoderPriorityDialog: () -> Unit,
    onShowMpvHardwareDecodeModeDialog: () -> Unit,
    onSetTrailerEnabled: (Boolean) -> Unit,
    onSetTrailerDelaySeconds: (Int) -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetRememberAudioDelayPerDevice: (Boolean) -> Unit,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetMapDV7ToHevc: (Boolean) -> Unit,
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
        item(key = "audio_trailer_section_header") {
            Text(
                text = stringResource(R.string.audio_trailer_section),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item(key = "audio_trailer_enabled") {
            ToggleSettingsItem(
                icon = Icons.Default.PlayCircle,
                title = stringResource(R.string.audio_autoplay_trailers),
                subtitle = stringResource(R.string.audio_autoplay_trailers_sub),
                isChecked = trailerSettings.enabled,
                onCheckedChange = onSetTrailerEnabled,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }

        if (trailerSettings.enabled) {
            item(key = "audio_trailer_delay") {
                SliderSettingsItem(
                    icon = Icons.Default.Timer,
                    title = stringResource(R.string.audio_trailer_delay),
                    value = trailerSettings.delaySeconds,
                    valueText = "${trailerSettings.delaySeconds}s",
                    minValue = 3,
                    maxValue = 15,
                    step = 1,
                    onValueChange = onSetTrailerDelaySeconds,
                    onFocused = onItemFocused,
                    enabled = enabled
                )
            }
        }
    }

    item(key = "audio_header") {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.audio_section),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item(key = "audio_passthrough_info") {
        Text(
            text = stringResource(R.string.audio_passthrough_info),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item(key = "audio_preferred_language") {
        val audioLangName = when (playerSettings.preferredAudioLanguage) {
            AudioLanguageOption.DEFAULT -> stringResource(R.string.audio_lang_default)
            AudioLanguageOption.DEVICE -> stringResource(R.string.audio_lang_device)
            AudioLanguageOption.ORIGINAL -> stringResource(R.string.audio_lang_original)
            else -> AVAILABLE_SUBTITLE_LANGUAGES.find {
                it.code == playerSettings.preferredAudioLanguage
            }?.displayName ?: playerSettings.preferredAudioLanguage
        }

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.audio_preferred_lang),
            subtitle = audioLangName,
            onClick = onShowAudioLanguageDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_secondary_preferred_language") {
        val secondaryAudioLangName = playerSettings.secondaryPreferredAudioLanguage?.let { code ->
            AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.displayName ?: code
        } ?: stringResource(R.string.sub_not_set)

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.sub_secondary_lang),
            subtitle = secondaryAudioLangName,
            onClick = onShowSecondaryAudioLanguageDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_skip_silence") {
        ToggleSettingsItem(
            icon = Icons.Default.Speed,
            title = stringResource(R.string.audio_skip_silence),
            subtitle = stringResource(R.string.audio_skip_silence_sub),
            isChecked = playerSettings.skipSilence,
            onCheckedChange = onSetSkipSilence,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_remember_delay_per_device") {
        ToggleSettingsItem(
            icon = Icons.Default.Timer,
            title = stringResource(R.string.audio_remember_delay_per_device),
            subtitle = stringResource(R.string.audio_remember_delay_per_device_sub),
            isChecked = playerSettings.rememberAudioDelayPerDevice,
            onCheckedChange = onSetRememberAudioDelayPerDevice,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_advanced_header") {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.audio_advanced_section),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item(key = "audio_advanced_warning") {
        Text(
            text = stringResource(R.string.audio_advanced_warning),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFF9800),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item(key = "audio_decoder_priority") {
        val decoderName = when (playerSettings.decoderPriority) {
            0 -> stringResource(R.string.audio_decoder_device_only)
            1 -> stringResource(R.string.audio_decoder_prefer_device)
            2 -> stringResource(R.string.audio_decoder_prefer_app)
            else -> stringResource(R.string.audio_decoder_prefer_device)
        }

        NavigationSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.audio_decoder_priority),
            subtitle = decoderName,
            onClick = onShowDecoderPriorityDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_tunneled_playback") {
        ToggleSettingsItem(
            icon = Icons.Default.VolumeUp,
            title = stringResource(R.string.audio_tunneled),
            subtitle = stringResource(R.string.audio_tunneled_sub),
            isChecked = playerSettings.tunnelingEnabled,
            onCheckedChange = onSetTunnelingEnabled,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_dv7_hevc_fallback") {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.audio_dv_title),
            subtitle = stringResource(R.string.audio_dv_sub),
            isChecked = playerSettings.mapDV7ToHevc,
            onCheckedChange = onSetMapDV7ToHevc,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_mpv_hardware_decode_mode") {
        val hwDecodeModeName = when (playerSettings.mpvHardwareDecodeMode) {
            MpvHardwareDecodeMode.LEGACY_DIRECT_COPY -> stringResource(R.string.audio_mpv_hwdec_legacy_direct_copy)
            MpvHardwareDecodeMode.AUTO_SAFE -> stringResource(R.string.audio_mpv_hwdec_auto_safe)
            MpvHardwareDecodeMode.HARDWARE_COPY -> stringResource(R.string.audio_mpv_hwdec_hardware_copy)
            MpvHardwareDecodeMode.HARDWARE_DIRECT -> stringResource(R.string.audio_mpv_hwdec_hardware_direct)
            MpvHardwareDecodeMode.DISABLED -> stringResource(R.string.audio_mpv_hwdec_disabled)
        }

        NavigationSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.audio_mpv_hwdec_title),
            subtitle = hwDecodeModeName,
            onClick = onShowMpvHardwareDecodeModeDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }
}

@Composable
internal fun AudioSettingsDialogs(
    showAudioLanguageDialog: Boolean,
    showSecondaryAudioLanguageDialog: Boolean,
    showDecoderPriorityDialog: Boolean,
    showMpvHardwareDecodeModeDialog: Boolean,
    selectedLanguage: String,
    selectedSecondaryLanguage: String?,
    selectedPriority: Int,
    selectedMpvHardwareDecodeMode: MpvHardwareDecodeMode,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetSecondaryPreferredAudioLanguage: (String?) -> Unit,
    onSetDecoderPriority: (Int) -> Unit,
    onSetMpvHardwareDecodeMode: (MpvHardwareDecodeMode) -> Unit,
    onDismissAudioLanguageDialog: () -> Unit,
    onDismissSecondaryAudioLanguageDialog: () -> Unit,
    onDismissDecoderPriorityDialog: () -> Unit,
    onDismissMpvHardwareDecodeModeDialog: () -> Unit
) {
    if (showAudioLanguageDialog) {
        AudioLanguageSelectionDialog(
            selectedLanguage = selectedLanguage,
            onLanguageSelected = {
                onSetPreferredAudioLanguage(it)
                onDismissAudioLanguageDialog()
            },
            onDismiss = onDismissAudioLanguageDialog
        )
    }

    if (showSecondaryAudioLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.sub_secondary_lang),
            selectedLanguage = selectedSecondaryLanguage,
            showNoneOption = true,
            onLanguageSelected = {
                onSetSecondaryPreferredAudioLanguage(it)
                onDismissSecondaryAudioLanguageDialog()
            },
            onDismiss = onDismissSecondaryAudioLanguageDialog
        )
    }

    if (showDecoderPriorityDialog) {
        DecoderPriorityDialog(
            selectedPriority = selectedPriority,
            onPrioritySelected = {
                onSetDecoderPriority(it)
                onDismissDecoderPriorityDialog()
            },
            onDismiss = onDismissDecoderPriorityDialog
        )
    }

    if (showMpvHardwareDecodeModeDialog) {
        MpvHardwareDecodeModeDialog(
            selectedMode = selectedMpvHardwareDecodeMode,
            onModeSelected = {
                onSetMpvHardwareDecodeMode(it)
                onDismissMpvHardwareDecodeModeDialog()
            },
            onDismiss = onDismissMpvHardwareDecodeModeDialog
        )
    }
}

@Composable
private fun AudioLanguageSelectionDialog(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val specialOptions = listOf(
        AudioLanguageOption.DEFAULT to stringResource(R.string.audio_lang_default),
        AudioLanguageOption.DEVICE to stringResource(R.string.audio_lang_device),
        AudioLanguageOption.ORIGINAL to stringResource(R.string.audio_lang_original)
    )
    val originalHint = stringResource(R.string.audio_lang_original_hint)
    val allOptions = specialOptions.map { (code, name) ->
        SettingsPickerOption(
            value = code,
            title = name,
            description = if (code == AudioLanguageOption.ORIGINAL) originalHint else null
        )
    } + AVAILABLE_SUBTITLE_LANGUAGES.sortedBy { it.displayName.lowercase() }.map {
        SettingsPickerOption(
            value = it.code,
            title = it.displayName,
            trailing = it.code.uppercase()
        )
    }

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_preferred_lang),
        options = allOptions,
        selectedValue = selectedLanguage,
        onOptionSelected = onLanguageSelected,
        onDismiss = onDismiss,
        width = 400.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun MpvHardwareDecodeModeDialog(
    selectedMode: MpvHardwareDecodeMode,
    onModeSelected: (MpvHardwareDecodeMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            MpvHardwareDecodeMode.AUTO_SAFE,
            stringResource(R.string.audio_mpv_hwdec_auto_safe),
            stringResource(R.string.audio_mpv_hwdec_auto_safe_desc)
        ),
        SettingsPickerOption(
            MpvHardwareDecodeMode.HARDWARE_COPY,
            stringResource(R.string.audio_mpv_hwdec_hardware_copy),
            stringResource(R.string.audio_mpv_hwdec_hardware_copy_desc)
        ),
        SettingsPickerOption(
            MpvHardwareDecodeMode.HARDWARE_DIRECT,
            stringResource(R.string.audio_mpv_hwdec_hardware_direct),
            stringResource(R.string.audio_mpv_hwdec_hardware_direct_desc)
        ),
        SettingsPickerOption(
            MpvHardwareDecodeMode.DISABLED,
            stringResource(R.string.audio_mpv_hwdec_disabled),
            stringResource(R.string.audio_mpv_hwdec_disabled_desc)
        ),
        SettingsPickerOption(
            MpvHardwareDecodeMode.LEGACY_DIRECT_COPY,
            stringResource(R.string.audio_mpv_hwdec_legacy_direct_copy),
            stringResource(R.string.audio_mpv_hwdec_legacy_direct_copy_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_mpv_hwdec_title),
        subtitle = stringResource(R.string.audio_mpv_hwdec_dialog_subtitle),
        options = options,
        selectedValue = selectedMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 360.dp
    )
}

@Composable
internal fun DecoderPriorityDialog(
    selectedPriority: Int,
    onPrioritySelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(0, stringResource(R.string.audio_decoder_device_only), stringResource(R.string.audio_decoder_device_only_desc)),
        SettingsPickerOption(1, stringResource(R.string.audio_decoder_prefer_device), stringResource(R.string.audio_decoder_prefer_device_desc)),
        SettingsPickerOption(2, stringResource(R.string.audio_decoder_prefer_app), stringResource(R.string.audio_decoder_prefer_app_desc))
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_decoder_priority),
        subtitle = stringResource(R.string.audio_decoder_controls),
        options = options,
        selectedValue = selectedPriority,
        onOptionSelected = onPrioritySelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}
