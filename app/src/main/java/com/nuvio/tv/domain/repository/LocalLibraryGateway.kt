package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow

/**
 * Bridge between the existing Catalog/Meta/Stream repositories and the local library
 * subsystem. The three resource repos short-circuit to this gateway whenever they
 * encounter the synthetic addon (baseUrl == [SYNTHETIC_BASE_URL]) or an id with
 * the [LOCAL_ID_PREFIX] prefix.
 */
interface LocalLibraryGateway {
    /**
     * Emits the synthetic Addon describing the union of currently-enabled sources
     * (with one CatalogDescriptor per source-section). Emits null when no sources
     * are enabled so callers can omit it from the addon list.
     */
    fun synthesizeAddon(): Flow<Addon?>

    /** Whether [baseUrl] / [addonId] refers to the synthetic local-library addon. */
    fun isLocalLibrary(addonId: String?, baseUrl: String?): Boolean

    /** Whether [id] refers to a local-library item (catalog item or meta id). */
    fun isLocalId(id: String?): Boolean

    suspend fun catalog(catalogId: String, skip: Int, skipStep: Int): NetworkResult<CatalogRow>

    suspend fun meta(type: String, id: String): NetworkResult<Meta>

    suspend fun streams(type: String, id: String, season: Int?, episode: Int?): NetworkResult<List<Stream>>

    companion object {
        const val ADDON_ID = "local-library"
        const val SYNTHETIC_BASE_URL = "nuvio-local://"
        const val LOCAL_ID_PREFIX = "nuvio-local:"
    }
}
