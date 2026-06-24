package com.nuvio.tv.data.repository

import android.net.Uri
import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.remote.api.PlaybackIssueReportApi
import com.nuvio.tv.data.remote.dto.PlaybackIssueAppDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueContentDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueDeviceDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueDiagnosticsDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueErrorDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueLoadingDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueLoadingEventDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlayerDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackAnalyticsDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackEventDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackFormatDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackLoadDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackLoadErrorDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueReportRequestDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueStreamDto
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackIssueReportInput(
    val diagnostics: LastPlaybackDiagnostics,
    val error: PlaybackIssueErrorInput,
    val title: String?,
    val contentName: String?,
    val contentId: String?,
    val contentType: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val releaseYear: String?,
    val streamUrl: String,
    val streamMimeType: String?,
    val streamName: String?,
    val addonName: String?,
    val videoHash: String?,
    val videoSize: Long?,
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, String>,
    val playerEngine: String,
    val loading: PlaybackIssueLoadingInput,
    val positionMs: Long?,
    val durationMs: Long?,
    val bufferedPositionMs: Long?,
    val selectedAudioTrack: String?,
    val selectedSubtitleTrack: String?,
    val isTorrentStream: Boolean,
    val playbackAnalytics: PlaybackIssuePlaybackAnalyticsInput?
)

data class PlaybackIssueLoadingInput(
    val phase: String,
    val message: String?,
    val progress: Float?,
    val elapsedMs: Long,
    val phaseElapsedMs: Long,
    val reportReason: String,
    val loadingOverlayVisible: Boolean,
    val loadingStatusVisible: Boolean,
    val hasRenderedFirstFrame: Boolean,
    val exoPlayerCreated: Boolean,
    val exoPlaybackState: Int?,
    val exoPlaybackStateName: String?,
    val exoIsLoading: Boolean?,
    val exoPlayWhenReady: Boolean?,
    val mpvAttached: Boolean,
    val startupRetryCount: Int,
    val errorRetryCount: Int,
    val timeoutRecoveryAttempts: Int,
    val isLoadingAddonSubtitles: Boolean,
    val addonSubtitlesCount: Int,
    val isLoadingSourceStreams: Boolean,
    val isLoadingEpisodeStreams: Boolean,
    val torrentDownloadSpeed: Long,
    val torrentPeers: Int,
    val torrentSeeds: Int,
    val events: List<PlaybackIssueLoadingEventInput>
)

data class PlaybackIssueLoadingEventInput(
    val timeMs: Long,
    val elapsedMs: Long,
    val phase: String,
    val message: String?,
    val progress: Float?,
    val detail: String?
)

data class PlaybackIssueErrorInput(
    val displayMessage: String?,
    val errorCode: Int?,
    val errorCodeName: String?,
    val exceptionClass: String?,
    val causeClass: String?,
    val causeMessage: String?,
    val httpStatus: Int?
)

