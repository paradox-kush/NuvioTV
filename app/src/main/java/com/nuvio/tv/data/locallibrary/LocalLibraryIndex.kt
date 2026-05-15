package com.nuvio.tv.data.locallibrary

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk JSON index of scanned items, one file per source under
 * `filesDir/local_library/<sourceId>.json`. Loaded lazily into memory on first
 * access; mutations write the whole file (acceptable: per-source writes are
 * infrequent — at scan completion or partial-progress checkpoints).
 *
 * Deliberately bypasses DataStore: Preferences DataStore rewrites its entire
 * file on every commit, which is fine for kB-sized configs but pathological
 * for ~10MB indexes of 10k items.
 */
@Singleton
class LocalLibraryIndex @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, List<ScannedItem>>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private val rootDir: File by lazy {
        File(context.filesDir, "local_library").also { it.mkdirs() }
    }

    private fun mutexFor(sourceId: String): Mutex =
        mutexes.computeIfAbsent(sourceId) { Mutex() }

    private fun fileFor(sourceId: String): File = File(rootDir, "$sourceId.json")

    suspend fun load(sourceId: String): List<ScannedItem> {
        cache[sourceId]?.let { return it }
        return mutexFor(sourceId).withLock {
            cache[sourceId]?.let { return@withLock it }
            val items = withContext(Dispatchers.IO) { readFromDisk(sourceId) }
            cache[sourceId] = items
            items
        }
    }

    suspend fun replace(sourceId: String, items: List<ScannedItem>) {
        mutexFor(sourceId).withLock {
            cache[sourceId] = items
            withContext(Dispatchers.IO) { writeToDisk(sourceId, items) }
        }
    }

    suspend fun deleteSource(sourceId: String) {
        mutexFor(sourceId).withLock {
            cache.remove(sourceId)
            withContext(Dispatchers.IO) { fileFor(sourceId).delete() }
        }
    }

    suspend fun findByLocalId(localId: String): ScannedItem? {
        val sourceId = ScannedItemIds.sourceIdFromLocalId(localId) ?: return null
        return load(sourceId).firstOrNull { it.localId == localId }
    }

    private fun readFromDisk(sourceId: String): List<ScannedItem> {
        val file = fileFor(sourceId)
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<ScannedItem>>() {}.type
            file.bufferedReader().use { gson.fromJson<List<ScannedItem>>(it, type) } ?: emptyList()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read index for $sourceId — treating as empty", t)
            emptyList()
        }
    }

    private fun writeToDisk(sourceId: String, items: List<ScannedItem>) {
        val file = fileFor(sourceId)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.bufferedWriter().use { gson.toJson(items, it) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist index for $sourceId", t)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "LocalLibraryIndex"
    }
}

/** Helpers for parsing the synthetic local id format `nuvio-local:<sourceId>:<encodedPath>`. */
object ScannedItemIds {
    private const val PREFIX = "nuvio-local:"

    fun sourceIdFromLocalId(localId: String): String? {
        if (!localId.startsWith(PREFIX)) return null
        val rest = localId.removePrefix(PREFIX)
        val colon = rest.indexOf(':')
        return if (colon > 0) rest.substring(0, colon) else null
    }
}
