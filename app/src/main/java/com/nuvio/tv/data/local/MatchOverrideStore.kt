package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.locallibrary.LocalMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists TMDB match results for scanned items so:
 *  - re-scans don't repeat TMDB lookups for items that haven't changed
 *  - user-set overrides ([LocalMatch.userSet]) are NEVER overwritten by auto-matching
 */
@Singleton
class MatchOverrideStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "local_library_matches"
    }

    private val gson = Gson()
    private val matchesKey = stringPreferencesKey("matches_json")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val matches: Flow<Map<String, LocalMatch>> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs -> parse(prefs[matchesKey]) }
        }

    suspend fun get(itemKey: String): LocalMatch? = matches.first()[itemKey]

    /** Upserts a match. Auto-matches MUST NOT replace a stored userSet=true match. */
    suspend fun put(match: LocalMatch) {
        store().edit { prefs ->
            val current = parse(prefs[matchesKey]).toMutableMap()
            val existing = current[match.itemKey]
            if (existing?.userSet == true && !match.userSet) return@edit
            current[match.itemKey] = match
            prefs[matchesKey] = gson.toJson(current)
        }
    }

    suspend fun remove(itemKey: String) {
        store().edit { prefs ->
            val current = parse(prefs[matchesKey]).toMutableMap()
            current.remove(itemKey)
            prefs[matchesKey] = gson.toJson(current)
        }
    }

    /** Drops every match whose key starts with `${sourceId}|` — call when a source is removed. */
    suspend fun removeForSource(sourceId: String) {
        val prefix = "$sourceId|"
        store().edit { prefs ->
            val current = parse(prefs[matchesKey]).filterKeys { !it.startsWith(prefix) }
            prefs[matchesKey] = gson.toJson(current)
        }
    }

    private fun parse(json: String?): Map<String, LocalMatch> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, LocalMatch>>() {}.type
            gson.fromJson<Map<String, LocalMatch>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
