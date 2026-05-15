package com.nuvio.tv.data.locallibrary.source

import android.util.Log
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore.Field
import com.nuvio.tv.data.remote.api.JellyfinApi
import com.nuvio.tv.data.remote.api.JellyfinAuthRequest
import com.nuvio.tv.data.remote.api.JellyfinItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.ResolvedStream
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.UUID

class JellyfinSource(
    override val config: LocalLibrarySourceConfig,
    private val credentialStore: LocalLibraryCredentialStore,
    private val httpClient: OkHttpClient,
    private val moshi: Moshi
) : LocalLibrarySource {

    private val api: JellyfinApi by lazy { buildApi() }

    override fun scan(): Flow<ScannedItem> = flow {
        val auth = ensureAuth() ?: error("Jellyfin authentication failed for ${config.displayName}")
        val pageSize = 500
        var startIndex = 0
        while (true) {
            val response = api.getItems(
                authHeader = authHeader(auth.token),
                userId = auth.userId,
                startIndex = startIndex,
                limit = pageSize
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "Jellyfin getItems failed: ${response.code()}")
                break
            }
            val body = response.body() ?: break
            val items = body.items.orEmpty()
            if (items.isEmpty()) break
            items.forEach { jfItem ->
                toScannedItem(jfItem)?.let { emit(it) }
            }
            startIndex += items.size
            val total = body.totalRecordCount ?: 0
            if (startIndex >= total || items.size < pageSize) break
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun resolveStream(item: ScannedItem): ResolvedStream? {
        val auth = ensureAuth() ?: return null
        val jellyfinItemId = item.sourceItemId ?: item.relativePath
        val base = config.urlOrPath.trimEnd('/')
        val url = "$base/Videos/$jellyfinItemId/stream?api_key=${auth.token}&static=true"
        return ResolvedStream(
            url = url,
            headers = emptyMap(),
            scheme = "https".takeIf { url.startsWith("https") } ?: "http",
            sizeBytes = item.sizeBytes,
            durationMs = item.durationMs
        )
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        val resp = api.publicInfo()
        require(resp.isSuccessful) { "Server returned HTTP ${resp.code()}" }
        val auth = ensureAuth() ?: error("Authentication failed")
        require(auth.token.isNotBlank()) { "Empty access token" }
    }

    private fun toScannedItem(item: JellyfinItem): ScannedItem? {
        val type = when (item.type?.lowercase()) {
            "movie" -> ContentType.MOVIE
            "episode" -> ContentType.SERIES
            else -> return null
        }
        val tmdbHint = item.providerIds?.get("Tmdb")?.toIntOrNull()
        val durationMs = item.runTimeTicks?.let { it / 10_000 }
        return ScannedItem(
            sourceId = config.id,
            relativePath = item.id,
            fileName = item.path?.substringAfterLast('/')
                ?: item.name
                ?: item.id,
            sizeBytes = item.mediaSources?.firstOrNull()?.size,
            durationMs = durationMs,
            sourceItemId = item.id,
            tmdbHintId = tmdbHint,
            parsedTitle = item.seriesName ?: item.name,
            parsedYear = item.productionYear,
            parsedSeason = item.parentIndexNumber,
            parsedEpisode = item.indexNumber,
            typeHint = type
        )
    }

    data class Auth(val token: String, val userId: String)

    private fun ensureAuth(): Auth? {
        val storedToken = credentialStore.getSecret(config.id, Field.JELLYFIN_TOKEN)
        val storedUserId = credentialStore.getSecret(config.id, Field.JELLYFIN_USER_ID)
        if (storedToken.isNullOrBlank() || storedUserId.isNullOrBlank()) return null
        return Auth(storedToken, storedUserId)
    }

    /** Public so the Add Source flow can prime credentials before persisting them. */
    suspend fun authenticate(username: String, password: String): Auth? {
        val resp = api.authenticateByName(
            authHeader = authHeader(null),
            body = JellyfinAuthRequest(username = username, password = password)
        )
        if (!resp.isSuccessful) {
            Log.w(TAG, "Jellyfin auth failed: ${resp.code()}")
            return null
        }
        val body = resp.body() ?: return null
        val token = body.accessToken ?: return null
        val userId = body.user?.id ?: return null
        credentialStore.putSecret(config.id, Field.JELLYFIN_TOKEN, token)
        credentialStore.putSecret(config.id, Field.JELLYFIN_USER_ID, userId)
        return Auth(token, userId)
    }

    private fun authHeader(token: String?): String {
        val deviceId = config.params["deviceId"] ?: deriveDeviceId()
        val base = """MediaBrowser Client="Nuvio TV", Device="Android TV", DeviceId="$deviceId", Version="1.0""""
        return if (token.isNullOrBlank()) base else "$base, Token=\"$token\""
    }

    private fun deriveDeviceId(): String =
        UUID.nameUUIDFromBytes(("nuvio-${config.id}").toByteArray()).toString()

    private fun buildApi(): JellyfinApi {
        val base = config.urlOrPath.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(JellyfinApi::class.java)
    }

    companion object {
        private const val TAG = "JellyfinSource"
    }
}
