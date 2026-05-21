package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.DirectDebridStreamApi
import com.nuvio.tv.data.remote.dto.StreamClientResolveDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveParsedDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveRawDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveStreamDto
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.DebridSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class DirectDebridStreamSourceTest {
    @After
    fun tearDown() {
        runCatching { unmockkStatic(Log::class) }
    }

    @Test
    fun `preload followed by fetch reuses cached streams`() = runTest {
        val api = FakeDirectDebridStreamApi { Response.success(streamResponse("one")) }
        val source = source(api, settings(), this)

        source.preloadStreams("movie", "tt1")
        advanceUntilIdle()
        val result = source.fetchStreams("movie", "tt1")

        assertTrue(result is DirectDebridStreamFetchResult.Success)
        assertEquals(1, api.calls)
    }

    @Test
    fun `concurrent preload and fetch share in flight request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeDirectDebridStreamApi(gate = gate) { Response.success(streamResponse("one")) }
        val source = source(api, settings(), this)

        source.preloadStreams("movie", "tt1")
        api.started.await()
        val fetched = async { source.fetchStreams("movie", "tt1") }
        advanceUntilIdle()
        assertEquals(1, api.calls)

        gate.complete(Unit)
        val result = fetched.await()
        advanceUntilIdle()

        assertTrue(result is DirectDebridStreamFetchResult.Success)
        assertEquals(1, api.calls)
    }

    @Test
    fun `changed settings fingerprint causes fresh request`() = runTest {
        val api = FakeDirectDebridStreamApi { Response.success(streamResponse("one")) }
        val settings = MutableStateFlow(settings("first"))
        val source = source(api, settings, this)

        assertTrue(source.fetchStreams("movie", "tt1") is DirectDebridStreamFetchResult.Success)
        assertTrue(source.fetchStreams("movie", "tt1") is DirectDebridStreamFetchResult.Success)
        settings.value = settings("second")
        assertTrue(source.fetchStreams("movie", "tt1") is DirectDebridStreamFetchResult.Success)

        assertEquals(2, api.calls)
    }

    @Test
    fun `error results are not cached as streams`() = runTest {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        val api = FakeDirectDebridStreamApi {
            Response.error(500, """{"error":"nope"}""".toResponseBody("application/json".toMediaType()))
        }
        val source = source(api, settings(), this)

        assertTrue(source.fetchStreams("movie", "tt1") is DirectDebridStreamFetchResult.Error)
        assertTrue(source.fetchStreams("movie", "tt1") is DirectDebridStreamFetchResult.Error)

        assertEquals(2, api.calls)
    }

    private fun source(
        api: DirectDebridStreamApi,
        settings: DebridSettings = settings(),
        scope: CoroutineScope
    ): DirectDebridStreamSource {
        return source(api, MutableStateFlow(settings), scope)
    }

    private fun source(
        api: DirectDebridStreamApi,
        settings: MutableStateFlow<DebridSettings>,
        scope: CoroutineScope
    ): DirectDebridStreamSource {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns settings
        return DirectDebridStreamSource(
            dataStore = dataStore,
            api = api,
            encoder = DirectDebridConfigEncoder(),
            formatter = DebridStreamFormatter(DebridStreamTemplateEngine()),
            baseUrlProvider = { "https://debrid.example" },
            scope = scope,
            nowMs = { 1_000L }
        )
    }

    private fun settings(template: String = "default"): DebridSettings {
        return DebridSettings(
            enabled = true,
            torboxApiKey = "tb_token",
            streamNameTemplate = template
        )
    }

    private fun streamResponse(token: String): StreamResponseDto {
        return StreamResponseDto(
            streams = listOf(
                StreamDto(
                    name = "TB 1080p cached $token",
                    description = "Movie.2024.1080p.WEB-DL.H264-GRP.mkv",
                    clientResolve = StreamClientResolveDto(
                        type = "debrid",
                        infoHash = token,
                        fileIdx = 0,
                        magnetUri = "magnet:?xt=urn:btih:$token",
                        filename = "Movie.2024.1080p.WEB-DL.H264-GRP.mkv",
                        title = "Movie",
                        service = DebridProviders.TORBOX_ID,
                        isCached = true,
                        stream = StreamClientResolveStreamDto(
                            raw = StreamClientResolveRawDto(
                                parsed = StreamClientResolveParsedDto(
                                    resolution = "1080p",
                                    quality = "WEB-DL",
                                    codec = "h264",
                                    group = "GRP"
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    private class FakeDirectDebridStreamApi(
        private val gate: CompletableDeferred<Unit>? = null,
        private val response: () -> Response<StreamResponseDto>
    ) : DirectDebridStreamApi {
        var calls = 0
            private set
        val started = CompletableDeferred<Unit>()

        override suspend fun getClientStreams(url: String): Response<StreamResponseDto> {
            calls += 1
            started.complete(Unit)
            gate?.await()
            return response()
        }
    }
}
