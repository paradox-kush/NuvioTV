package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.iptv.CategorySelections
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.remote.supabase.SupabaseIptvPlaylist
import com.nuvio.tv.data.remote.supabase.SupabaseXtreamAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "XtreamAccountSyncService"

/**
 * Syncs IPTV playlists (Xtream accounts + playlist-manager options) per profile to Supabase,
 * mirroring AddonSyncService. Push = full-replace RPC on change (debounced from the settings VM);
 * pull = direct RLS-scoped select on login. Empty remote + non-empty local => migrate local up.
 *
 * v2 (playlist manager P1): push/pull target the `iptv_playlists` table + RPC. Pull falls back
 * to the legacy `xtream_accounts` table when `iptv_playlists` has no xtream rows, applies the
 * legacy rows locally (defaults for the new option fields) and pushes them up to the NEW table —
 * a one-way upgrade per spec §12. After a successful migration push the legacy rows are cleared
 * (one write of `[]` to the old RPC) so the fallback is one-shot; the legacy RPC is never
 * written otherwise.
 */
@Singleton
class XtreamAccountSyncService @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
    private val authManager: AuthManager,
    private val accountStore: XtreamAccountStore,
    private val profileManager: ProfileManager,
) {
    private val postgrest get() = supabaseProvider.postgrest
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null

    var isSyncingFromRemote: Boolean = false

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /** Debounced push after a local account change (called from XtreamSettingsViewModel). */
    fun triggerRemoteSync() {
        if (isSyncingFromRemote || !authManager.isAuthenticated) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(600)
            if (!isSyncingFromRemote) pushToRemote()
        }
    }

    /** [onlyIfEmpty] is the legacy-migration guard: the loser of a two-device first-login
     *  race becomes a no-op instead of a full-replace with stale legacy rows. */
    suspend fun pushToRemote(onlyIfEmpty: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val accounts = accountStore.accounts.first()
            val params = playlistPushParams(accounts, profileId, onlyIfEmpty)
            withJwtRefreshRetry { postgrest.rpc("sync_push_iptv_playlists", params) }
            Log.d(TAG, "Pushed ${accounts.size} iptv playlists for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push iptv playlists", e)
            Result.failure(e)
        }
    }

    /**
     * Pull this profile's playlists and apply them locally.
     * No usable (xtream) rows in `iptv_playlists` => fall back to the legacy `xtream_accounts`
     * select and, when legacy rows exist, migrate them up to the new table (then clear them).
     * Both empty + non-empty local => push local up (source-type-scoped, so it can't delete
     * foreign rows).
     */
    suspend fun pullAndApply(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(
                    IllegalStateException("Unable to resolve sync owner for xtream sync")
                )
            val profileId = profileManager.activeProfileId.value
            val rows = withJwtRefreshRetry {
                postgrest.from("iptv_playlists")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", profileId)
                    } }
                    .decodeList<SupabaseIptvPlaylist>()
            }
            // Emptiness is decided AFTER filtering to rows this client understands: a table
            // holding only foreign source types (m3u/stalker from a newer client) must behave
            // exactly like an empty remote — applying an empty list would wipe local state.
            val remoteAccounts = rows.sortedBy { it.sortOrder }.mapNotNull { it.toXtreamAccountOrNull() }
            if (remoteAccounts.isNotEmpty()) {
                applyRemote(remoteAccounts)
                Log.d(TAG, "Pulled ${remoteAccounts.size} iptv playlists for profile $profileId")
                return@withContext Result.success(Unit)
            }

            // Legacy fallback: rows written by pre-playlist-manager clients.
            val legacy = withJwtRefreshRetry {
                postgrest.from("xtream_accounts")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", profileId)
                    } }
                    .decodeList<SupabaseXtreamAccount>()
            }
            if (legacy.isNotEmpty()) {
                val accounts = legacy.sortedBy { it.sortOrder }.map {
                    XtreamAccount(
                        id = "${it.baseUrl}|${it.username}",
                        name = it.name ?: it.baseUrl,
                        baseUrl = it.baseUrl,
                        username = it.username,
                        password = it.password,
                        enabled = it.enabled,
                        // new playlist options take their defaults
                    )
                }
                applyRemote(accounts)
                // One-way migration: copy the legacy rows into the new table (only-if-empty so
                // the loser of a two-device first-login race is a no-op), then clear the legacy
                // rows — a stale legacy copy would resurrect deleted playlists on every login.
                if (pushToRemote(onlyIfEmpty = true).isSuccess) clearLegacyRemote(profileId)
                Log.d(TAG, "Migrated ${accounts.size} legacy xtream accounts to iptv_playlists for profile $profileId")
                return@withContext Result.success(Unit)
            }

            if (accountStore.accounts.first().isNotEmpty()) pushToRemote()
            Result.success(Unit)
        } catch (e: Exception) {
            isSyncingFromRemote = false
            Log.e(TAG, "Failed to pull iptv playlists", e)
            Result.failure(e)
        }
    }

    private suspend fun applyRemote(accounts: List<XtreamAccount>) {
        isSyncingFromRemote = true
        accountStore.replaceAll(accounts)
        isSyncingFromRemote = false
    }

    /** One-shot legacy cleanup after a successful migration push: empty the old
     *  `xtream_accounts` rows so they can't resurrect deleted playlists on a later login.
     *  Best-effort — a failure just leaves the (idempotent) migration to run again. */
    private suspend fun clearLegacyRemote(profileId: Int) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_accounts", JsonArray(emptyList()))
            }
            withJwtRefreshRetry { postgrest.rpc("sync_push_xtream_accounts", params) }
            Log.d(TAG, "Cleared legacy xtream_accounts rows for profile $profileId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear legacy xtream_accounts rows", e)
        }
    }
}

