package com.nuvio.tv.core.player.datasource

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Routes ExoPlayer reads to one of several backing [DataSource] factories based
 * on the URI scheme. Used to add `smb://` support without changing the rest of
 * the player networking code — anything not matched by a custom factory falls
 * through to [defaultFactory].
 */
@UnstableApi
class SchemeDispatchingDataSource(
    private val defaultFactory: DataSource.Factory,
    private val factoryByScheme: Map<String, DataSource.Factory>
) : DataSource {

    private var delegate: DataSource? = null
    private val transferListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        val factory = scheme?.let { factoryByScheme[it] } ?: defaultFactory
        val ds = factory.createDataSource()
        transferListeners.forEach(ds::addTransferListener)
        delegate = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (delegate ?: error("Read before open")).read(buffer, offset, length)

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    class Factory(
        private val defaultFactory: DataSource.Factory,
        private val factoryByScheme: Map<String, DataSource.Factory>
    ) : DataSource.Factory {
        @UnstableApi
        override fun createDataSource(): DataSource =
            SchemeDispatchingDataSource(defaultFactory, factoryByScheme)
    }
}
