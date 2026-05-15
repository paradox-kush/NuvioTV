package com.nuvio.tv.domain.model.locallibrary

import androidx.compose.runtime.Immutable

/**
 * What a [com.nuvio.tv.data.locallibrary.source.LocalLibrarySource] returns when
 * asked to make an item playable. The gateway converts this into a [com.nuvio.tv.domain.model.Stream].
 */
@Immutable
data class ResolvedStream(
    val url: String,
    /** Extra headers required for playback (e.g. Jellyfin auth bearer). */
    val headers: Map<String, String> = emptyMap(),
    val mimeHint: String? = null,
    /** "http", "https", "smb", "content", "file" — used to route the right ExoPlayer DataSource. */
    val scheme: String,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null
)
