package com.nuvio.tv.core.player.datasource

import android.content.Context
import com.nuvio.tv.data.local.LocalLibraryPreferences
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Bridges the player's static `PlayerPlaybackNetworking` factory builder into
 * the Hilt graph: the player constructs DataSource factories synchronously from
 * non-injected call sites, so it grabs the needed singletons via this entry
 * point rather than threading them through every layer.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerDataSourceEntryPoint {
    fun localLibraryPreferences(): LocalLibraryPreferences
    fun localLibraryCredentialStore(): LocalLibraryCredentialStore

    companion object {
        fun get(context: Context): PlayerDataSourceEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, PlayerDataSourceEntryPoint::class.java)
    }
}
