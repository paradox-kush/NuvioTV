package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.nuvio.tv.core.iptv.XtreamAccount
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression for the "works first login, portal error on return" bug.
 *
 * A Stalker handshake OVERWRITES the MAC's token server-side, so N concurrent browse calls that all
 * hit a stale token must trigger exactly ONE re-handshake — not N. Without the single-flight guard in
 * [StalkerSession.reauthenticate], each stale call re-handshakes, rotating the token out from under the
 * others' retries -> "portal error".
 *
 * Determinism: the first [CONCURRENCY] content calls (the initial batch, all carrying the session's
 * first token) are answered stale and held at a barrier, so they ALL enter the re-auth path together
 * before any of them completes. Retries (after re-auth) are answered valid. Pre-fix that batch fans out
 * into N handshakes; the guard collapses it to one.
 */
class StalkerSessionReauthTest {

    private lateinit var server: MockWebServer
    private val handshakes = AtomicInteger(0)
    private val contentCalls = AtomicInteger(0)
    private val staleGate = CyclicBarrier(CONCURRENCY)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.url.queryParameter("action")) {
                    "handshake" -> """{"js":{"token":"T${handshakes.incrementAndGet()}"}}"""
                    "get_profile" -> """{"js":{"watchdog_timeout":120}}"""
                    else -> {
                        // Token-agnostic on purpose: endpoint probing already burns handshakes, so the
                        // live session token isn't predictable. The first CONCURRENCY content calls are
                        // the initial concurrent batch — answer them stale (held at the barrier so they
                        // re-auth together); everything after (the retries) is valid.
                        if (contentCalls.incrementAndGet() <= CONCURRENCY) {
                            runCatching { staleGate.await(5, TimeUnit.SECONDS) }
                            """{"js":false}"""
                        } else {
                            """{"js":[]}"""   // empty list = valid (no channels)
                        }
                    }
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        unmockkStatic(Log::class)
    }

    @Test
    fun `concurrent stale requests trigger exactly one re-handshake`() = runBlocking {
        val account = XtreamAccount(
            id = "t", name = "portal", baseUrl = "", username = "", password = "",
            sourceType = "stalker",
            portalUrl = server.url("/").toString().trimEnd('/'),
            macAddress = "00:1A:79:58:B3:A6",
        )
        // Lift OkHttp's default per-host cap (5) so all CONCURRENCY calls hit the portal at once —
        // that simultaneity is what the barrier relies on to force a genuine re-auth stampede.
        val client = OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 64 })
            .build()
        val session = StalkerSession(account, client)

        val results = (1..CONCURRENCY).map {
            async(Dispatchers.IO) {
                runCatching { session.request(mapOf("type" to "itv", "action" to "get_genres")) }
            }
        }.awaitAll()

        assertEquals(CONCURRENCY, results.count { it.isSuccess })   // every call recovered
        // Initial auth = 2 handshakes (endpoint probe + the real handshake). The stampede must add
        // exactly ONE more (the single collapsed re-auth), not one-per-stale-call.
        assertEquals(3, handshakes.get())
    }

    private companion object { const val CONCURRENCY = 6 }
}
