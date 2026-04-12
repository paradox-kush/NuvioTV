package com.nuvio.tv.core.anime

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Fetches the consolidated mappings file from the PlexAniBridge-Mappings repo.
 * This is the best community source for per-episode absolute-numbering offsets
 * (long-runners like One Piece, Naruto, Detective Conan, Dragon Ball).
 *
 * The file is a JSON object keyed by AniList id. Each entry contains the
 * cross-reference IDs plus a `tvdb_mappings` sub-object whose keys are TVDB
 * season+episode ranges and whose values are the corresponding AniList episode
 * ranges. See https://github.com/eliasbenb/PlexAniBridge-Mappings for the
 * authoritative spec. Schema assumptions in [AnimeMappingEntryDto] below are
 * deliberately lenient; unknown keys are ignored by Moshi.
 *
 * The file is downloaded lazily on first use and cached on disk for 24 h.
 */
interface AnimeMappingsApi {
    @GET
    suspend fun getMappings(@Url url: String): Response<Map<String, AnimeMappingEntryDto>>
}

/**
 * PlexAniBridge entry. Two fields — [malIdRaw] and [imdbIdRaw] — can arrive as
 * either a single value (`"tt0388629"` / `21`) or a list (`["tt0213338", "tt0889816"]`
 * / `[1, 4037, 17205]`) when one AniList entry aggregates several tracker-side
 * entries (e.g. compilation movies). We accept both shapes via [Any] and
 * normalise at read time; when a list is present we pick the first element,
 * which is the canonical entry for writes.
 */
@JsonClass(generateAdapter = true)
data class AnimeMappingEntryDto(
    @Json(name = "anidb_id") val anidbId: Int? = null,
    @Json(name = "anilist_id") val anilistId: Int? = null,
    @Json(name = "mal_id") val malIdRaw: Any? = null,
    @Json(name = "kitsu_id") val kitsuId: Int? = null,
    @Json(name = "imdb_id") val imdbIdRaw: Any? = null,
    @Json(name = "tmdb_movie_id") val tmdbMovieIdRaw: Any? = null,
    @Json(name = "tmdb_show_id") val tmdbShowId: Int? = null,
    @Json(name = "tvdb_id") val tvdbId: Int? = null,
    @Json(name = "tvdb_mappings") val tvdbMappings: Map<String, String>? = null,
    @Json(name = "tmdb_mappings") val tmdbMappings: Map<String, String>? = null,
    @Json(name = "length") val length: Int? = null
) {
    val malId: Int?
        get() = when (val raw = malIdRaw) {
            is Number -> raw.toInt()
            is List<*> -> (raw.firstOrNull() as? Number)?.toInt()
            else -> null
        }

    val imdbId: String?
        get() = when (val raw = imdbIdRaw) {
            is String -> raw.trim().takeIf { it.isNotBlank() }
            is List<*> -> raw.firstOrNull { it is String && it.isNotBlank() } as? String
            else -> null
        }

    /** All IMDb ids when the raw value was a list — used for full-coverage indexing. */
    val imdbIds: List<String>
        get() = when (val raw = imdbIdRaw) {
            is String -> raw.trim().takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            is List<*> -> raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }

    val tmdbMovieId: Int?
        get() = when (val raw = tmdbMovieIdRaw) {
            is Number -> raw.toInt()
            is List<*> -> (raw.firstOrNull() as? Number)?.toInt()
            else -> null
        }

    /** All TMDB movie ids when the raw value was a list. */
    val tmdbMovieIds: List<Int>
        get() = when (val raw = tmdbMovieIdRaw) {
            is Number -> listOf(raw.toInt())
            is List<*> -> raw.filterIsInstance<Number>().map { it.toInt() }
            else -> emptyList()
        }
}

object AnimeMappingsSource {
    /**
     * Mirror of the PlexAniBridge-Mappings consolidated file. Single JSON blob,
     * ~2 MB, updated frequently. Raw GitHub is fine for reads with no auth.
     * Default branch is `master`, not `main` — fetching from `main` returns 404.
     */
    const val PLEX_ANI_BRIDGE_URL =
        "https://raw.githubusercontent.com/eliasbenb/PlexAniBridge-Mappings/master/mappings.json"
}
