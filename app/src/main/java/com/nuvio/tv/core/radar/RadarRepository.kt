package com.nuvio.tv.core.radar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class RadarUiState(
    val catalog: RadarCatalog = RadarCatalog(),
    val follows: List<RadarFollow> = emptyList(),
    val prefs: RadarPrefs = RadarPrefs(),
    val fixturesByLeague: Map<String, List<RadarFixture>> = emptyMap(),
    val liveEventIds: Set<String> = emptySet(),
    val loadingFixtures: Boolean = false,
) {
    val followedLeagueIds: Set<String> get() = follows.map { it.leagueId }.toSet()

    fun leagueById(id: String): RadarLeague? =
        catalog.categories.asSequence().flatMap { it.leagues }.firstOrNull { it.id == id }

    fun activeFeatured(nowMs: Long): List<RadarFeaturedEvent> =
        catalog.featured.filter { it.isActive(nowMs) }

    fun isLive(fixture: RadarFixture, nowMs: Long): Boolean =
        fixture.id?.let { it in liveEventIds } ?: false || fixture.inferredLive(nowMs)

    fun upcoming(leagueIds: Collection<String>, nowMs: Long, cap: Int = 20): List<RadarFixture> =
        leagueIds.asSequence()
            .flatMap { fixturesByLeague[it].orEmpty() }
            .distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
            .filter { fx ->
                val start = fx.startEpochMs ?: return@filter false
                start >= nowMs - 4 * 60 * 60 * 1000L || isLive(fx, nowMs)
            }
            .sortedBy { it.startEpochMs }
            .take(cap)
            .toList()
}

/**
 * Sports Centre state on TV: curated catalog + per-profile follows/prefs (RadarStore, synced)
 * + throttled fixtures via the radar-fixtures edge function. The store flow is profile-reactive
 * (flatMapLatest on activeProfileId) so profile switches propagate automatically; the in-memory
 * fixtures reset alongside via the state collector.
 */
@Singleton
class RadarRepository @Inject constructor(
    private val store: RadarStore,
    private val fixturesClient: RadarFixturesClient,
    private val syncService: RadarSyncService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(RadarUiState())
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private var lastFetchMark: TimeMark? = null
    private val fetchTtl = 15.minutes
    private var started = false

    fun ensureLoaded() {
        if (!started) {
            started = true
            val catalog = runCatching { json.decodeFromString<RadarCatalog>(RadarCatalogData.JSON) }
                .getOrDefault(RadarCatalog())
            _uiState.update { it.copy(catalog = catalog) }
            scope.launch {
                store.loadFixtures()?.let { cached ->
                    _uiState.update {
                        it.copy(fixturesByLeague = cached.fixtures, liveEventIds = liveIds(cached))
                    }
                }
                // Profile-reactive: any follows/prefs change (local edit, sync pull, profile
                // switch) lands here and re-evaluates what to fetch.
                store.state.collect { local ->
                    _uiState.update { it.copy(follows = local.follows, prefs = local.prefs) }
                    refreshFixtures()
                }
            }
        } else {
            refreshFixtures()
        }
    }

    fun refreshFixtures(force: Boolean = false) {
        val mark = lastFetchMark
        if (!force && mark != null && mark.elapsedNow() < fetchTtl) return
        val nowMs = RadarTime.nowMs()
        val state = _uiState.value
        val leagues = state.followedLeagueIds + state.activeFeatured(nowMs).map { it.leagueId }
        if (leagues.isEmpty()) return
        lastFetchMark = TimeSource.Monotonic.markNow()
        val sports = leagues.mapNotNull { id -> state.leagueById(id)?.sport?.lowercase() }
            .filter { it in RADAR_LIVESCORE_SPORTS }.toSet()
        _uiState.update { it.copy(loadingFixtures = true) }
        scope.launch {
            val response = fixturesClient.fetch(leagues, sports)
            if (response == null) {
                _uiState.update { it.copy(loadingFixtures = false) }
                lastFetchMark = null // failed: retry on next entry instead of waiting out the TTL
                return@launch
            }
            _uiState.update {
                it.copy(
                    fixturesByLeague = it.fixturesByLeague + response.fixtures,
                    liveEventIds = liveIds(response),
                    loadingFixtures = false,
                )
            }
            store.saveFixtures(response)
        }
    }

    fun toggleFollow(league: RadarLeague) {
        scope.launch {
            val current = _uiState.value.follows
            val without = current.filterNot { it.leagueId == league.id }
            val follows = if (without.size == current.size) {
                without + RadarFollow(leagueId = league.id, sport = league.sport ?: "", sortOrder = without.size)
            } else {
                without
            }
            store.saveState(RadarLocalState(follows = follows, prefs = _uiState.value.prefs))
            syncService.triggerRemoteSync()
            refreshFixtures(force = true)
        }
    }

    fun setOptIn(featuredEventId: String, accepted: Boolean) {
        scope.launch {
            val prefs = _uiState.value.prefs.copy(
                featuredEventId = featuredEventId,
                optInState = if (accepted) RadarOptIn.ACCEPTED else RadarOptIn.DECLINED,
            )
            store.saveState(RadarLocalState(follows = _uiState.value.follows, prefs = prefs))
            syncService.triggerRemoteSync()
        }
    }

    private fun liveIds(response: RadarFixturesResponse): Set<String> =
        response.livescore.values.asSequence().flatten().mapNotNull { it.eventId }.toSet()
}
