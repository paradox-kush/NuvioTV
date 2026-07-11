package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.XtreamAccount
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Owns one [StalkerSession] per Stalker playlist, keyed by account id + a config fingerprint so an
 * edited portal/MAC gets a fresh session.
 *
 * No keep-alive/watchdog: a portal expires an idle session after `watchdog_timeout` (~100–120s), but
 * the session re-handshakes on demand (single-flight — see [StalkerSession.reauthenticate]) the next
 * time it's used, which is exactly how a real STB behaves after it sleeps.
 */
@Singleton
class StalkerSessionManager @Inject constructor(
    @Named("stalker") private val http: OkHttpClient
) {
    private data class Entry(val session: StalkerSession, val fingerprint: String)

    private val sessions = ConcurrentHashMap<String, Entry>()

    /** The session for [account], recreated if the account's Stalker config changed since last time. */
    fun sessionFor(account: XtreamAccount): StalkerSession {
        val fp = fingerprint(account)
        val existing = sessions[account.id]
        if (existing != null && existing.fingerprint == fp) return existing.session
        val fresh = StalkerSession(account, http)
        sessions[account.id] = Entry(fresh, fp)
        return fresh
    }

    /** Drop a session (playlist removed/edited) so the next access re-handshakes. */
    fun evict(accountId: String) { sessions.remove(accountId) }

    fun clear() { sessions.clear() }

    /** Any change to these invalidates the session (new handshake/device identity needed). */
    private fun fingerprint(a: XtreamAccount): String =
        listOf(a.portalUrl, a.macAddress, a.serialNumber, a.deviceId, a.sendDeviceId.toString(),
            a.stalkerUsername, a.stalkerPassword).joinToString("|")
}
