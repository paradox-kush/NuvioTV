package com.nuvio.tv.data.locallibrary.source

import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.ResolvedStream
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import kotlinx.coroutines.flow.Flow

/**
 * A single backend that can enumerate media files and resolve them to a
 * playable URL. Implementations are constructed per-source-config via
 * [LocalLibrarySourceFactory] and live for the duration of a scan/resolve call.
 */
sealed interface LocalLibrarySource {
    val config: LocalLibrarySourceConfig

    /** Streams discovered items. Cold flow — collection triggers the scan. */
    fun scan(): Flow<ScannedItem>

    /** Produces a URL/headers ExoPlayer can play. Null if the item is no longer reachable. */
    suspend fun resolveStream(item: ScannedItem): ResolvedStream?

    /** Best-effort connectivity / credential check used by the Add Source UI. */
    suspend fun testConnection(): Result<Unit>
}