@Singleton
class PlaybackIssueReportRepository @Inject constructor(
    private val playbackIssueReportApi: PlaybackIssueReportApi
) {
    suspend fun submit(input: PlaybackIssueReportInput): Result<String> = runCatching {
        if (BuildConfig.PLAYBACK_REPORTS_BASE_URL.isBlank()) {
            error("Playback report endpoint is not configured")
        }
        val response = playbackIssueReportApi.createPlaybackIssueReport(input.toDto())
        if (!response.isSuccessful) {
            error("Playback report upload failed: HTTP ${response.code()}")
        }
        val body = response.body()
        val reportId = body?.reportId?.trim()?.takeIf { it.isNotBlank() }
            ?: body?.id?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Playback report upload failed: missing report id")
        reportId
    }

    private fun PlaybackIssueReportInput.toDto(): PlaybackIssueReportRequestDto {
        val streamUri = runCatching { Uri.parse(streamUrl) }.getOrNull()
        val urlWithoutQuery = streamUri?.withoutQueryAndFragment()
        return PlaybackIssueReportRequestDto(
            schemaVersion = 1,
            createdAtMs = System.currentTimeMillis(),
            app = PlaybackIssueAppDto(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                debugBuild = BuildConfig.IS_DEBUG_BUILD
            ),
            device = PlaybackIssueDeviceDto(
                manufacturer = Build.MANUFACTURER.orEmpty().limit(80),
                brand = Build.BRAND.orEmpty().limit(80),
                model = Build.MODEL.orEmpty().limit(120),
                product = Build.PRODUCT.orEmpty().limit(120),
                androidRelease = Build.VERSION.RELEASE.orEmpty().limit(40),
                sdkInt = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS.orEmpty().map { it.limit(40) }
            ),
            content = PlaybackIssueContentDto(
                title = title.cleanText(160),
                contentName = contentName.cleanText(160),
                contentId = contentId.cleanText(160),
                contentType = contentType.cleanText(60),
                videoId = videoId.cleanText(160),
                season = season,
                episode = episode,
                episodeTitle = episodeTitle.cleanText(160),
                releaseYear = releaseYear.cleanText(20)
            ),
            stream = PlaybackIssueStreamDto(
                host = streamUri?.host.cleanText(160) ?: diagnostics.host.cleanText(160),
                scheme = streamUri?.scheme.cleanText(24),
                port = streamUri?.port?.takeIf { it >= 0 },
                urlHash = streamUrl.sha256OrNull(),
                urlWithoutQueryHash = urlWithoutQuery.sha256OrNull(),
                fileExtension = streamUri.fileExtension(),
                mimeType = streamMimeType.cleanText(120),
                streamName = streamName.cleanText(160),
                addonName = addonName.cleanText(120),
                videoHash = videoHash.cleanText(160),
                videoSize = videoSize,
                requestHeaderNames = requestHeaders.safeHeaderNames(),
                responseHeaderNames = responseHeaders.safeHeaderNames()
            ),
            player = PlaybackIssuePlayerDto(
                engine = playerEngine.limit(80),
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                audioTrack = selectedAudioTrack.cleanText(160),
                subtitleTrack = selectedSubtitleTrack.cleanText(160),
                isTorrentStream = isTorrentStream
            ),
            loading = loading.toDto(),
            error = PlaybackIssueErrorDto(
                displayMessage = error.displayMessage.cleanText(1000),
                errorCode = error.errorCode,
                errorCodeName = error.errorCodeName.cleanText(120),
                exceptionClass = error.exceptionClass.cleanText(160),
                causeClass = error.causeClass.cleanText(160),
                causeMessage = error.causeMessage.cleanText(1000),
                httpStatus = error.httpStatus
            ),
            diagnostics = diagnostics.toDto(),
            playbackAnalytics = playbackAnalytics?.toDto()
        )
    }

    private fun PlaybackIssueLoadingInput.toDto(): PlaybackIssueLoadingDto =
        PlaybackIssueLoadingDto(
            phase = phase.limit(80),
            message = message.cleanText(240),
            progress = progress?.coerceIn(0f, 1f),
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            phaseElapsedMs = phaseElapsedMs.coerceAtLeast(0L),
            reportReason = reportReason.limit(80),
            loadingOverlayVisible = loadingOverlayVisible,
            loadingStatusVisible = loadingStatusVisible,
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            exoPlayerCreated = exoPlayerCreated,
            exoPlaybackState = exoPlaybackState,
            exoPlaybackStateName = exoPlaybackStateName.cleanText(80),
            exoIsLoading = exoIsLoading,
            exoPlayWhenReady = exoPlayWhenReady,
            mpvAttached = mpvAttached,
            startupRetryCount = startupRetryCount,
            errorRetryCount = errorRetryCount,
            timeoutRecoveryAttempts = timeoutRecoveryAttempts,
            isLoadingAddonSubtitles = isLoadingAddonSubtitles,
            addonSubtitlesCount = addonSubtitlesCount.coerceAtLeast(0),
            isLoadingSourceStreams = isLoadingSourceStreams,
            isLoadingEpisodeStreams = isLoadingEpisodeStreams,
            torrentDownloadSpeed = torrentDownloadSpeed.coerceAtLeast(0L),
            torrentPeers = torrentPeers.coerceAtLeast(0),
            torrentSeeds = torrentSeeds.coerceAtLeast(0),
            events = events.takeLast(80).map { event ->
                PlaybackIssueLoadingEventDto(
                    timeMs = event.timeMs,
                    elapsedMs = event.elapsedMs.coerceAtLeast(0L),
                    phase = event.phase.limit(80),
                    message = event.message.cleanText(240),
                    progress = event.progress?.coerceIn(0f, 1f),
                    detail = event.detail.cleanText(240)
                )
            }
        )

    private fun LastPlaybackDiagnostics.toDto(): PlaybackIssueDiagnosticsDto =
        PlaybackIssueDiagnosticsDto(
            timestampMs = timestampMs,
            host = host.limit(160),
            hdrCapsKnown = hdrCapsKnown,
            displayDv = displayDv,
            displayHdr10 = displayHdr10,
            displayHdr10Plus = displayHdr10Plus,
            codecDv7Supported = codecDv7Supported,
            dv81DecoderName = dv81DecoderName.cleanText(160),
            bridgeReady = bridgeReady,
            bridgeVersion = bridgeVersion.cleanText(120),
            bridgeReason = bridgeReason.cleanText(180),
            dv7ModeRequested = dv7ModeRequested.limit(80),
            dv7ModeEffective = dv7ModeEffective.limit(80),
            dv7AutoDecision = dv7AutoDecision.cleanText(80),
            bufferEngineEnabled = bufferEngineEnabled,
            parallelNetworkEnabled = parallelNetworkEnabled,
            firstFrameMs = firstFrameMs,
            dv7DoviCalls = dv7DoviCalls,
            dv7DoviSuccess = dv7DoviSuccess,
            dv7DoviSignalRewrites = dv7DoviSignalRewrites,
            dvSourceProfile = dvSourceProfile.cleanText(80),
            videoResolution = videoResolution.cleanText(80),
            videoCodec = videoCodec.cleanText(120),
            videoHdrType = videoHdrType.cleanText(120),
            rebufferCount = rebufferCount,
            rebufferTotalMs = rebufferTotalMs,
            result = result.limit(1000)
        )

    private fun PlaybackIssuePlaybackAnalyticsInput.toDto(): PlaybackIssuePlaybackAnalyticsDto =
        PlaybackIssuePlaybackAnalyticsDto(
            schemaVersion = schemaVersion,
            sessionStartedAtMs = sessionStartedAtMs,
            capturedAtMs = capturedAtMs,
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            eventCount = eventCount.coerceAtLeast(0),
            lastEventElapsedMs = lastEventElapsedMs?.coerceAtLeast(0L),
            playbackState = playbackState,
            playbackStateName = playbackStateName.cleanText(80),
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            isLoading = isLoading,
            positionMs = positionMs?.coerceAtLeast(0L),
            bufferedPositionMs = bufferedPositionMs?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L),
            bufferedPercentage = bufferedPercentage?.coerceIn(0, 100),
            firstFrameElapsedMs = firstFrameElapsedMs?.coerceAtLeast(0L),
            renderedFirstFrameCount = renderedFirstFrameCount.coerceAtLeast(0),
            rebufferCount = rebufferCount.coerceAtLeast(0),
            rebufferTotalMs = rebufferTotalMs.coerceAtLeast(0L),
            currentRebufferMs = currentRebufferMs.coerceAtLeast(0L),
            positionStallCount = positionStallCount.coerceAtLeast(0),
            longestPositionStallMs = longestPositionStallMs.coerceAtLeast(0L),
            droppedFrames = droppedFrames.coerceAtLeast(0),
            maxDroppedFramesInEvent = maxDroppedFramesInEvent.coerceAtLeast(0),
            videoDecoderName = videoDecoderName.cleanText(160),
            videoDecoderInitMs = videoDecoderInitMs?.coerceAtLeast(0L),
            videoDecoderReleaseCount = videoDecoderReleaseCount.coerceAtLeast(0),
            videoRenderedOutputBuffers = videoRenderedOutputBuffers?.coerceAtLeast(0),
            videoDroppedBuffers = videoDroppedBuffers?.coerceAtLeast(0),
            videoMaxConsecutiveDroppedBuffers = videoMaxConsecutiveDroppedBuffers?.coerceAtLeast(0),
            videoFrameProcessingOffsetAverageUs = videoFrameProcessingOffsetAverageUs,
            videoFormat = videoFormat?.toDto(),
            audioDecoderName = audioDecoderName.cleanText(160),
            audioDecoderInitMs = audioDecoderInitMs?.coerceAtLeast(0L),
            audioDecoderReleaseCount = audioDecoderReleaseCount.coerceAtLeast(0),
            audioUnderrunCount = audioUnderrunCount.coerceAtLeast(0),
            audioUnderrunBufferSize = audioUnderrunBufferSize?.coerceAtLeast(0),
            audioUnderrunBufferSizeMs = audioUnderrunBufferSizeMs?.coerceAtLeast(0L),
            audioUnderrunElapsedSinceLastFeedMs = audioUnderrunElapsedSinceLastFeedMs?.coerceAtLeast(0L),
            audioFormat = audioFormat?.toDto(),
            bandwidthEstimateBps = bandwidthEstimateBps?.coerceAtLeast(0L),
            bandwidthTransferDurationMs = bandwidthTransferDurationMs?.coerceAtLeast(0),
            bandwidthBytesTransferred = bandwidthBytesTransferred?.coerceAtLeast(0L),
            loadStartedCount = loadStartedCount.coerceAtLeast(0),
            loadCompletedCount = loadCompletedCount.coerceAtLeast(0),
            loadCanceledCount = loadCanceledCount.coerceAtLeast(0),
            loadErrorCount = loadErrorCount.coerceAtLeast(0),
            totalBytesLoaded = totalBytesLoaded.coerceAtLeast(0L),
            lastLoad = lastLoad?.toDto(),
            lastLoadError = lastLoadError?.toDto(),
            events = events.takeLast(140).map { it.toDto() }
        )

    private fun PlaybackIssuePlaybackFormatInput.toDto(): PlaybackIssuePlaybackFormatDto =
        PlaybackIssuePlaybackFormatDto(
            trackType = trackType.cleanText(40),
            sampleMimeType = sampleMimeType.cleanText(120),
            containerMimeType = containerMimeType.cleanText(120),
            codecs = codecs.cleanText(160),
            id = id.cleanText(160),
            label = label.cleanText(160),
            language = language.cleanText(40),
            width = width?.coerceAtLeast(0),
            height = height?.coerceAtLeast(0),
            frameRate = frameRate?.takeIf { it > 0f },
            bitrate = bitrate?.coerceAtLeast(0),
            channelCount = channelCount?.coerceAtLeast(0),
            sampleRate = sampleRate?.coerceAtLeast(0),
            colorTransfer = colorTransfer,
            selectionFlags = selectionFlags,
            roleFlags = roleFlags,
            support = support.cleanText(80),
            decoderReuseResult = decoderReuseResult.cleanText(80),
            decoderDiscardReasons = decoderDiscardReasons
        )

    private fun PlaybackIssuePlaybackLoadInput.toDto(): PlaybackIssuePlaybackLoadDto =
        PlaybackIssuePlaybackLoadDto(
            host = host.cleanText(160),
            scheme = scheme.cleanText(24),
            dataType = dataType.cleanText(80),
            trackType = trackType.cleanText(40),
            httpMethod = httpMethod.cleanText(12),
            position = position?.coerceAtLeast(0L),
            length = length?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L),
            bytesLoaded = bytesLoaded?.coerceAtLeast(0L),
            responseHeaderNames = responseHeaderNames.mapNotNull { it.cleanText(80)?.lowercase() }
                .distinct()
                .sorted()
                .take(40)
        )

    private fun PlaybackIssuePlaybackLoadErrorInput.toDto(): PlaybackIssuePlaybackLoadErrorDto =
        PlaybackIssuePlaybackLoadErrorDto(
            host = host.cleanText(160),
            dataType = dataType.cleanText(80),
            trackType = trackType.cleanText(40),
            exceptionClass = exceptionClass.cleanText(160),
            message = message.cleanText(500),
            httpStatus = httpStatus,
            wasCanceled = wasCanceled,
            bytesLoaded = bytesLoaded?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L)
        )

    private fun PlaybackIssuePlaybackEventInput.toDto(): PlaybackIssuePlaybackEventDto =
        PlaybackIssuePlaybackEventDto(
            timeMs = timeMs,
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            name = name.limit(80),
            playbackState = playbackState.cleanText(80),
            positionMs = positionMs?.coerceAtLeast(0L),
            bufferedPositionMs = bufferedPositionMs?.coerceAtLeast(0L),
            details = details.entries
                .mapNotNull { (key, value) ->
                    key.cleanText(50)?.let { safeKey ->
                        value.cleanText(240)?.let { safeValue -> safeKey to safeValue }
                    }
                }
                .take(16)
                .toMap()
        )

    private fun Uri.withoutQueryAndFragment(): String? {
        val safeScheme = scheme?.takeIf { it.isNotBlank() } ?: return null
        val safeHost = host?.takeIf { it.isNotBlank() } ?: return null
        val authority = if (port >= 0) "$safeHost:$port" else safeHost
        return buildUpon()
            .scheme(safeScheme)
            .encodedAuthority(authority)
            .encodedQuery(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun Uri?.fileExtension(): String? {
        val segment = this?.lastPathSegment?.substringBefore('?')?.substringBefore('#')
            ?: return null
        val extension = segment.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.length in 1..10 }
            ?.takeIf { it.all { c -> c.isLetterOrDigit() } }
        return extension
    }

    private fun Map<String, String>.safeHeaderNames(): List<String> =
        keys.mapNotNull { it.cleanText(80)?.lowercase() }
            .distinct()
            .sorted()
            .take(40)

    private fun String?.sha256OrNull(): String? {
        val value = this?.takeIf { it.isNotBlank() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun String?.cleanText(maxLength: Int): String? =
        this?.trim()
            ?.redactSensitiveText()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() }
            ?.limit(maxLength)

    private fun String.redactSensitiveText(): String =
        replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "[redacted-url]")
            .replace(Regex("""(?i)(bearer|token|apikey|api_key|authorization|cookie)=\S+"""), "$1=[redacted]")

    private fun String.limit(maxLength: Int): String =
        if (length <= maxLength) this else take(maxLength)
}
