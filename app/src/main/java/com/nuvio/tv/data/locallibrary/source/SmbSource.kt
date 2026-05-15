package com.nuvio.tv.data.locallibrary.source

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore.Field
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.ResolvedStream
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.EnumSet

/**
 * Browses and reads media from a SMB/CIFS share via smbj.
 *
 * Playback URLs use the `smb://` scheme so ExoPlayer routes through
 * [com.nuvio.tv.core.player.datasource.SmbDataSource] — the password lookup
 * happens there at open-time, never in the URL itself.
 */
class SmbSource(
    override val config: LocalLibrarySourceConfig,
    private val credentialStore: LocalLibraryCredentialStore
) : LocalLibrarySource {

    private val location: SmbLocation = SmbLocation.parse(config.urlOrPath)

    override fun scan(): Flow<ScannedItem> = flow {
        withShare { share, rootDir ->
            traverse(share, rootDir, rootDir).forEach { entry ->
                val (relPath, sizeBytes) = entry
                val parsed = com.nuvio.tv.data.locallibrary.match.FilenameParser.parse(
                    relPath.substringAfterLast('/')
                )
                emit(
                    ScannedItem(
                        sourceId = config.id,
                        relativePath = relPath,
                        fileName = relPath.substringAfterLast('/'),
                        sizeBytes = sizeBytes,
                        parsedTitle = parsed.title,
                        parsedYear = parsed.year,
                        parsedSeason = parsed.season,
                        parsedEpisode = parsed.episode,
                        typeHint = parsed.contentType
                    )
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun resolveStream(item: ScannedItem): ResolvedStream {
        val urlPath = item.relativePath.trim('/').replace('\\', '/')
        val url = "smb://${location.host}/${location.share}/$urlPath"
        return ResolvedStream(
            url = url,
            scheme = "smb",
            sizeBytes = item.sizeBytes
        )
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        withShare { _, _ -> /* successful connect+authenticate is enough */ }
    }

    private inline fun <T> withShare(block: (DiskShare, String) -> T): T {
        val username = credentialStore.getSecret(config.id, Field.SMB_USERNAME).orEmpty()
        val password = credentialStore.getSecret(config.id, Field.SMB_PASSWORD).orEmpty()
        val domain = credentialStore.getSecret(config.id, Field.SMB_DOMAIN)
        val auth = if (username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(username, password.toCharArray(), domain)
        }
        val client = SMBClient()
        return client.connect(location.host).use { connection ->
            val session = connection.authenticate(auth)
            session.connectShare(location.share).use { share ->
                require(share is DiskShare) { "Share ${location.share} is not a disk share" }
                block(share, location.rootDir)
            }
        }
    }

    /** DFS over [share] rooted at [rootDir]. Returns pairs of (relative path, size). */
    private fun traverse(
        share: DiskShare,
        rootDir: String,
        currentDir: String
    ): List<Pair<String, Long>> {
        val results = mutableListOf<Pair<String, Long>>()
        val stack = ArrayDeque<String>().apply { addLast(currentDir) }
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val entries = try {
                share.list(dir.takeIf { it.isNotEmpty() } ?: "")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to list '$dir' on ${location.host}", t)
                continue
            }
            for (entry in entries) {
                val name = entry.fileName
                if (name == "." || name == "..") continue
                val isDirectory = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                val full = if (dir.isEmpty()) name else "$dir/$name"
                if (isDirectory) {
                    stack.addLast(full)
                } else if (isVideoFile(name)) {
                    val rel = if (rootDir.isEmpty()) full else full.removePrefix("$rootDir/")
                    results += rel to entry.endOfFile
                }
            }
        }
        return results
    }

    private fun isVideoFile(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return false
        return name.substring(dot + 1).lowercase() in VIDEO_EXTS
    }

    /** Public helper so [com.nuvio.tv.core.player.datasource.SmbDataSource] can reuse parsing. */
    data class SmbLocation(
        val host: String,
        val share: String,
        /** Subpath under the share root, "" if browsing the share root itself. */
        val rootDir: String
    ) {
        companion object {
            fun parse(raw: String): SmbLocation {
                val cleaned = raw
                    .removePrefix("smb://")
                    .removePrefix("//")
                    .trimStart('/')
                    .trimEnd('/')
                val parts = cleaned.split('/')
                require(parts.size >= 2) { "SMB path must include a share: $raw" }
                val host = parts[0]
                val share = parts[1]
                val rootDir = parts.drop(2).joinToString("/")
                return SmbLocation(host = host, share = share, rootDir = rootDir)
            }
        }
    }

    companion object {
        private const val TAG = "SmbSource"
        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg", "m4v"
        )

        /** Open access mask for read-only file open from [SmbDataSource]. */
        val READ_ACCESS: EnumSet<AccessMask> = EnumSet.of(AccessMask.GENERIC_READ)
        val READ_DISPOSITION: SMB2CreateDisposition = SMB2CreateDisposition.FILE_OPEN
        val READ_SHARE_ACCESS: EnumSet<SMB2ShareAccess> = EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ)
    }
}