/** P1: this client only understands xtream sources; other source types stay remote-only. */
internal fun SupabaseIptvPlaylist.toXtreamAccountOrNull(): XtreamAccount? {
    if (sourceType != XtreamAccount.SOURCE_XTREAM) return null
    val base = baseUrl ?: return null
    val user = username ?: return null
    val pass = password ?: return null
    return XtreamAccount(
        id = "$base|$user",
        name = name ?: base,
        baseUrl = base,
        username = user,
        password = pass,
        enabled = enabled,
        sourceType = sourceType,
        epgUrl = epgUrl,
        dnsProvider = dnsProvider,
        autoRefreshHours = autoRefreshHours,
        contentTypes = contentTypes.toSet(),
        categorySelections = decodeCategorySelections(categorySelections)
    )
}

/**
 * Full parameter object for `sync_push_iptv_playlists`. Every push scopes the full-replace with
 * `p_source_types: ["xtream"]` so this P1 client never deletes rows of source types it doesn't
 * understand (m3u/stalker written by a newer client). `p_only_if_empty` is sent only on the
 * legacy-migration push.
 */
internal fun playlistPushParams(accounts: List<XtreamAccount>, profileId: Int, onlyIfEmpty: Boolean): JsonObject =
    buildJsonObject {
        put("p_playlists", buildJsonArray {
            accounts.forEachIndexed { index, acc -> add(playlistPushJson(acc, index)) }
        })
        put("p_profile_id", profileId)
        put("p_source_types", buildJsonArray { add(XtreamAccount.SOURCE_XTREAM) })
        if (onlyIfEmpty) put("p_only_if_empty", true)
    }

/**
 * One playlist row of the `sync_push_iptv_playlists` payload. Contract (must match the
 * migration RPC in nuvio-backend `20260702000000_iptv_playlists.sql`): source_type, name
 * (omitted when blank), enabled, sort_order, base_url, username, password, epg_url (omitted
 * when null), dns_provider, auto_refresh_hours, content_types (array of strings),
 * category_selections (object with live/movies/series arrays, each omitted when null;
 * whole object omitted when all three are null).
 */
internal fun playlistPushJson(acc: XtreamAccount, sortOrder: Int): JsonObject = buildJsonObject {
    put("source_type", acc.sourceType)
    if (acc.name.isNotBlank()) put("name", acc.name)
    put("enabled", acc.enabled)
    put("sort_order", sortOrder)
    put("base_url", acc.baseUrl)
    put("username", acc.username)
    put("password", acc.password)
    acc.epgUrl?.let { put("epg_url", it) }
    put("dns_provider", acc.dnsProvider)
    put("auto_refresh_hours", acc.autoRefreshHours)
    put("content_types", buildJsonArray { acc.contentTypes.forEach { add(it) } })
    val sel = acc.categorySelections
    if (!sel.allNull) {
        put("category_selections", buildJsonObject {
            sel.live?.let { put("live", JsonArray(it.map(::JsonPrimitive))) }
            sel.movies?.let { put("movies", JsonArray(it.map(::JsonPrimitive))) }
            sel.series?.let { put("series", JsonArray(it.map(::JsonPrimitive))) }
        })
    }
}

/** Lenient jsonb -> CategorySelections: anything malformed degrades to "all categories". */
internal fun decodeCategorySelections(element: JsonElement?): CategorySelections {
    val obj = element as? JsonObject ?: return CategorySelections()
    fun list(key: String): List<String>? = (obj[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
    return CategorySelections(live = list("live"), movies = list("movies"), series = list("series"))
}
