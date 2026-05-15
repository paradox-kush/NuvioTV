@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun ManualMatchPickerScreen(
    sourceId: String,
    itemKey: String,
    onBackPress: () -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
    LaunchedEffect(sourceId, itemKey) {
        viewModel.loadUnmatched(sourceId)
    }
    val unmatched by viewModel.unmatched.collectAsStateWithLifecycle()
    val item: ScannedItem? = unmatched.firstOrNull { it.itemKey == itemKey }

    LaunchedEffect(item) { item?.let { viewModel.loadCandidates(it) } }
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()

    if (item == null) {
        SettingsStandaloneScaffold(title = "Pick match", subtitle = "") {
            Text("Item no longer in unmatched list.", color = NuvioColors.TextSecondary)
        }
        return
    }

    val type = item.typeHint.takeIf { it != ContentType.UNKNOWN } ?: ContentType.MOVIE

    SettingsStandaloneScaffold(
        title = "Pick match",
        subtitle = item.fileName
    ) {
        SettingsDetailHeader(
            title = "Top TMDB results",
            subtitle = "Selecting one stores a permanent override for this file."
        )
        SettingsGroupCard(modifier = Modifier.fillMaxSize()) {
            if (candidates.isEmpty()) {
                Text(
                    text = "No candidates returned. Try renaming the file with a clear title and year.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(8.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(candidates, key = { it.id }) { candidate ->
                        val title = candidate.title ?: candidate.name ?: "Untitled"
                        val year = (candidate.releaseDate ?: candidate.firstAirDate)?.take(4)
                        SettingsActionRow(
                            title = title,
                            subtitle = buildString {
                                year?.let { append(it) }
                                candidate.overview?.takeIf { it.isNotBlank() }?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append(it.take(120))
                                }
                            }.takeIf { it.isNotBlank() },
                            onClick = {
                                viewModel.pickCandidate(item, candidate, type)
                                onBackPress()
                            }
                        )
                    }
                }
            }
        }
    }
}
