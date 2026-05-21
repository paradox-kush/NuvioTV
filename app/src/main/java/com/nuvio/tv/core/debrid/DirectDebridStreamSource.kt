package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.DirectDebridStreamApi
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private const val DIRECT_DEBRID_TAG = "DirectDebridStreams"
private const val STREAM_CACHE_TTL_MS = 5 * 60 * 1000L

@Singleton
class DirectDebridStreamSource internal constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: DirectDebridStreamApi,
    private val encoder: DirectDebridConfigEncoder,
    private val formatter: DebridStreamFormatter,
    private val baseUrlProvider: () -> String,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long
) {
    @Inject
    constructor(
        dataStore: DebridSettingsDataStore,
        api: DirectDebridStreamApi,
        encoder: DirectDebridConfigEncoder,
        formatter: DebridStreamFormatter
    ) : this(
        dataStore = dataStore,
        api = api,
        encoder = encoder,
        formatter = formatter,
        baseUrlProvider = { BuildConfig.DIRECT_DEBRID_API_BASE_URL },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        nowMs = { System.currentTimeMillis() }
    )

    private val lock = Any()
    private val streamCache = mutableMapOf<DirectDebridStreamCacheKey, CachedDirectDebridStreams>()
    private val inFlightFetches = mutableMapOf<DirectDebridStreamCacheKey, Deferred<DirectDebridStreamFetchResult>>()

    suspend fun isEnabled(): Boolean {
        return sourceNames().isNotEmpty()
    }

    suspend fun sourceNames(): List<String> {
        val settings = dataStore.settings.first()
        if (!settings.enabled || baseUrlProvider().isBlank()) return emptyList()
        return DebridProviders.configuredSourceNames(settings)
    }

    fun preloadStreams(type: String, videoId: String) {
        if (type.isBlank() || videoId.isBlank()) return
        scope.launch {
            runCatching { fetchStreams(type, videoId) }
        }
    }

    suspend fun fetchStreams(type: String, videoId: String): DirectDebridStreamFetchResult {
        val settings = dataStore.settings.first()
        val services = DebridProviders.configuredServices(settings)
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        if (!settings.enabled || services.isEmpty() || baseUrl.isBlank()) {
            return DirectDebridStreamFetchResult.Disabled
        }

        val cacheKey = DirectDebridStreamCacheKey(
            type = type.trim().lowercase(),
            videoId = videoId.trim(),
            baseUrl = baseUrl,
            settingsFingerprint = settings.toString()
        )
        cachedResult(cacheKey)?.let { return it }

        val deferred = synchronized(lock) {
            cachedResultLocked(cacheKey)?.let { cached ->
                return@synchronized null to cached
            }
            val existing = inFlightFetches[cacheKey]
            if (existing != null) {
                existing to null
            } else {
                val created = scope.async {
                    fetchStreamsUncached(
                        baseUrl = baseUrl,
                        type = type,
                        videoId = videoId,
                        services = services,
                        settings = settings
                    )
                }
                inFlightFetches[cacheKey] = created
                created to null
            }
        }

        deferred.second?.let { return it }

        val activeFetch = deferred.first ?: return DirectDebridStreamFetchResult.Error("Could not start Direct Debrid fetch")
        val result = activeFetch.await()
        synchronized(lock) {
            if (inFlightFetches[cacheKey] == activeFetch) {
                inFlightFetches.remove(cacheKey)
            }
            if (result is DirectDebridStreamFetchResult.Success) {
                streamCache[cacheKey] = CachedDirectDebridStreams(result, nowMs())
            }
        }
        return result
    }

    private fun cachedResult(cacheKey: DirectDebridStreamCacheKey): DirectDebridStreamFetchResult.Success? =
        synchronized(lock) { cachedResultLocked(cacheKey) }

    private fun cachedResultLocked(cacheKey: DirectDebridStreamCacheKey): DirectDebridStreamFetchResult.Success? {
        val cached = streamCache[cacheKey] ?: return null
        return if (nowMs() - cached.createdAtMs <= STREAM_CACHE_TTL_MS) {
            cached.result
        } else {
            streamCache.remove(cacheKey)
            null
        }
    }

    private suspend fun fetchStreamsUncached(
        baseUrl: String,
        type: String,
        videoId: String,
        services: List<DebridServiceCredential>,
        settings: DebridSettings
    ): DirectDebridStreamFetchResult {

        val results = mutableListOf<AddonStreams>()
        val errors = mutableListOf<String>()
        services.forEach { service ->
            when (val result = fetchProviderStreams(baseUrl, type, videoId, service, settings)) {
                is ProviderFetchResult.Success -> results += result.streams
                is ProviderFetchResult.Error -> errors += result.message
                ProviderFetchResult.Empty -> Unit
            }
        }

        return when {
            results.isNotEmpty() -> DirectDebridStreamFetchResult.Success(results)
            errors.isNotEmpty() -> DirectDebridStreamFetchResult.Error(errors.first())
            else -> DirectDebridStreamFetchResult.Empty
        }
    }

    private suspend fun fetchProviderStreams(
        baseUrl: String,
        type: String,
        videoId: String,
        service: DebridServiceCredential,
        settings: DebridSettings
    ): ProviderFetchResult {
        val b64Config = encoder.encode(service)
        val url = "$baseUrl/$b64Config/client-stream/${encodePathSegment(type)}/${encodePathSegment(videoId)}.json"
        return try {
            val response = api.getClientStreams(url)
            if (response.isSuccessful) {
                val streams = response.body()?.streams
                    ?.map { it.toDomain(DirectDebridStreamFilter.FALLBACK_SOURCE_NAME, null) }
                    ?.let { DirectDebridStreamFilter.filterInstant(it, settings) }
                    ?.filter { stream -> stream.clientResolve?.service.equals(service.provider.id, ignoreCase = true) }
                    ?.map { formatter.format(it, settings) }
                    .orEmpty()
                if (streams.isEmpty()) {
                    ProviderFetchResult.Empty
                } else {
                    ProviderFetchResult.Success(
                        streams.groupBy { it.addonName }
                            .map { (addonName, groupedStreams) ->
                                AddonStreams(
                                    addonName = addonName,
                                    addonLogo = null,
                                    streams = groupedStreams
                                )
                            }
                    )
                }
            } else {
                val message = response.message().ifBlank { "HTTP ${response.code()}" }
                Log.w(
                    DIRECT_DEBRID_TAG,
                    "Direct debrid ${service.provider.id} request failed code=${response.code()} message=$message"
                )
                ProviderFetchResult.Error(message)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val message = error.message ?: "Unknown error"
            Log.w(DIRECT_DEBRID_TAG, "Direct debrid ${service.provider.id} request failed message=$message")
            ProviderFetchResult.Error(message)
        }
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

private data class DirectDebridStreamCacheKey(
    val type: String,
    val videoId: String,
    val baseUrl: String,
    val settingsFingerprint: String
)

private data class CachedDirectDebridStreams(
    val result: DirectDebridStreamFetchResult.Success,
    val createdAtMs: Long
)

private sealed class ProviderFetchResult {
    data object Empty : ProviderFetchResult()
    data class Success(val streams: List<AddonStreams>) : ProviderFetchResult()
    data class Error(val message: String) : ProviderFetchResult()
}

sealed class DirectDebridStreamFetchResult {
    data object Disabled : DirectDebridStreamFetchResult()
    data object Empty : DirectDebridStreamFetchResult()
    data class Success(val streams: List<AddonStreams>) : DirectDebridStreamFetchResult()
    data class Error(val message: String) : DirectDebridStreamFetchResult()
}
