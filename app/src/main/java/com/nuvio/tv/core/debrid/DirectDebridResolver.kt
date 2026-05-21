package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val torboxResolver: TorboxDirectDebridResolver,
    private val realDebridResolver: RealDebridDirectDebridResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val resolvedCache = mutableMapOf<String, CachedDirectDebridResolve>()
    private val inFlightResolves = mutableMapOf<String, Deferred<DirectDebridResolveResult>>()

    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val cacheKey = stream.directDebridResolveCacheKey(season, episode)
        if (cacheKey == null) {
            return resolveUncached(stream, season, episode)
        }
        getCachedResult(cacheKey)?.let {
            return it
        }

        var ownsResolve = false
        val newResolve = scope.async(start = CoroutineStart.LAZY) {
            resolveUncached(stream, season, episode)
        }
        val activeResolve = mutex.withLock {
            getCachedResultLocked(cacheKey)?.let { cached ->
                return@withLock null to cached
            }
            val existing = inFlightResolves[cacheKey]
            if (existing != null) {
                existing to null
            } else {
                inFlightResolves[cacheKey] = newResolve
                ownsResolve = true
                newResolve to null
            }
        }
        activeResolve.second?.let {
            newResolve.cancel()
            return it
        }
        val deferred = activeResolve.first ?: return DirectDebridResolveResult.Error
        if (!ownsResolve) newResolve.cancel()
        if (ownsResolve) deferred.start()

        return try {
            val result = deferred.await()
            if (ownsResolve && result is DirectDebridResolveResult.Success) {
                mutex.withLock {
                    resolvedCache[cacheKey] = CachedDirectDebridResolve(
                        result = result,
                        cachedAtMs = System.currentTimeMillis()
                    )
                }
            }
            result
        } finally {
            if (ownsResolve) {
                mutex.withLock {
                    if (inFlightResolves[cacheKey] === deferred) {
                        inFlightResolves.remove(cacheKey)
                    }
                }
            }
        }
    }

    suspend fun cachedPlayableStream(stream: Stream, season: Int?, episode: Int?): Stream? {
        val cacheKey = stream.directDebridResolveCacheKey(season, episode) ?: return null
        return getCachedResult(cacheKey)?.let { result -> stream.withResolvedDebridUrl(result) }
    }

    suspend fun resolveToPlayableStream(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridPlayableResult {
        if (!stream.isDirectDebrid() || stream.getStreamUrl() != null) {
            return DirectDebridPlayableResult.Success(stream)
        }
        return when (val result = resolve(stream, season, episode)) {
            is DirectDebridResolveResult.Success -> DirectDebridPlayableResult.Success(stream.withResolvedDebridUrl(result))
            DirectDebridResolveResult.MissingApiKey -> DirectDebridPlayableResult.MissingApiKey
            DirectDebridResolveResult.Stale -> DirectDebridPlayableResult.Stale
            DirectDebridResolveResult.Error -> DirectDebridPlayableResult.Error
        }
    }

    private suspend fun getCachedResult(cacheKey: String): DirectDebridResolveResult.Success? =
        mutex.withLock { getCachedResultLocked(cacheKey) }

    private fun getCachedResultLocked(cacheKey: String): DirectDebridResolveResult.Success? {
        val cached = resolvedCache[cacheKey] ?: return null
        val age = System.currentTimeMillis() - cached.cachedAtMs
        return if (age in 0..DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS) {
            cached.result
        } else {
            resolvedCache.remove(cacheKey)
            null
        }
    }

    private suspend fun resolveUncached(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        return when (DebridProviders.byId(stream.clientResolve?.service)?.id) {
            DebridProviders.TORBOX_ID -> torboxResolver.resolve(stream, season, episode)
            DebridProviders.REAL_DEBRID_ID -> realDebridResolver.resolve(stream, season, episode)
            else -> DirectDebridResolveResult.Error
        }
    }

    private suspend fun Stream.directDebridResolveCacheKey(season: Int?, episode: Int?): String? {
        val resolve = clientResolve ?: return null
        val providerId = DebridProviders.byId(resolve.service)?.id ?: return null
        val settings = dataStore.settings.first()
        val apiKey = when (providerId) {
            DebridProviders.TORBOX_ID -> settings.torboxApiKey
            DebridProviders.REAL_DEBRID_ID -> settings.realDebridApiKey
            else -> ""
        }.trim().takeIf { it.isNotBlank() } ?: return null
        val identity = resolve.infoHash
            ?: resolve.magnetUri
            ?: resolve.torrentName
            ?: resolve.filename
            ?: return null

        return listOf(
            providerId,
            apiKey.stableFingerprint(),
            identity.trim().lowercase(),
            resolve.fileIdx?.toString().orEmpty(),
            (resolve.filename ?: behaviorHints?.filename).orEmpty().trim().lowercase(),
            (season ?: resolve.season)?.toString().orEmpty(),
            (episode ?: resolve.episode)?.toString().orEmpty()
        ).joinToString("|")
    }
}

private const val DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS = 15L * 60L * 1000L

private data class CachedDirectDebridResolve(
    val result: DirectDebridResolveResult.Success,
    val cachedAtMs: Long
)

sealed class DirectDebridPlayableResult {
    data class Success(val stream: Stream) : DirectDebridPlayableResult()
    data object MissingApiKey : DirectDebridPlayableResult()
    data object Stale : DirectDebridPlayableResult()
    data object Error : DirectDebridPlayableResult()
}

private fun String.stableFingerprint(): String {
    val hash = fold(1125899906842597L) { acc, char -> (acc * 31L) + char.code }
    return hash.toULong().toString(16)
}

private fun Stream.withResolvedDebridUrl(result: DirectDebridResolveResult.Success): Stream =
    copy(
        url = result.url,
        externalUrl = null,
        behaviorHints = behaviorHints.mergeResolvedDebridHints(result)
    )

private fun StreamBehaviorHints?.mergeResolvedDebridHints(
    result: DirectDebridResolveResult.Success
): StreamBehaviorHints {
    val current = this
    if (current != null) {
        return current.copy(
            filename = result.filename ?: current.filename,
            videoSize = result.videoSize ?: current.videoSize
        )
    }
    return StreamBehaviorHints(
        notWebReady = null,
        bingeGroup = null,
        countryWhitelist = null,
        proxyHeaders = null,
        filename = result.filename,
        videoSize = result.videoSize
    )
}
