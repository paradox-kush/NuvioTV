package com.nuvio.tv.domain.model.locallibrary

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.ContentType

/**
 * A single media file discovered by scanning a [LocalLibrarySourceConfig].
 *
 * This is the pre-match record. TMDB resolution happens later via MediaMatcher,
 * either by following a strong server-side hint (Jellyfin `ProviderIds.Tmdb`)
 * or by parsing the filename and searching TMDB.
 */
@Immutable
data class ScannedItem(
    val sourceId: String,
    /** Path relative to the source's root, used for stable identity. */
    val relativePath: String,
    val fileName: String,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    /** Jellyfin item id if the source backend already knows it. */
    val sourceItemId: String? = null,
    /** TMDB id if the source already resolved it (e.g. Jellyfin ProviderIds.Tmdb). */
    val tmdbHintId: Int? = null,
    /** Filename-derived hints — populated by FilenameParser, may be overridden by matcher. */
    val parsedTitle: String? = null,
    val parsedYear: Int? = null,
    val parsedSeason: Int? = null,
    val parsedEpisode: Int? = null,
    val typeHint: ContentType = ContentType.UNKNOWN,
    /** Optional alternative stream URL the source can produce without re-querying. */
    val directStreamUrl: String? = null
) {
    /** Stable key used as the storage key in MatchOverrideStore. */
    val itemKey: String get() = "$sourceId|$relativePath"

    /** Synthetic local-library id, used as the externally-visible id for this item. */
    val localId: String get() = "$LOCAL_ID_PREFIX$sourceId:${relativePath.encodePath()}"

    companion object {
        const val LOCAL_ID_PREFIX = "nuvio-local:"
    }
}

/** Stable, reversible-ish encoding of a relative path for use inside an id string. */
private fun String.encodePath(): String = replace("/", "%2F").replace(":", "%3A")
