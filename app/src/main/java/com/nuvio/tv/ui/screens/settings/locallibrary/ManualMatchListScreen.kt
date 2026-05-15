@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun ManualMatchListScreen(
    sourceId: String,
    onBackPress: () -> Unit,
    onNavigateToPicker: (sourceId: String, itemKey: String) -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
    LaunchedEffect(sourceId) { viewModel.loadUnmatched(sourceId) }
    val unmatched by viewModel.unmatched.collectAsStateWithLifecycle()

    SettingsStandaloneScaffold(
        title = "Manual Match",
        subtitle = "Files that auto-matching couldn't confidently resolve."
    ) {
        SettingsDetailHeader(
            title = "Unmatched (${unmatched.size})",
            subtitle = "Pick a file to choose its TMDB match. Manual picks persist across rescans."
        )

        if (unmatched.isEmpty()) {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Nothing to do — every file is matched.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }
        } else {
            SettingsGroupCard(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(unmatched, key = { it.itemKey }) { item ->
                        SettingsActionRow(
                            title = item.fileName,
                            subtitle = item.parsedTitle?.let { p ->
                                buildString {
                                    append(p)
                                    item.parsedYear?.let { append(" · ", it.toString()) }
                                    item.parsedSeason?.let { s ->
                                        append(" · S", s)
                                        item.parsedEpisode?.let { e -> append("E", e) }
                                    }
                                }
                            },
                            onClick = { onNavigateToPicker(item.sourceId, item.itemKey) }
                        )
                    }
                }
            }
        }
    }
}
