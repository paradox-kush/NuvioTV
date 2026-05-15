package com.nuvio.tv.data.locallibrary

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted at-rest store for local-library credentials (Jellyfin tokens, SMB passwords).
 * Falls back to plain SharedPreferences only when the secure backend is unavailable
 * (very old / broken keystore) — we never silently fail to write secrets.
 */
@Singleton
class LocalLibraryCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy { open() }

    private fun open(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        Log.e(TAG, "Falling back to plaintext SharedPreferences for credentials", t)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun getSecret(sourceId: String, field: Field): String? =
        prefs.getString(keyFor(sourceId, field), null)

    fun putSecret(sourceId: String, field: Field, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrEmpty()) remove(keyFor(sourceId, field)) else putString(keyFor(sourceId, field), value)
        }.apply()
    }

    fun clearSource(sourceId: String) {
        val prefix = "$sourceId|"
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
    }

    enum class Field { JELLYFIN_TOKEN, JELLYFIN_USER_ID, SMB_USERNAME, SMB_PASSWORD, SMB_DOMAIN }

    private fun keyFor(sourceId: String, field: Field) = "$sourceId|${field.name}"

    companion object {
        private const val FILE = "local_library_credentials"
        private const val TAG = "LocalLibraryCredentialStore"
    }
}
