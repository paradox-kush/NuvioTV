package com.nuvio.tv.data.locallibrary.discovery

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Scans the device's local subnet for Jellyfin servers.
 *
 * Strategy: for every IPv4 host in each site-local interface's /24, fire a
 * `GET /System/Info/Public` probe — HTTPS on 8920 first (Jellyfin's secure
 * default), HTTP on 8096 second. The first response whose body looks like
 * Jellyfin's identity JSON wins; remaining probes are cancelled.
 *
 * Trusts all certificates because home Jellyfin installs almost always use a
 * self-signed cert, and we're only reading a public identity endpoint.
 */
@Singleton
class JellyfinDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Probe result. [url] is e.g. "https://192.168.1.42:8920" — no trailing slash. */
    data class Hit(val url: String, val serverName: String?)

    suspend fun discover(): Hit? = withContext(Dispatchers.IO) {
        val client = buildTrustAllClient()
        val interfaces = localIPv4Interfaces()
        if (interfaces.isEmpty()) {
            Log.w(TAG, "No usable local IPv4 interfaces found — skipping discovery")
            return@withContext null
        }
        for ((subnetBase, selfIp) in interfaces) {
            // HTTPS first, HTTP fallback — explicit user requirement.
            scan(client, subnetBase, selfIp, scheme = "https", port = 8920)?.let { return@withContext it }
            scan(client, subnetBase, selfIp, scheme = "http", port = 8096)?.let { return@withContext it }
        }
        null
    }

    private suspend fun scan(
        client: OkHttpClient,
        subnetBase: String,
        selfIp: String,
        scheme: String,
        port: Int
    ): Hit? = coroutineScope {
        val found = CompletableDeferred<Hit?>()
        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
        val scanScope = CoroutineScope(coroutineContext + SupervisorJob())

        (1..254).forEach { i ->
            val host = "$subnetBase.$i"
            if (host == selfIp) return@forEach
            scanScope.launch {
                if (found.isCompleted) return@launch
                semaphore.withPermit {
                    if (found.isCompleted) return@withPermit
                    probe(client, scheme, host, port)?.let { hit ->
                        found.complete(hit)
                    }
                }
            }
        }
        // Wait either for a hit or for all probes to finish.
        scanScope.launch {
            scanScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList()
                .filter { it != coroutineContext[kotlinx.coroutines.Job] }
                .forEach { it.join() }
            if (!found.isCompleted) found.complete(null)
        }
        val result = found.await()
        scanScope.cancel()
        result
    }

    private fun probe(client: OkHttpClient, scheme: String, host: String, port: Int): Hit? {
        val url = "$scheme://$host:$port/System/Info/Public"
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                if (!looksLikeJellyfin(body)) return null
                val serverName = SERVER_NAME_REGEX.find(body)?.groupValues?.get(1)
                Hit(url = "$scheme://$host:$port", serverName = serverName)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikeJellyfin(body: String): Boolean {
        // The /System/Info/Public endpoint always returns ProductName.
        // "Jellyfin Server" for Jellyfin, "Emby Server" for Emby. We only
        // claim a hit for Jellyfin to avoid mislabeling.
        return body.contains("\"ProductName\"", ignoreCase = false) &&
            body.contains("Jellyfin", ignoreCase = false)
    }

    /** Returns every site-local /24 we should sweep, paired with our own IP in it. */
    private fun localIPv4Interfaces(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) return@forEach
                iface.inetAddresses.toList().forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val host = addr.hostAddress ?: return@forEach
                        val parts = host.split('.')
                        if (parts.size == 4) {
                            out += "${parts[0]}.${parts[1]}.${parts[2]}" to host
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enumerate network interfaces", t)
        }
        return out.distinct()
    }

    private fun buildTrustAllClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    companion object {
        private const val TAG = "JellyfinDiscovery"
        private const val PROBE_TIMEOUT_MS = 1500L
        private const val MAX_CONCURRENT_PROBES = 48
        private val SERVER_NAME_REGEX = Regex("\"ServerName\"\\s*:\\s*\"([^\"]+)\"")
    }
}
