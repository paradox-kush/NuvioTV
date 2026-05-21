package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.PremiumizeAccountInfoDto
import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadDto
import com.nuvio.tv.data.remote.dto.PremiumizeItemDetailsDto
import com.nuvio.tv.data.remote.dto.PremiumizeItemListAllDto
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface PremiumizeApi {
    @GET("api/account/info")
    suspend fun accountInfo(
        @Header("Authorization") authorization: String
    ): Response<PremiumizeAccountInfoDto>

    @GET("api/item/listall")
    suspend fun listAllItems(
        @Header("Authorization") authorization: String
    ): Response<PremiumizeItemListAllDto>

    @GET("api/item/details")
    suspend fun itemDetails(
        @Header("Authorization") authorization: String,
        @Query("id") itemId: String
    ): Response<PremiumizeItemDetailsDto>

    @FormUrlEncoded
    @POST("api/transfer/directdl")
    suspend fun directDownload(
        @Header("Authorization") authorization: String,
        @Field("src") source: String
    ): Response<PremiumizeDirectDownloadDto>
}
