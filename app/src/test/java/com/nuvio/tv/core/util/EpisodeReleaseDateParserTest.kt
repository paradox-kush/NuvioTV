package com.nuvio.tv.core.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeReleaseDateParserTest {
    @Test
    fun `utc midnight timestamp preserves encoded episode date`() {
        assertEquals(
            LocalDate.of(2026, 7, 16),
            parseEpisodeCalendarDate("2026-07-16T00:00:00.000Z")
        )
    }

    @Test
    fun `offset timestamp preserves source calendar date`() {
        assertEquals(
            LocalDate.of(2026, 7, 16),
            parseEpisodeCalendarDate("2026-07-16T23:30:00-04:00")
        )
    }

    @Test
    fun `plain date and invalid value are handled`() {
        assertEquals(LocalDate.of(2026, 7, 16), parseEpisodeCalendarDate("2026-07-16"))
        assertNull(parseEpisodeCalendarDate("not-a-date"))
    }
}
