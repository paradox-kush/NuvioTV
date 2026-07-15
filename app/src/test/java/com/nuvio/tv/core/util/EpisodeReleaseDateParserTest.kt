package com.nuvio.tv.core.util

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeReleaseDateParserTest {
    private val eastern = ZoneId.of("America/Detroit")

    @Test
    fun `utc timestamp uses the viewers local episode date`() {
        assertEquals(
            LocalDate.of(2026, 7, 15),
            parseEpisodeReleaseLocalDate("2026-07-16T00:00:00.000Z", eastern)
        )
    }

    @Test
    fun `offset timestamp is converted to the viewers timezone`() {
        assertEquals(
            LocalDate.of(2026, 7, 15),
            parseEpisodeReleaseLocalDate("2026-07-16T01:00:00+02:00", eastern)
        )
    }

    @Test
    fun `plain date and invalid value are handled`() {
        assertEquals(LocalDate.of(2026, 7, 16), parseEpisodeReleaseLocalDate("2026-07-16", eastern))
        assertNull(parseEpisodeReleaseLocalDate("not-a-date", eastern))
    }

    @Test
    fun `precise addon timestamp wins over tmdb broadcaster date`() {
        assertEquals(
            "2026-07-15T15:00:00Z",
            selectEpisodeReleaseValue(
                addonReleased = "2026-07-15T15:00:00Z",
                tmdbAirDate = "2026-07-16",
                useTmdbReleaseDates = true
            )
        )
    }

    @Test
    fun `tmdb date remains the fallback for imprecise addon metadata`() {
        assertEquals(
            "2026-07-16",
            selectEpisodeReleaseValue(
                addonReleased = null,
                tmdbAirDate = "2026-07-16",
                useTmdbReleaseDates = true
            )
        )
        assertEquals(
            "2026-07-15",
            selectEpisodeReleaseValue(
                addonReleased = "2026-07-15",
                tmdbAirDate = "2026-07-16",
                useTmdbReleaseDates = false
            )
        )
    }
}
