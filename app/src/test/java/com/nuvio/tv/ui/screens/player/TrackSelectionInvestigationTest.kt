package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.source.chunk.MediaChunk
import android.text.TextUtils
import android.os.SystemClock
import android.content.Context
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.RendererCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.VideoCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URL
import java.net.HttpURLConnection

class TrackSelectionInvestigationTest {

    @Test
    fun testTrackSelectionWithVixsrcManifest() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val seq = firstArg<CharSequence?>()
            seq == null || seq.isEmpty()
        }

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        // Create the 3 video formats from the vixsrc playlist:
        // 1080p (4.5 Mbps), 720p (2.15 Mbps), 480p (1.2 Mbps)
        val format1080p = Format.Builder()
            .setId("1")
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .setPeakBitrate(4500000)
            .setWidth(1920)
            .setHeight(1080)
            .build()

        val format720p = Format.Builder()
            .setId("2")
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .setPeakBitrate(2150000)
            .setWidth(1280)
            .setHeight(720)
            .build()

        val format480p = Format.Builder()
            .setId("3")
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .setPeakBitrate(1200000)
            .setWidth(854)
            .setHeight(480)
            .build()

        // TrackGroup formats must be sorted by quality (highest bandwidth/quality first)
        val trackGroup = TrackGroup(format1080p, format720p, format480p)
        val tracks = intArrayOf(0, 1, 2) // Indices in the group

        // Let's test different estimated bandwidths
        val testBandwidths = listOf(
            1_000_000L to "Under 480p bandwidth",
            1_500_000L to "Sufficient for 480p",
            2_000_000L to "Between 480p and 720p",
            3_500_000L to "Sufficient for 720p (with 0.7 fraction)",
            5_000_000L to "Between 720p and 1080p",
            7_000_000L to "Sufficient for 1080p (with 0.7 fraction)",
            25_000_000L to "Initial estimate (25 Mbps)"
        )

        for ((bandwidth, description) in testBandwidths) {
            val bandwidthMeter = mockk<BandwidthMeter>(relaxed = true)
            every { bandwidthMeter.bitrateEstimate } returns bandwidth
            every { bandwidthMeter.timeToFirstByteEstimateUs } returns C.TIME_UNSET

            // Instantiate AdaptiveTrackSelection with default values
            val adaptiveSelection = AdaptiveTrackSelection(
                trackGroup,
                tracks,
                bandwidthMeter
            )

            // Update selected track (simulating playback update at start)
            adaptiveSelection.updateSelectedTrack(
                /* playbackPositionUs= */ 0,
                /* bufferedDurationUs= */ 0,
                /* playlistTimelineEngineDelayUs= */ C.TIME_UNSET,
                /* queue= */ emptyList(),
                /* mediaChunkIterators= */ emptyArray()
            )

            val selectedIndex = adaptiveSelection.selectedIndex
            val selectedFormat = adaptiveSelection.getFormat(selectedIndex)
            
            println("--- Bandwidth: ${bandwidth / 1000} kbps ($description) ---")
            println("Effective Bandwidth (0.7x): ${(bandwidth * 0.7) / 1000} kbps")
            println("Selected Quality: ${selectedFormat.height}p (Bitrate: ${selectedFormat.bitrate / 1000} kbps, Index: $selectedIndex)")
        }
    }

    @Test
    fun testTrackSelectionWithDecoderCapabilityLimits() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val seq = firstArg<CharSequence?>()
            seq == null || seq.isEmpty()
        }

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        val format1080p = Format.Builder().setId("1").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(4500000).setWidth(1920).setHeight(1080).build()
        val format720p = Format.Builder().setId("2").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(2150000).setWidth(1280).setHeight(720).build()
        val format480p = Format.Builder().setId("3").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(1200000).setWidth(854).setHeight(480).build()

        val trackGroup = TrackGroup(format1080p, format720p, format480p)
        
        // Simulating that the device only supports 480p (Index 2) as H.264 High Profile @ Level 4.0
        // is unsupported for higher resolutions on this TV device.
        val supportedTracks = intArrayOf(2) // Only 480p index

        val bandwidthMeter = mockk<BandwidthMeter>(relaxed = true)
        every { bandwidthMeter.bitrateEstimate } returns 25_000_000L // 25 Mbps (Plenty of bandwidth)
        every { bandwidthMeter.timeToFirstByteEstimateUs } returns C.TIME_UNSET

        val adaptiveSelection = AdaptiveTrackSelection(
            trackGroup,
            supportedTracks,
            bandwidthMeter
        )

        adaptiveSelection.updateSelectedTrack(0, 0, C.TIME_UNSET, emptyList(), emptyArray())

        val selectedIndex = adaptiveSelection.selectedIndex
        val selectedFormat = adaptiveSelection.getFormat(selectedIndex)

        println("--- Decoder capability constraint test ---")
        println("Raw Bandwidth: 25 Mbps")
        println("Supported tracks: Only 480p")
        println("Selected Quality: ${selectedFormat.height}p (Bitrate: ${selectedFormat.bitrate / 1000} kbps)")
        
        assertEquals(480, selectedFormat.height)
    }

    @Test
    fun testTrackSelectionWithLatencyPenalty() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val seq = firstArg<CharSequence?>()
            seq == null || seq.isEmpty()
        }

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        val format1080p = Format.Builder().setId("1").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(4500000).setWidth(1920).setHeight(1080).build()
        val format720p = Format.Builder().setId("2").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(2150000).setWidth(1280).setHeight(720).build()
        val format480p = Format.Builder().setId("3").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(1200000).setWidth(854).setHeight(480).build()

        val trackGroup = TrackGroup(format1080p, format720p, format480p)
        val tracks = intArrayOf(0, 1, 2)

        // Simulate ongoing playback with a last chunk duration of 4 seconds
        val mockChunk = object : MediaChunk(
            mockk(relaxed = true),
            mockk(relaxed = true),
            format1080p, // trackFormat (non-null)
            C.SELECTION_REASON_UNKNOWN,
            null,
            0L, // startTimeUs
            4_000_000L, // endTimeUs (4 seconds duration)
            0L // chunkIndex
        ) {
            override fun isLoadCompleted(): Boolean = true
            override fun cancelLoad() {}
            override fun load() {}
        }
        val queue = listOf(mockChunk)

        // High raw bandwidth of 25 Mbps
        val rawBandwidth = 25_000_000L

        // Test different TTFBs (latency)
        val testLatencies = listOf(
            0L to "No latency",
            500_000L to "0.5s latency (TTFB)",
            1_000_000L to "1.0s latency (TTFB)",
            2_000_000L to "2.0s latency (TTFB)",
            3_000_000L to "3.0s latency (TTFB)",
            3_500_000L to "3.5s latency (TTFB)"
        )

        println("--- Latency (TTFB) penalty test (Raw Bandwidth: 25 Mbps, Segment: 4s) ---")
        for ((ttfbUs, description) in testLatencies) {
            val bandwidthMeter = mockk<BandwidthMeter>(relaxed = true)
            every { bandwidthMeter.bitrateEstimate } returns rawBandwidth
            every { bandwidthMeter.timeToFirstByteEstimateUs } returns ttfbUs

            val adaptiveSelection = AdaptiveTrackSelection(
                trackGroup,
                tracks,
                bandwidthMeter
            )

            adaptiveSelection.updateSelectedTrack(0, 0, C.TIME_UNSET, queue, emptyArray())

            val selectedIndex = adaptiveSelection.selectedIndex
            val selectedFormat = adaptiveSelection.getFormat(selectedIndex)
            
            val cautiousBandwidth = rawBandwidth * 0.7
            val availableTimeFactor = (4_000_000.0 - ttfbUs) / 4_000_000.0
            val mathEffectiveBandwidth = if (availableTimeFactor > 0) cautiousBandwidth * availableTimeFactor else 0.0

            println("TTFB: ${ttfbUs / 1000} ms ($description) -> Effective Bandwidth: ${(mathEffectiveBandwidth / 1000).toInt()} kbps -> Selected: ${selectedFormat.height}p")
        }
    }


    @Test
    fun testExoPlayerCodecCapabilitiesEvaluation() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<Throwable>()) } returns 0
        every { android.util.Log.w(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val seq = firstArg<CharSequence?>()
            seq == null || seq.isEmpty()
        }

        // Helper to construct a real android.util.Pair and set its final fields via reflection on JVM
        fun createAndroidPair(first: Int, second: Int): android.util.Pair<Int, Int> {
            val pair = android.util.Pair(first, second)
            try {
                val firstField = android.util.Pair::class.java.getField("first")
                firstField.isAccessible = true
                firstField.set(pair, first)

                val secondField = android.util.Pair::class.java.getField("second")
                secondField.isAccessible = true
                secondField.set(pair, second)
            } catch (e: Exception) {
                println("Failed to set fields on Pair: ${e.message}")
            }
            return pair
        }

        // Statically mock MediaCodecUtil to return simulated profile/level pairs, bypassing android.util.Pair JVM stub limitations
        mockkStatic(androidx.media3.exoplayer.mediacodec.MediaCodecUtil::class)
        every { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(any()) } answers {
            val format = firstArg<Format>()
            val codecs = format.codecs ?: ""
            val profileLevel = when {
                codecs.contains("640028") -> 100 to 40  // High Profile @ Level 4.0
                codecs.contains("64002a") -> 100 to 42  // High Profile @ Level 4.2
                codecs.contains("4d401f") -> 77 to 31   // Main Profile @ Level 3.1
                else -> null
            }
            if (profileLevel != null) {
                createAndroidPair(profileLevel.first, profileLevel.second)
            } else {
                null
            }
        }

        // Create formats representing the streams we want to check
        val formats = listOf(
            Format.Builder().setId("1080p").setSampleMimeType("video/avc").setCodecs("avc1.640028").setWidth(1920).setHeight(1080).build(), // High Profile @ L4.0 (avc1.640028)
            Format.Builder().setId("720p").setSampleMimeType("video/avc").setCodecs("avc1.640028").setWidth(1280).setHeight(720).build(),   // High Profile @ L4.0 (avc1.640028)
            Format.Builder().setId("480p").setSampleMimeType("video/avc").setCodecs("avc1.640028").setWidth(854).setHeight(480).build(),   // High Profile @ L4.0 (avc1.640028)
            Format.Builder().setId("1080p_L4.2").setSampleMimeType("video/avc").setCodecs("avc1.64002a").setWidth(1920).setHeight(1080).build(), // High Profile @ L4.2 (avc1.64002a)
            Format.Builder().setId("1080p_Main_L3.1").setSampleMimeType("video/avc").setCodecs("avc1.4d401f").setWidth(1920).setHeight(1080).build() // Main Profile @ L3.1 (avc1.4d401f)
        )

        // Define simulated decoder capability profiles:
        // H.264 profiles: AVCProfileMain = 77, AVCProfileHigh = 100
        // H.264 levels: AVCLevel31 = 31, AVCLevel4 = 40, AVCLevel42 = 42
        val testDecoderCapabilities = listOf(
            "High Profile Level 4.0 Decoder" to listOf(100 to 40),
            "High Profile Level 3.1 Decoder" to listOf(100 to 31),
            "Main Profile Level 4.0 Decoder" to listOf(77 to 40)
        )

        for ((decoderName, profileLevels) in testDecoderCapabilities) {
            println("\n--- Testing with simulated decoder: $decoderName ---")
            
            // Map the profile-level pairs to mock CodecProfileLevel objects using MediaCodecUtil helper
            val mockProfileLevels = profileLevels.map { (p, l) ->
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel(p, l)
            }.toTypedArray()

            // Mock android.media.MediaCodecInfo capabilities
            val videoCaps = mockk<android.media.MediaCodecInfo.VideoCapabilities>()
            every { videoCaps.isSizeSupported(any(), any()) } returns true
            every { videoCaps.areSizeAndRateSupported(any(), any(), any()) } returns true
            every { videoCaps.getWidthAlignment() } returns 2
            every { videoCaps.getHeightAlignment() } returns 2

            val capabilities = mockk<CodecCapabilities>(relaxed = true)
            every { capabilities.getVideoCapabilities() } returns videoCaps
            
            // Set the profileLevels field of CodecCapabilities using reflection
            val field = CodecCapabilities::class.java.getField("profileLevels")
            field.set(capabilities, mockProfileLevels)

            // Instantiate a real Media3 MediaCodecInfo (not spyk)
            val codecInfo = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
                /* name= */ "test-h264-decoder",
                /* mimeType= */ "video/avc",
                /* codecMimeType= */ "video/avc",
                /* capabilities= */ capabilities,
                /* hardwareAccelerated= */ true,
                /* softwareOnly= */ false,
                /* vendor= */ true,
                /* forceDisableAdaptive= */ false,
                /* forceSecure= */ false
            )

            for (format in formats) {
                println("Evaluating format: ${format.id}, codecs: ${format.codecs}")
                
                try {
                    // Check format support using ExoPlayer's capability check logic
                    val isSupported = codecInfo.isFormatSupported(format)
                    val isFunctionallySupported = codecInfo.isFormatFunctionallySupported(format)
                    
                    println("Format ${format.id} (Size: ${format.width}x${format.height}):")
                    println("  - isFormatSupported (checkPerformanceCapabilities = true): $isSupported")
                    println("  - isFormatFunctionallySupported (checkPerformanceCapabilities = false): $isFunctionallySupported")
                } catch (e: Exception) {
                    println("  - capability check failed: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    @Test
    fun testNuvioMediaCodecVideoRendererSupport() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<Throwable>()) } returns 0
        every { android.util.Log.w(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val seq = firstArg<CharSequence?>()
            seq == null || seq.isEmpty()
        }

        fun createAndroidPair(first: Int, second: Int): android.util.Pair<Int, Int> {
            val pair = android.util.Pair(first, second)
            try {
                val firstField = android.util.Pair::class.java.getField("first")
                firstField.isAccessible = true
                firstField.set(pair, first)

                val secondField = android.util.Pair::class.java.getField("second")
                secondField.isAccessible = true
                secondField.set(pair, second)
            } catch (e: Exception) {
                println("Failed to set fields on Pair: ${e.message}")
            }
            return pair
        }

        mockkStatic(androidx.media3.exoplayer.mediacodec.MediaCodecUtil::class)
        every { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(any()) } answers {
            val format = firstArg<Format>()
            val codecs = format.codecs ?: ""
            val profileLevel = when {
                codecs.contains("640028") -> 100 to 40  // High Profile @ Level 4.0
                else -> null
            }
            if (profileLevel != null) {
                createAndroidPair(profileLevel.first, profileLevel.second)
            } else {
                null
            }
        }

        // 1080p format demanding High Profile L4.0 (avc1.640028)
        val format1080p = Format.Builder()
            .setId("1080p")
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(30.0f)
            .build()

        // High Profile Level 3.1 Decoder capability: High Profile = 100, Level = 31
        val profileLevels = arrayOf(
            androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel(100, 31)
        )

        val videoCaps = mockk<android.media.MediaCodecInfo.VideoCapabilities>()
        every { videoCaps.isSizeSupported(any(), any()) } returns true
        every { videoCaps.areSizeAndRateSupported(any(), any(), any()) } returns true
        every { videoCaps.getWidthAlignment() } returns 2
        every { videoCaps.getHeightAlignment() } returns 2

        val capabilities = mockk<CodecCapabilities>(relaxed = true)
        every { capabilities.getVideoCapabilities() } returns videoCaps
        val field = CodecCapabilities::class.java.getField("profileLevels")
        field.set(capabilities, profileLevels)

        val codecInfo = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
            "test-h264-decoder",
            "video/avc",
            "video/avc",
            capabilities,
            true,
            false,
            true,
            false,
            false
        )

        val mediaCodecSelector = mockk<MediaCodecSelector>()
        every { mediaCodecSelector.getDecoderInfos(any(), any(), any()) } returns listOf(codecInfo)

        // Statically mock MediaCodecUtil.getDecoderInfosSoftMatch to return our codecInfo list
        every {
            androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfosSoftMatch(any(), any(), any(), any())
        } returns listOf(codecInfo)

        val context = mockk<Context>(relaxed = true)
        val displayManager = mockk<android.hardware.display.DisplayManager>(relaxed = true)
        every { context.getSystemService(Context.DISPLAY_SERVICE) } returns displayManager
        every { context.applicationContext } returns context
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)

        val renderer = NuvioMediaCodecVideoRenderer(builder)

        val rendererCapabilities = renderer.supportsFormat(mediaCodecSelector, format1080p)
        val formatSupport = RendererCapabilities.getFormatSupport(rendererCapabilities)

        println("--- NuvioMediaCodecVideoRenderer Unit Test ---")
        println("Format Support for 1080p High Profile @ Level 4.0 stream on a Level 3.1 Decoder:")
        println("Original ExoPlayer evaluation: FORMAT_EXCEEDS_CAPABILITIES")
        println("NuvioMediaCodecVideoRenderer evaluation: $formatSupport (C.FORMAT_HANDLED = ${C.FORMAT_HANDLED})")

        assertEquals(C.FORMAT_HANDLED, formatSupport)
    }
}
