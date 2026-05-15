package com.nuvio.tv.core.di

import com.nuvio.tv.data.locallibrary.LocalLibraryGatewayImpl
import com.nuvio.tv.domain.repository.LocalLibraryGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalLibraryModule {

    @Binds
    @Singleton
    abstract fun bindLocalLibraryGateway(impl: LocalLibraryGatewayImpl): LocalLibraryGateway
}
