package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Jellyfin REST surface used by the local library subsystem. Per-server
 * instances are built at runtime since each user-configured source has its own
 * base URL and auth token.
 */
interface JellyfinApi {

    @POST("Users/AuthenticateByName")
    suspend fun authenticateByName(
        @Header("Authorization") authHeader: String,
        @Body body: JellyfinAuthRequest
    ): Response<JellyfinAuthResponse>

    @GET("System/Info/Public")
    suspend fun publicInfo(): Response<JellyfinPublicInfo>

    @GET("Users/{userId}/Items")
    suspend fun getItems(
        @Header("Authorization") authHeader: String,
        @Path("userId") userId: String,
        @Query("Recursive") recursive: Boolean = true,
        @Query("IncludeItemTypes") includeItemTypes: String = "Movie,Episode",
        @Query("Fields") fields: String = "ProviderIds,Path,MediaSources,RunTimeTicks,ParentIndexNumber,IndexNumber,SeriesName,ProductionYear",
        @Query("EnableUserData") enableUserData: Boolean = false,
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 500
    ): Response<JellyfinItemsResponse>
}

@JsonClass(generateAdapter = true)
data class JellyfinAuthRequest(
    @Json(name = "Username") val username: String,
    @Json(name = "Pw") val password: String
)

@JsonClass(generateAdapter = true)
data class JellyfinAuthResponse(
    @Json(name = "User") val user: JellyfinUser? = null,
    @Json(name = "AccessToken") val accessToken: String? = null,
    @Json(name = "ServerId") val serverId: String? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinUser(
    @Json(name = "Id") val id: String? = null,
    @Json(name = "Name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinPublicInfo(
    @Json(name = "ServerName") val serverName: String? = null,
    @Json(name = "Version") val version: String? = null,
    @Json(name = "Id") val id: String? = null,
    @Json(name = "ProductName") val productName: String? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinItemsResponse(
    @Json(name = "Items") val items: List<JellyfinItem>? = null,
    @Json(name = "TotalRecordCount") val totalRecordCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinItem(
    @Json(name = "Id") val id: String,
    @Json(name = "Name") val name: String? = null,
    @Json(name = "Type") val type: String? = null,
    @Json(name = "Path") val path: String? = null,
    @Json(name = "ProviderIds") val providerIds: Map<String, String>? = null,
    @Json(name = "MediaSources") val mediaSources: List<JellyfinMediaSource>? = null,
    @Json(name = "RunTimeTicks") val runTimeTicks: Long? = null,
    @Json(name = "ProductionYear") val productionYear: Int? = null,
    @Json(name = "ParentIndexNumber") val parentIndexNumber: Int? = null,
    @Json(name = "IndexNumber") val indexNumber: Int? = null,
    @Json(name = "SeriesName") val seriesName: String? = null,
    @Json(name = "SeriesId") val seriesId: String? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinMediaSource(
    @Json(name = "Id") val id: String? = null,
    @Json(name = "Container") val container: String? = null,
    @Json(name = "Size") val size: Long? = null,
    @Json(name = "Path") val path: String? = null
)
