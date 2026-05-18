package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamCodecFilter
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamFeatureFilter
import com.nuvio.tv.domain.model.DebridStreamLanguage
import com.nuvio.tv.domain.model.DebridStreamMinimumQuality
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamSortCriterion
import com.nuvio.tv.domain.model.DebridStreamSortDirection
import com.nuvio.tv.domain.model.DebridStreamSortKey
import com.nuvio.tv.domain.model.DebridStreamSortMode
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDebridStreamFilterTest {
    @Test
    fun `keeps only cached supported debrid streams and labels source as instant`() {
        val cachedTorbox = stream(
            name = "Direct 1080p",
            resolve = resolve(type = "debrid", service = "torbox", isCached = true)
        )
        val uncachedTorbox = stream(
            resolve = resolve(type = "debrid", service = "torbox", isCached = false)
        )
        val genericTorrent = stream(
            resolve = resolve(type = "torrent", service = null, isCached = null)
        )
        val unsupportedDebrid = stream(
            resolve = resolve(type = "debrid", service = "futurebox", isCached = true)
        )

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(cachedTorbox, uncachedTorbox, genericTorrent, unsupportedDebrid)
        )

        assertEquals(1, result.size)
        assertEquals("Direct 1080p", result.single().name)
        assertEquals("Torbox Instant", result.single().addonName)
        assertTrue(result.single().isDirectDebrid())
        assertFalse(result.single().isTorrent())
    }

    @Test
    fun `uses provider instant name when source stream has no name`() {
        val result = DirectDebridStreamFilter.filterInstant(
            listOf(stream(name = null, resolve = resolve(type = "debrid", service = "realdebrid", isCached = true)))
        )

        assertEquals("Real-Debrid Instant", result.single().name)
    }

    @Test
    fun `limits and sorts streams by quality and size`() {
        val streams = listOf(
            stream(resolve = resolve(resolution = "1080p", size = 20)),
            stream(resolve = resolve(resolution = "2160p", size = 10)),
            stream(resolve = resolve(resolution = "2160p", size = 30)),
            stream(resolve = resolve(resolution = "720p", size = 40))
        )

        val result = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamMaxResults = 2,
                streamSortMode = DebridStreamSortMode.QUALITY_DESC
            )
        )

        assertEquals(listOf(30L, 10L), result.map { it.clientResolve?.stream?.raw?.size })
    }

    @Test
    fun `filters minimum quality dv hdr and codec`() {
        val hdrHevc = stream(
            resolve = resolve(
                resolution = "2160p",
                hdr = listOf("HDR10"),
                codec = "HEVC",
                size = 10
            )
        )
        val dvHevc = stream(
            resolve = resolve(
                resolution = "2160p",
                hdr = listOf("DV", "HDR10"),
                codec = "HEVC",
                size = 20
            )
        )
        val sdrAvc = stream(
            resolve = resolve(
                resolution = "1080p",
                hdr = emptyList(),
                codec = "AVC",
                size = 30
            )
        )
        val hdHevc = stream(
            resolve = resolve(
                resolution = "720p",
                hdr = emptyList(),
                codec = "HEVC",
                size = 40
            )
        )

        val noDvHdrHevc4k = DirectDebridStreamFilter.filterInstant(
            listOf(hdrHevc, dvHevc, sdrAvc, hdHevc),
            DebridSettings(
                streamMinimumQuality = DebridStreamMinimumQuality.P2160,
                streamDolbyVisionFilter = DebridStreamFeatureFilter.EXCLUDE,
                streamHdrFilter = DebridStreamFeatureFilter.ONLY,
                streamCodecFilter = DebridStreamCodecFilter.HEVC
            )
        )

        assertEquals(listOf(10L), noDvHdrHevc4k.map { it.clientResolve?.stream?.raw?.size })

        val dvOnly = DirectDebridStreamFilter.filterInstant(
            listOf(hdrHevc, dvHevc, sdrAvc, hdHevc),
            DebridSettings(streamDolbyVisionFilter = DebridStreamFeatureFilter.ONLY)
        )

        assertEquals(listOf(20L), dvOnly.map { it.clientResolve?.stream?.raw?.size })
    }

    @Test
    fun `filters provided torbox response through dto mapper path`() {
        val response = Moshi.Builder()
            .build()
            .adapter(StreamResponseDto::class.java)
            .fromJson(torboxMegamindResponse)
            ?: error("response not parsed")
        val streams = response.streams.orEmpty()
            .map { it.toDomain(DirectDebridStreamFilter.FALLBACK_SOURCE_NAME, null) }

        assertEquals(7, streams.size)
        assertEquals(7, DirectDebridStreamFilter.filterInstant(streams, DebridSettings()).size)

        val sizeDesc = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamMaxResults = 3,
                streamSortMode = DebridStreamSortMode.SIZE_DESC
            )
        )

        assertEquals(listOf(24_201_782_274L, 22_719_933_168L, 4_571_523_542L), sizeDesc.map { it.streamSize() })

        val qualityTopTwo = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamMaxResults = 2,
                streamSortMode = DebridStreamSortMode.QUALITY_DESC
            )
        )

        assertEquals(listOf("2160p", "1080p"), qualityTopTwo.map { it.clientResolve?.stream?.raw?.parsed?.resolution })
        assertEquals(4_571_523_542L, qualityTopTwo.first().streamSize())

        val dvOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamDolbyVisionFilter = DebridStreamFeatureFilter.ONLY)
        )

        assertEquals(listOf(4_571_523_542L), dvOnly.map { it.streamSize() })

        val noDv = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamDolbyVisionFilter = DebridStreamFeatureFilter.EXCLUDE)
        )

        assertEquals(6, noDv.size)
        assertTrue(noDv.none { it.description.orEmpty().contains("DV", ignoreCase = true) })

        val hdrOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamHdrFilter = DebridStreamFeatureFilter.ONLY)
        )

        assertEquals(listOf(4_571_523_542L), hdrOnly.map { it.streamSize() })

        val noHdr = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamHdrFilter = DebridStreamFeatureFilter.EXCLUDE)
        )

        assertEquals(6, noHdr.size)

        val hevcOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamCodecFilter = DebridStreamCodecFilter.HEVC)
        )

        assertEquals(listOf(4_571_523_542L, 3_859_136_613L, 2_946_516_232L), hevcOnly.map { it.streamSize() })

        val avcOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamCodecFilter = DebridStreamCodecFilter.H264)
        )

        assertEquals(4, avcOnly.size)

        val minimum1080 = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamMinimumQuality = DebridStreamMinimumQuality.P1080)
        )

        assertEquals(6, minimum1080.size)

        val minimum2160 = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamMinimumQuality = DebridStreamMinimumQuality.P2160)
        )

        assertEquals(listOf(4_571_523_542L), minimum2160.map { it.streamSize() })
    }

    @Test
    fun `applies aio style stream preferences`() {
        val remuxAtmos = stream(
            resolve = resolve(
                resolution = "2160p",
                quality = "BluRay REMUX",
                hdr = listOf("HDR10"),
                codec = "HEVC",
                audio = listOf("Atmos", "TrueHD"),
                channels = listOf("7.1"),
                languages = listOf("en"),
                group = "GOOD",
                size = 40_000_000_000
            )
        )
        val webAac = stream(
            resolve = resolve(
                resolution = "2160p",
                quality = "WEB-DL",
                codec = "AVC",
                audio = listOf("AAC"),
                channels = listOf("2.0"),
                languages = listOf("en"),
                group = "NOPE",
                size = 4_000_000_000
            )
        )
        val blurayDts = stream(
            resolve = resolve(
                resolution = "1080p",
                quality = "BluRay",
                codec = "AVC",
                audio = listOf("DTS"),
                channels = listOf("5.1"),
                languages = listOf("hi"),
                group = "GOOD",
                size = 12_000_000_000
            )
        )
        val preferences = DebridStreamPreferences(
            maxResults = 2,
            maxPerResolution = 1,
            sizeMinGb = 5,
            requiredResolutions = listOf(DebridStreamResolution.P2160, DebridStreamResolution.P1080),
            excludedQualities = listOf(DebridStreamQuality.WEB_DL),
            requiredAudioChannels = listOf(DebridStreamAudioChannel.CH_7_1, DebridStreamAudioChannel.CH_5_1),
            excludedEncodes = listOf(DebridStreamEncode.UNKNOWN),
            excludedLanguages = listOf(DebridStreamLanguage.IT),
            requiredReleaseGroups = listOf("GOOD"),
            sortCriteria = listOf(
                DebridStreamSortCriterion(DebridStreamSortKey.AUDIO_TAG, DebridStreamSortDirection.DESC),
                DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.ASC)
            )
        )

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(webAac, blurayDts, remuxAtmos),
            DebridSettings(streamPreferences = preferences)
        )

        assertEquals(listOf(40_000_000_000L, 12_000_000_000L), result.map { it.streamSize() })

        val noHdr = DirectDebridStreamFilter.filterInstant(
            listOf(webAac, blurayDts, remuxAtmos),
            DebridSettings(
                streamPreferences = DebridStreamPreferences(
                    requiredVisualTags = listOf(DebridStreamVisualTag.HDR_ONLY),
                    requiredAudioTags = listOf(DebridStreamAudioTag.ATMOS)
                )
            )
        )

        assertEquals(listOf(40_000_000_000L), noHdr.map { it.streamSize() })
    }

    private fun Stream.streamSize(): Long? = clientResolve?.stream?.raw?.size ?: behaviorHints?.videoSize

    private fun stream(
        name: String? = "Stream",
        resolve: StreamClientResolve?
    ): Stream = Stream(
        name = name,
        title = "Title",
        description = "Description",
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Direct Debrid",
        addonLogo = null,
        clientResolve = resolve
    )

    private fun resolve(
        type: String? = "debrid",
        service: String? = "torbox",
        isCached: Boolean? = true,
        resolution: String? = null,
        quality: String? = null,
        hdr: List<String> = emptyList(),
        codec: String? = null,
        audio: List<String>? = null,
        channels: List<String>? = null,
        languages: List<String>? = null,
        group: String? = null,
        size: Long? = null
    ): StreamClientResolve = StreamClientResolve(
        type = type,
        infoHash = "abc${size ?: ""}",
        fileIdx = size?.toInt() ?: 1,
        magnetUri = "magnet:?xt=urn:btih:abc",
        sources = null,
        torrentName = "Torrent",
        filename = "video ${resolution.orEmpty()} ${quality.orEmpty()} ${codec.orEmpty()}.mkv",
        mediaType = "movie",
        mediaId = "tt1",
        mediaOnlyId = "tt1",
        title = "Title",
        season = null,
        episode = null,
        service = service,
        serviceIndex = 0,
        serviceExtension = null,
        isCached = isCached,
        stream = StreamClientResolveStream(
            raw = StreamClientResolveRaw(
                torrentName = "Torrent ${resolution.orEmpty()} ${quality.orEmpty()}",
                filename = "video ${resolution.orEmpty()} ${quality.orEmpty()} ${codec.orEmpty()}.mkv",
                size = size,
                folderSize = size,
                tracker = null,
                indexer = null,
                network = null,
                parsed = StreamClientResolveParsed(
                    rawTitle = null,
                    parsedTitle = null,
                    year = null,
                    resolution = resolution,
                    seasons = null,
                    episodes = null,
                    quality = quality,
                    hdr = hdr,
                    codec = codec,
                    audio = audio,
                    channels = channels,
                    languages = languages,
                    group = group,
                    network = null,
                    edition = null,
                    duration = null,
                    bitDepth = null,
                    extended = null,
                    theatrical = null,
                    remastered = null,
                    unrated = null
                )
            )
        )
    )

    private val torboxMegamindResponse = """
        {
          "streams": [
            {
              "name": "TB 2160p cached",
              "description": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:c7a807331e1dcdd08e4527f6363cd1e8a109fe01&dn=Megamind%20%282010%29%20UpScaled%202160p%20H265%2010%20bit%20DV%20HDR10%2B%20ita%20eng%20AC3%205.1%20sub%20ita%20eng%20Licdom.mkv",
                "infoHash": "c7a807331e1dcdd08e4527f6363cd1e8a109fe01",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "title": "Megamind",
                "torrentName": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "2160p",
                      "codec": "hevc",
                      "audio": ["Dolby Digital"],
                      "channels": ["5.1"],
                      "hdr": ["DV", "HDR10+"],
                      "languages": ["multi", "en", "it"],
                      "year": 2010,
                      "raw_title": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "videoSize": 4571523542
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:6744954d89d8190643cb80653cf9b5c4b482409f&dn=Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "infoHash": "6744954d89d8190643cb80653cf9b5c4b482409f",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay REMUX",
                      "codec": "avc",
                      "audio": ["TrueHD"],
                      "channels": ["7.1"],
                      "group": "FiBERHD",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "videoSize": 24201782274
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:dbbbe7da95f9bac639af2d182746d33d0cea51fb&dn=Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "infoHash": "dbbbe7da95f9bac639af2d182746d33d0cea51fb",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay REMUX",
                      "codec": "avc",
                      "audio": ["TrueHD"],
                      "channels": ["7.1"],
                      "group": "NOGRP",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "videoSize": 22719933168
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:11802541a66f717fbc58dd72cd267d55a6df0477&dn=Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "infoHash": "11802541a66f717fbc58dd72cd267d55a6df0477",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "WEB-DL",
                      "codec": "avc",
                      "audio": ["AAC"],
                      "channels": ["2.0"],
                      "group": "PiRaTeS",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "videoSize": 3079386380
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:3fa25f80fb3eddf076c9002cc16c2a9801233e4b&dn=Megamind%20%282010%29%201080p%2010bit%20Bluray%20x265%20HEVC%20%5BOrg%20DD%205.1%20Hindi%20%2B%20DD%205.1%20English%5D%20MSubs%20~%20TombDoc.mkv",
                "infoHash": "3fa25f80fb3eddf076c9002cc16c2a9801233e4b",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "title": "Megamind",
                "torrentName": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay",
                      "codec": "hevc",
                      "audio": ["Dolby Digital"],
                      "channels": ["5.1"],
                      "languages": ["multi", "en", "hi"],
                      "year": 2010,
                      "raw_title": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "videoSize": 3859136613
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:e4d12499cf481aeee9007b806c609917d3799e43&dn=Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "infoHash": "e4d12499cf481aeee9007b806c609917d3799e43",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay",
                      "codec": "hevc",
                      "audio": ["Dolby Digital Plus"],
                      "channels": ["7.1"],
                      "group": "EDGE2020",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "videoSize": 2946516232
              }
            },
            {
              "name": "TB 720p cached",
              "description": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:52c6341e0e4cc4eb0e23bd16f93a00dcad93490c&dn=Megamind%202010%20x264%20720p%20Esub%20BluRay%20Dual%20Audio%20English%20Hindi%20GOPI%20SAHI.mkv",
                "infoHash": "52c6341e0e4cc4eb0e23bd16f93a00dcad93490c",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "title": "Megamind",
                "torrentName": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "720p",
                      "quality": "BluRay",
                      "codec": "avc",
                      "languages": ["multi", "en", "hi"],
                      "year": 2010,
                      "raw_title": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "videoSize": 796639202
              }
            }
          ],
          "resolveMode": "client"
        }
    """.trimIndent()
}
