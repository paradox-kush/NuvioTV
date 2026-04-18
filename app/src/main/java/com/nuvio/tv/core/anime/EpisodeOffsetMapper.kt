package com.nuvio.tv.core.anime

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves (TMDB show id, season, episode) → tracker entry + tracker episode
 * number, handling the two hardest cases:
 *
 * 1. Per-season-as-separate-entry: AoT S1, S2, S3 are three different MAL/
 *    AniList/Kitsu entries — pick the one that matches the TMDB season.
 * 2. Absolute numbering: One Piece S21E1072 on TMDB is `num_watched_episodes
 *    = 1072` on MAL (single entry, absolute count).
 *
 * Sources in priority order:
 * - PlexAniBridge-Mappings (if fetched) — has per-episode offset ranges for
 *   absolute-numbered shows and authoritative TMDB↔AniList cross-refs.
 * - arm.haglund.dev via [AnimeIdMapper] — coverage fallback; returns a list
 *   of entries but no per-episode offsets, so numbering assumed to restart.
 * - Heuristic: if nothing matches, return a [TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE]
 *   result with the best-effort first entry and `trackerEpisode = tmdbEpisode`.
 *   Callers should check [TrackerEpisodeMapping.hasAnyTrackerId] before writing.
 */
@Singleton
class EpisodeOffsetMapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val animeMappingsApi: AnimeMappingsApi,
    private val animeIdMapper: AnimeIdMapper,
    private val moshi: Moshi
) {
    private val mutex = Mutex()

    /** Loaded mappings keyed by AniList id (string). */
    @Volatile private var cache: Map<String, AnimeMappingEntryDto>? = null
    @Volatile private var byTmdbShowIndex: Map<Int, List<AnimeMappingEntryDto>> = emptyMap()
    @Volatile private var byTmdbMovieIndex: Map<Int, AnimeMappingEntryDto> = emptyMap()
    @Volatile private var byImdbShowIndex: Map<String, List<AnimeMappingEntryDto>> = emptyMap()

    /** Async kick-off used by StartupSyncService. Non-blocking; errors logged. */
    suspend fun warmIfStale() {
        val file = cacheFile()
        val freshCutoff = System.currentTimeMillis() - MAX_AGE_MS
        if (file.exists() && file.lastModified() > freshCutoff && cache != null) return
        loadOrRefresh()
    }

    /**
     * Convenience entry point that accepts either an IMDb id or a TMDB id.
     * Player and detail screens have IMDb far more reliably than TMDB, so
     * prefer IMDb when both are supplied — [byImdbShowIndex] is authoritative
     * when present. Falls back through ARM if neither is in the PlexAniBridge
     * file.
     */
    /**
     * Legacy single-result resolver. Kept public because a few call sites
     * legitimately want just the primary match (the one containing the
     * watched episode, not the already-completed earlier parts). For fanout
     * writes, prefer [resolveAllByIds] so Part 1 + Part 2 splits both get
     * updated.
     */
    suspend fun resolveByIds(
        imdbId: String?,
        tmdbId: Int?,
        season: Int,
        episode: Int
    ): TrackerEpisodeMapping {
        val all = resolveAllByIds(imdbId, tmdbId, season, episode)
        // Return the mapping that actually CONTAINS the watched episode (not
        // one of the completed earlier parts). `isRangeComplete=false` means
        // "this entry is still in progress at the watched episode".
        return all.firstOrNull { !it.isRangeComplete }
            ?: all.firstOrNull()
            ?: TrackerEpisodeMapping(
                tmdbId = tmdbId ?: -1,
                tmdbSeason = season,
                tmdbEpisode = episode,
                trackerEpisode = episode,
                source = TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE
            )
    }

    /**
     * Collect every tracker entry whose mapping ranges contribute to the
     * watched `(season, episode)`. Earlier parts of a split season are
     * returned with `isRangeComplete=true` (Part 1 finished when user reaches
     * Part 2 content). The containing entry is returned with
     * `isRangeComplete=false` and `trackerEpisode` = tracker-side offset.
     *
     * Resolution priority is the same as [resolveByIds]: IMDb-direct on PAB
     * → TMDB-direct on PAB → IMDb→TMDB bridge via ARM → ARM positional
     * fallback (single result) → HEURISTIC_NONE.
     */
    suspend fun resolveAllByIds(
        imdbId: String?,
        tmdbId: Int?,
        season: Int,
        episode: Int
    ): List<TrackerEpisodeMapping> {
        Log.i(TAG, "resolveAllByIds imdb=$imdbId tmdb=$tmdbId s=$season e=$episode cacheSize=${cache?.size}")
        ensureLoaded()
        Log.i(TAG, "  after ensureLoaded: cacheSize=${cache?.size} imdbIdx=${byImdbShowIndex.size} tmdbIdx=${byTmdbShowIndex.size}")

        // 1. Direct IMDb hit in PAB.
        if (!imdbId.isNullOrBlank()) {
            val pab = byImdbShowIndex[imdbId.trim()].orEmpty()
            if (pab.isNotEmpty()) {
                val matches = collectCoveringCandidates(pab, season, episode)
                if (matches.isNotEmpty()) {
                    Log.d(TAG, "  IMDb-direct PAB: ${matches.size} mapping(s)")
                    return matches.map { plexAniBridgeMapping(it, tmdbId, season, episode) }
                }
            }
        }

        // 2. Direct TMDB hit in PAB.
        if (tmdbId != null) {
            val pab = byTmdbShowIndex[tmdbId].orEmpty()
            if (pab.isNotEmpty()) {
                val matches = collectCoveringCandidates(pab, season, episode)
                if (matches.isNotEmpty()) {
                    Log.d(TAG, "  TMDB-direct PAB: ${matches.size} mapping(s)")
                    return matches.map { plexAniBridgeMapping(it, tmdbId, season, episode) }
                }
            }
        }

        // 3. IMDb → TMDB via ARM, then retry PAB TMDB lookup. Covers the
        // common case where PAB indexes by tmdb_show_id only.
        if (!imdbId.isNullOrBlank()) {
            val arm = animeIdMapper.getEntriesForImdb(imdbId)
            val tmdbFromArm = arm.firstNotNullOfOrNull { it.themoviedb }
            if (tmdbFromArm != null) {
                Log.d(TAG, "  IMDb→TMDB bridge via arm: $imdbId → $tmdbFromArm")
                val bridged = byTmdbShowIndex[tmdbFromArm].orEmpty()
                if (bridged.isNotEmpty()) {
                    val matches = collectCoveringCandidates(bridged, season, episode)
                    if (matches.isNotEmpty()) {
                        Log.d(TAG, "  bridged PAB: ${matches.size} mapping(s)")
                        return matches.map { plexAniBridgeMapping(it, tmdbFromArm, season, episode) }
                    }
                }
            }
            // ARM positional fallback — one mapping, single entry, no splits.
            if (arm.isNotEmpty()) {
                val idx = (season - 1).coerceIn(0, arm.lastIndex)
                val entry = arm[idx]
                return listOf(
                    TrackerEpisodeMapping(
                        tmdbId = entry.themoviedb ?: -1,
                        tmdbSeason = season,
                        tmdbEpisode = episode,
                        malId = entry.myanimelist,
                        anilistId = entry.anilist,
                        kitsuId = entry.kitsu,
                        anidbId = entry.anidb,
                        trackerEpisode = episode,
                        totalEpisodes = null,
                        source = TrackerEpisodeMapping.MappingSource.ARM
                    )
                )
            }
        }
        return emptyList()
    }

    private suspend fun plexAniBridgeMapping(
        match: CoveringMatch,
        tmdbId: Int?,
        season: Int,
        episode: Int
    ): TrackerEpisodeMapping {
        // PAB entries sometimes lack fields for trackers the user has connected
        // (e.g. One Piece has no kitsu_id in PAB even though ARM does). If any
        // tracker id is missing and we have an AniList id to pivot on, fill the
        // gaps via the ARM cross-reference. ARM results are memoised so this
        // costs at most one network call per show, ever.
        var malId = match.entry.malId
        var kitsuId = match.entry.kitsuId
        var anilistId = match.entry.anilistId
        var anidbId = match.entry.anidbId
        if ((malId == null || kitsuId == null) && anilistId != null) {
            val arm = animeIdMapper.resolveFromTracker(AnimeIdMapper.TrackerSource.ANILIST, anilistId)
            if (arm != null) {
                if (malId == null) malId = arm.myanimelist
                if (kitsuId == null) kitsuId = arm.kitsu
                if (anidbId == null) anidbId = arm.anidb
            }
        }
        return TrackerEpisodeMapping(
            tmdbId = match.entry.tmdbShowId ?: tmdbId ?: -1,
            tmdbSeason = season,
            tmdbEpisode = episode,
            malId = malId,
            anilistId = anilistId,
            kitsuId = kitsuId,
            anidbId = anidbId,
            trackerEpisode = match.trackerEpisode,
            totalEpisodes = match.entry.length,
            source = TrackerEpisodeMapping.MappingSource.PLEX_ANI_BRIDGE,
            isRangeComplete = match.isRangeComplete
        )
    }

    /**
     * Single-mapping TMDB-only resolver. Kept for callers that don't have
     * an IMDb id (e.g. the movie flow). For split-season coverage use
     * [resolveAllByIds] instead.
     */
    suspend fun resolve(
        tmdbId: Int,
        tmdbSeason: Int,
        tmdbEpisode: Int
    ): TrackerEpisodeMapping {
        ensureLoaded()
        val candidates = byTmdbShowIndex[tmdbId].orEmpty()
        if (candidates.isNotEmpty()) {
            val matches = collectCoveringCandidates(candidates, tmdbSeason, tmdbEpisode)
            // Prefer the "containing" match (still in progress) over earlier
            // completed parts — callers want the currently-active entry.
            val primary = matches.firstOrNull { !it.isRangeComplete } ?: matches.firstOrNull()
            if (primary != null) {
                return TrackerEpisodeMapping(
                    tmdbId = tmdbId,
                    tmdbSeason = tmdbSeason,
                    tmdbEpisode = tmdbEpisode,
                    malId = primary.entry.malId,
                    anilistId = primary.entry.anilistId,
                    kitsuId = primary.entry.kitsuId,
                    anidbId = primary.entry.anidbId,
                    trackerEpisode = primary.trackerEpisode,
                    totalEpisodes = primary.entry.length,
                    source = TrackerEpisodeMapping.MappingSource.PLEX_ANI_BRIDGE,
                    isRangeComplete = primary.isRangeComplete
                )
            }
        }
        // ARM fallback — one entry per anime season/cour, no episode offsets.
        val armEntries = animeIdMapper.getEntriesForTmdb(tmdbId)
        if (armEntries.isNotEmpty()) {
            val idx = (tmdbSeason - 1).coerceIn(0, armEntries.lastIndex)
            val entry = armEntries[idx]
            return TrackerEpisodeMapping(
                tmdbId = tmdbId,
                tmdbSeason = tmdbSeason,
                tmdbEpisode = tmdbEpisode,
                malId = entry.myanimelist,
                anilistId = entry.anilist,
                kitsuId = entry.kitsu,
                anidbId = entry.anidb,
                trackerEpisode = tmdbEpisode,
                totalEpisodes = null,
                source = TrackerEpisodeMapping.MappingSource.ARM
            )
        }
        return TrackerEpisodeMapping(
            tmdbId = tmdbId,
            tmdbSeason = tmdbSeason,
            tmdbEpisode = tmdbEpisode,
            trackerEpisode = tmdbEpisode,
            source = TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE
        )
    }

    /**
     * [resolveWholeShow] variant that accepts either id. Prefers IMDb because
     * player/detail have it more reliably; falls through to TMDB then ARM.
     */
    suspend fun resolveWholeShowByIds(imdbId: String?, tmdbId: Int?): TrackerShowMapping {
        ensureLoaded()
        if (!imdbId.isNullOrBlank()) {
            val pab = byImdbShowIndex[imdbId.trim()].orEmpty()
            if (pab.isNotEmpty()) {
                return TrackerShowMapping(
                    tmdbId = pab.firstOrNull()?.tmdbShowId ?: tmdbId ?: -1,
                    entries = pab.map {
                        TrackerShowEntry(
                            tmdbSeason = firstTvdbSeason(it),
                            malId = it.malId,
                            anilistId = it.anilistId,
                            kitsuId = it.kitsuId,
                            anidbId = it.anidbId,
                            totalEpisodes = it.length
                        )
                    },
                    source = TrackerEpisodeMapping.MappingSource.PLEX_ANI_BRIDGE
                )
            }
        }
        if (tmdbId != null) return resolveWholeShow(tmdbId)
        if (!imdbId.isNullOrBlank()) {
            val arm = animeIdMapper.getEntriesForImdb(imdbId)
            if (arm.isNotEmpty()) {
                return TrackerShowMapping(
                    tmdbId = arm.firstOrNull()?.themoviedb ?: -1,
                    entries = arm.mapIndexed { idx, e ->
                        TrackerShowEntry(
                            tmdbSeason = idx + 1,
                            malId = e.myanimelist,
                            anilistId = e.anilist,
                            kitsuId = e.kitsu,
                            anidbId = e.anidb
                        )
                    },
                    source = TrackerEpisodeMapping.MappingSource.ARM
                )
            }
        }
        return TrackerShowMapping(
            tmdbId = tmdbId ?: -1,
            entries = emptyList(),
            source = TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE
        )
    }

    /**
     * Every tracker entry that belongs to a TMDB show — used for "mark full
     * show watched" to iterate and complete each entry.
     */
    suspend fun resolveWholeShow(tmdbId: Int): TrackerShowMapping {
        ensureLoaded()
        val pab = byTmdbShowIndex[tmdbId].orEmpty()
        if (pab.isNotEmpty()) {
            return TrackerShowMapping(
                tmdbId = tmdbId,
                entries = pab.map {
                    TrackerShowEntry(
                        tmdbSeason = firstTvdbSeason(it),
                        malId = it.malId,
                        anilistId = it.anilistId,
                        kitsuId = it.kitsuId,
                        anidbId = it.anidbId,
                        totalEpisodes = it.length
                    )
                },
                source = TrackerEpisodeMapping.MappingSource.PLEX_ANI_BRIDGE
            )
        }
        val arm = animeIdMapper.getEntriesForTmdb(tmdbId)
        if (arm.isNotEmpty()) {
            return TrackerShowMapping(
                tmdbId = tmdbId,
                entries = arm.mapIndexed { idx, e ->
                    TrackerShowEntry(
                        tmdbSeason = idx + 1,
                        malId = e.myanimelist,
                        anilistId = e.anilist,
                        kitsuId = e.kitsu,
                        anidbId = e.anidb,
                        totalEpisodes = null
                    )
                },
                source = TrackerEpisodeMapping.MappingSource.ARM
            )
        }
        return TrackerShowMapping(
            tmdbId = tmdbId,
            entries = emptyList(),
            source = TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE
        )
    }

    /**
     * Movie-specific resolution — TMDB movie id → single tracker entry.
     * Falls through the same priority chain as [resolve].
     */
    suspend fun resolveMovie(tmdbMovieId: Int): TrackerShowEntry? {
        ensureLoaded()
        byTmdbMovieIndex[tmdbMovieId]?.let {
            return TrackerShowEntry(
                malId = it.malId,
                anilistId = it.anilistId,
                kitsuId = it.kitsuId,
                anidbId = it.anidbId,
                totalEpisodes = it.length ?: 1
            )
        }
        // arm's /themoviedb returns for both movies and shows — try it.
        val arm = animeIdMapper.getEntriesForTmdb(tmdbMovieId)
        val first = arm.firstOrNull() ?: return null
        return TrackerShowEntry(
            malId = first.myanimelist,
            anilistId = first.anilist,
            kitsuId = first.kitsu,
            anidbId = first.anidb,
            totalEpisodes = 1
        )
    }

    // --- internal --- //

    /** Backs off failed loads so we don't hammer GitHub on every episode watched. */
    @Volatile private var lastFailedLoadAtMs: Long = 0L

    private suspend fun ensureLoaded() {
        if (cache != null && cache!!.isNotEmpty()) return
        // Previous attempt may have returned an empty map (sentinel). Retry
        // unless we failed very recently.
        if (System.currentTimeMillis() - lastFailedLoadAtMs < FAILED_BACKOFF_MS) return
        loadOrRefresh()
    }

    private suspend fun loadOrRefresh() = mutex.withLock {
        if (cache != null && cache!!.isNotEmpty()) return@withLock
        val file = cacheFile()
        val freshCutoff = System.currentTimeMillis() - MAX_AGE_MS
        val fromDisk = if (file.exists() && file.lastModified() > freshCutoff) {
            Log.i(TAG, "reading cached mappings from ${file.absolutePath} (${file.length()} bytes)")
            readFromFile(file).also {
                if (it == null) Log.w(TAG, "failed to parse cached mappings — will re-download")
                else Log.i(TAG, "parsed ${it.size} entries from disk cache")
            }
        } else null

        val loaded: Map<String, AnimeMappingEntryDto>? = fromDisk ?: run {
            val fetched = download()
            if (fetched != null) {
                runCatching { writeToFile(file, fetched) }.onFailure {
                    Log.w(TAG, "failed to persist mappings: ${it.message}")
                }
                fetched
            } else {
                // Network failed — fall back to whatever stale copy we have.
                if (file.exists()) {
                    Log.w(TAG, "download failed — trying stale cache at ${file.absolutePath}")
                    readFromFile(file)
                } else null
            }
        }
        // The outer JSON object is keyed by AniList id (`"182205": { ... }`).
        // Entries themselves don't carry `anilist_id` — pull it from the map key.
        val hydrated = loaded?.mapValues { (keyStr, entry) ->
            if (entry.anilistId != null) entry
            else entry.copy(anilistId = keyStr.toIntOrNull())
        }
        if (hydrated != null && hydrated.isNotEmpty()) {
            cache = hydrated
            val values = hydrated.values
            byTmdbShowIndex = values
                .filter { it.tmdbShowId != null }
                .groupBy { it.tmdbShowId!! }
            byTmdbMovieIndex = values
                .flatMap { entry -> entry.tmdbMovieIds.map { it to entry } }
                .associate { it }
            byImdbShowIndex = values
                .flatMap { entry -> entry.imdbIds.map { it to entry } }
                .groupBy({ it.first }, { it.second })
            Log.i(TAG, "loaded ${hydrated.size} anime mappings (shows=${byTmdbShowIndex.size}, movies=${byTmdbMovieIndex.size}, imdb=${byImdbShowIndex.size})")
        } else {
            lastFailedLoadAtMs = System.currentTimeMillis()
            Log.w(TAG, "no mappings available — falling back to arm only (will retry in ${FAILED_BACKOFF_MS / 1000}s)")
        }
    }

    private suspend fun download(): Map<String, AnimeMappingEntryDto>? {
        Log.i(TAG, "downloading mappings from ${AnimeMappingsSource.PLEX_ANI_BRIDGE_URL}")
        val result = safeApiCall {
            animeMappingsApi.getMappings(AnimeMappingsSource.PLEX_ANI_BRIDGE_URL)
        }
        return when (result) {
            is NetworkResult.Success -> {
                Log.i(TAG, "mappings download ok, ${result.data.size} raw entries")
                result.data
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "mappings download failed code=${result.code} msg=${result.message}")
                null
            }
            NetworkResult.Loading -> {
                Log.w(TAG, "mappings download returned Loading (should not happen)")
                null
            }
        }
    }

    private fun cacheFile(): File = File(context.cacheDir, CACHE_FILENAME)

    @Suppress("UNCHECKED_CAST")
    private suspend fun readFromFile(file: File): Map<String, AnimeMappingEntryDto>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val type = Types.newParameterizedType(
                    Map::class.java, String::class.java, AnimeMappingEntryDto::class.java
                )
                val adapter = moshi.adapter<Map<String, AnimeMappingEntryDto>>(type)
                file.source().buffer().use { adapter.fromJson(it) }
            }.getOrNull()
        }

    private suspend fun writeToFile(file: File, data: Map<String, AnimeMappingEntryDto>) {
        withContext(Dispatchers.IO) {
            val type = Types.newParameterizedType(
                Map::class.java, String::class.java, AnimeMappingEntryDto::class.java
            )
            val adapter = moshi.adapter<Map<String, AnimeMappingEntryDto>>(type)
            file.sink().buffer().use { adapter.toJson(it, data) }
        }
    }

    /**
     * Collect every candidate that contributes to the watched `(season,
     * episode)`. PlexAniBridge keys are always `s{N}` (season-only); the
     * episode range lives in the value. For each candidate whose mapping
     * mentions this season, we parse the value's intervals and emit:
     *
     *   • every interval whose last-episode is ≤ `episode` → contributes
     *     its full length, flagged `isRangeComplete=true` when nothing
     *     later in this season remains.
     *   • the interval containing `episode` → contributes
     *     `(episode - interval.first + 1)`, flagged `isRangeComplete=false`
     *     when there's still unwatched content after in the same entry.
     *   • intervals entirely after `episode` → skipped for this candidate.
     *
     * tmdb_mappings is preferred over tvdb_mappings when both exist for a
     * candidate (tmdb is authoritative for our TMDB-based inputs).
     */
    internal fun collectCoveringCandidates(
        candidates: List<AnimeMappingEntryDto>,
        tmdbSeason: Int,
        tmdbEpisode: Int
    ): List<CoveringMatch> {
        val matches = mutableListOf<CoveringMatch>()
        for (entry in candidates) {
            val contribution = computeContribution(entry, tmdbSeason, tmdbEpisode) ?: continue
            Log.d(TAG, "  candidate anilist=${entry.anilistId} → trackerEp=${contribution.trackerEpisode} " +
                "complete=${contribution.isComplete} via=${if (contribution.viaTmdbMappings) "tmdb" else "tvdb"}")
            matches += CoveringMatch(
                entry = entry,
                trackerEpisode = contribution.trackerEpisode,
                isRangeComplete = contribution.isComplete,
                viaTmdbMappings = contribution.viaTmdbMappings
            )
        }
        // Fallback for shows with exactly one candidate and no resolvable
        // season mapping — better to attempt a write than silently skip.
        if (matches.isEmpty() && candidates.size == 1) {
            val sole = candidates[0]
            Log.d(TAG, "  sole-candidate fallback: anilist=${sole.anilistId}")
            matches += CoveringMatch(
                entry = sole,
                trackerEpisode = tmdbEpisode,
                isRangeComplete = false,
                viaTmdbMappings = false
            )
        }
        return matches
    }

    /**
     * Given one candidate and a watched `(season, episode)`, compute how
     * many tracker-side episodes have been watched on this entry and
     * whether any content remains unwatched. Handles two distinct value
     * coordinate systems used in PlexAniBridge:
     *
     *   • **Absolute-cumulative**: each season's value range is a slice
     *     of the tracker's absolute episode numbering (e.g. One Piece
     *     `tmdb_mappings` — `s1: e1-e61, s2: e62-e77, s3: e78-e91...`).
     *     Detected when consecutive season values chain end-to-start.
     *
     *   • **Season-local**: each season's value is in that source
     *     season's own episode coordinates starting from 1 (tvdb case) or
     *     from the entry's first covered TMDB episode (split-entry case
     *     like AoT Part 2 `s4: e17-e28`). Must sum earlier seasons'
     *     lengths to produce cumulative tracker progress.
     *
     * Returns null when the candidate has no mapping for this season.
     */
    private fun computeContribution(
        entry: AnimeMappingEntryDto,
        tmdbSeason: Int,
        tmdbEpisode: Int
    ): Contribution? {
        // Prefer tvdb_mappings when it covers the watched season — current
        // TMDB season layouts for most anime shows mirror TVDB (One Piece
        // S1=8 eps, S2=22 eps, …), so tvdb-side season-local episode ranges
        // match what the user sees in the app. tmdb_mappings is kept as a
        // fallback and tends to be an older snapshot of TMDB that used
        // very different absolute numbering (e.g. One Piece S1=61 eps).
        val preferTvdb = entry.tvdbMappings?.containsKey("s$tmdbSeason") == true
        val mappings = when {
            preferTvdb -> entry.tvdbMappings!!
            entry.tmdbMappings?.containsKey("s$tmdbSeason") == true -> entry.tmdbMappings
            else -> return null
        } ?: return null
        val viaTmdb = !preferTvdb

        return if (detectChaining(mappings)) {
            computeAbsoluteContribution(mappings, tmdbSeason, tmdbEpisode, viaTmdb)
        } else {
            computeSeasonLocalContribution(mappings, tmdbSeason, tmdbEpisode, viaTmdb)
        }
    }

    /**
     * True iff every consecutive pair of numeric season keys in [mappings]
     * chains: the last episode of `s(n)`'s value equals the first episode
     * of `s(n+1)`'s value minus one. This pattern is how PlexAniBridge
     * encodes absolute-cumulative tracker numbering across multiple TMDB
     * seasons. Single-season mappings are never considered chaining.
     */
    private fun detectChaining(mappings: Map<String, String>): Boolean {
        val sorted = mappings.entries.mapNotNull { (k, v) ->
            val s = SEASON_KEY_REGEX.matchEntire(k.trim())?.groupValues?.get(1)?.toIntOrNull()
            if (s == null || s <= 0 || v.isEmpty()) null else s to v
        }.sortedBy { it.first }
        if (sorted.size < 2) return false
        var prevEnd: Int? = null
        for ((_, value) in sorted) {
            val intervals = parseValueCoverage(value)
            if (intervals.isEmpty()) return false
            val firstStart = intervals.first().first
            val lastEnd = intervals.last().last
            if (prevEnd != null) {
                if (firstStart != prevEnd!! + 1) return false
            }
            prevEnd = if (lastEnd == Int.MAX_VALUE) lastEnd else lastEnd
        }
        return true
    }

    /**
     * Absolute-cumulative branch. Example: `s2: e62-e77` means TMDB S2 (whose
     * episodes run 1..16 in TMDB-season-local numbering) maps to tracker
     * absolute eps 62..77. Watching TMDB S2E5 → tracker ep = `62 + (5 - 1)`.
     *
     * Completeness: the watched episode must reach or exceed the last interval's
     * end for this season, AND there must be no later-season keys left.
     */
    private fun computeAbsoluteContribution(
        mappings: Map<String, String>,
        tmdbSeason: Int,
        tmdbEpisode: Int,
        preferTmdb: Boolean
    ): Contribution? {
        val rawValue = mappings["s$tmdbSeason"] ?: return null
        val intervals = parseValueCoverage(rawValue)
        if (intervals.isEmpty()) return null
        val first = intervals.first()
        val last = intervals.last()
        val trackerEp = first.first + (tmdbEpisode - 1)
        val seasonDone = trackerEp >= last.last
        val laterSeasonsExist = mappings.keys.any {
            val s = SEASON_KEY_REGEX.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull()
            s != null && s > tmdbSeason
        }
        return Contribution(
            trackerEpisode = trackerEp,
            isComplete = seasonDone && !laterSeasonsExist,
            viaTmdbMappings = preferTmdb
        )
    }

    /**
     * Season-local branch. Sums earlier-season full ranges + current-season
     * partial. Covers both split-entry cases (AoT Part 2 with one season
     * key) and multi-season non-chaining entries (tvdb_mappings where each
     * season resets to e1).
     *
     * Returns null when this candidate doesn't mention the watched season
     * at all — prevents emitting a mapping for an entry that only covers
     * OTHER seasons.
     */
    private fun computeSeasonLocalContribution(
        mappings: Map<String, String>,
        tmdbSeason: Int,
        tmdbEpisode: Int,
        preferTmdb: Boolean
    ): Contribution? {
        val sortedSeasons = mappings.keys.mapNotNull {
            SEASON_KEY_REGEX.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull()
        }.filter { it in 1..tmdbSeason }.sorted()
        if (sortedSeasons.isEmpty()) return null

        var trackerContribution = 0
        var hasUnwatchedAfter = false
        var touchedCurrent = false

        for (season in sortedSeasons) {
            val rawValue = mappings["s$season"] ?: continue
            val intervals = parseValueCoverage(rawValue)
            if (intervals.isEmpty()) continue

            if (season < tmdbSeason) {
                // Earlier season: fully watched (user's past viewing assumed).
                // Skip unbounded intervals — we can't count `""` (1..MAX_VALUE)
                // without a length field. Explicit ranges contribute their size.
                for (interval in intervals) {
                    if (interval.last == Int.MAX_VALUE) continue
                    trackerContribution += (interval.last - interval.first + 1)
                }
            } else {
                // Current season: partial based on watched episode.
                for (interval in intervals) {
                    when {
                        interval.last < tmdbEpisode -> {
                            trackerContribution += (interval.last - interval.first + 1)
                            touchedCurrent = true
                        }
                        interval.first > tmdbEpisode -> hasUnwatchedAfter = true
                        else -> {
                            trackerContribution += (tmdbEpisode - interval.first + 1)
                            touchedCurrent = true
                            if (tmdbEpisode < interval.last) hasUnwatchedAfter = true
                        }
                    }
                }
            }
        }
        if (!touchedCurrent) return null

        val laterSeasonsExist = mappings.keys.any {
            val s = SEASON_KEY_REGEX.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull()
            s != null && s > tmdbSeason
        }
        return Contribution(
            trackerEpisode = trackerContribution,
            isComplete = !hasUnwatchedAfter && !laterSeasonsExist,
            viaTmdbMappings = preferTmdb
        )
    }

    /**
     * Parse a PlexAniBridge mapping value into a list of TMDB episode
     * intervals.
     *
     * Grammar (confirmed against 20,626 real entries):
     *   ""                             → the whole season (wildcard, all episodes)
     *   "eN"                           → single episode N
     *   "eA-eB"                        → range A..B inclusive
     *   "<seg>,<seg>,..."              → comma-separated multi-range
     *   "<seg>|{ratio}" (rare, 85 entries) → ratio suffix for movie/recap
     *     compressions; we strip the `|…` and use the base range. That
     *     over-counts for recap segments but covers the common case.
     *
     * Empty string is treated as the interval `1..Int.MAX_VALUE` so a
     * single-candidate whole-season mapping always "contains" the watched
     * episode and writes `trackerEpisode = watched`.
     */
    internal fun parseValueCoverage(value: String): List<IntRange> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return listOf(1..Int.MAX_VALUE)
        val segments = trimmed.split(",")
        val out = mutableListOf<IntRange>()
        for (raw in segments) {
            val seg = raw.substringBefore("|").trim() // strip ratio suffix
            if (seg.isEmpty()) continue
            val m = VALUE_RANGE_REGEX.matchEntire(seg) ?: continue
            val start = m.groupValues[1].toInt()
            val endGroup = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            // "e1089" (no dash) → single ep. "e1089-" (trailing dash, no
            // end) → open-ended; used by PAB to mark ongoing seasons like
            // One Piece's latest arc. Distinguish via the raw string.
            val end = when {
                endGroup != null -> endGroup.toInt()
                seg.contains('-') -> Int.MAX_VALUE
                else -> start
            }
            out += start..end
        }
        return out
    }

    private fun firstTvdbSeason(entry: AnimeMappingEntryDto): Int? {
        val keys = entry.tvdbMappings?.keys ?: return null
        for (key in keys) {
            val m = SEASON_KEY_REGEX.matchEntire(key.trim()) ?: continue
            return m.groupValues[1].toInt()
        }
        return null
    }

    /** Per-candidate computation result (internal helper). */
    private data class Contribution(
        val trackerEpisode: Int,
        val isComplete: Boolean,
        val viaTmdbMappings: Boolean
    )

    /** Public enough to be addressable by test-only code; sealed via `internal`. */
    internal data class CoveringMatch(
        val entry: AnimeMappingEntryDto,
        val trackerEpisode: Int,
        val isRangeComplete: Boolean,
        val viaTmdbMappings: Boolean
    )

    companion object {
        private const val TAG = "EpisodeOffsetMapper"
        private const val CACHE_FILENAME = "anime_mappings.json"
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val FAILED_BACKOFF_MS = 60_000L
        // Keys — always `s{N}` in real PAB data.
        private val SEASON_KEY_REGEX = Regex("""s(\d+)""", RegexOption.IGNORE_CASE)
        // Value segments — `eA`, `eA-eB`, or `eA-` (open-ended).
        private val VALUE_RANGE_REGEX = Regex("""e?(\d+)(?:-e?(\d+)?)?""", RegexOption.IGNORE_CASE)
    }
}
