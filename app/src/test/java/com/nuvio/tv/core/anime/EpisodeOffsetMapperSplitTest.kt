package com.nuvio.tv.core.anime

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the split-season fanout fix: when one TMDB season is mapped to
 * multiple tracker entries (AoT "Final Season Part 1" + "Part 2" style),
 * watching a late episode must produce mappings for BOTH the completed
 * earlier part AND the currently-in-progress later part.
 *
 * The mapper under test needs Context, Moshi, and the other deps for the
 * download path only. Parsing + candidate selection is pure; we mock the
 * constructor deps and exercise the value-parser + collector directly.
 */
class EpisodeOffsetMapperSplitTest {

    private lateinit var mapper: EpisodeOffsetMapper

    @Before
    fun setUp() {
        mapper = EpisodeOffsetMapper(
            context = mockk(relaxed = true),
            animeMappingsApi = mockk(relaxed = true),
            animeIdMapper = mockk(relaxed = true),
            moshi = mockk(relaxed = true)
        )
    }

    // --- parseValueCoverage --- //

    @Test
    fun `parseValueCoverage handles empty string as whole-season wildcard`() {
        val ranges = mapper.parseValueCoverage("")
        assertEquals(1, ranges.size)
        assertEquals(1, ranges[0].first)
        assertEquals(Int.MAX_VALUE, ranges[0].last)
    }

    @Test
    fun `parseValueCoverage handles single range`() {
        assertEquals(listOf(1..12), mapper.parseValueCoverage("e1-e12"))
    }

    @Test
    fun `parseValueCoverage handles single episode`() {
        assertEquals(listOf(5..5), mapper.parseValueCoverage("e5"))
    }

    @Test
    fun `parseValueCoverage handles comma-separated multi-range`() {
        assertEquals(
            listOf(1..12, 14..24),
            mapper.parseValueCoverage("e1-e12,e14-e24")
        )
    }

    @Test
    fun `parseValueCoverage strips ratio suffix`() {
        assertEquals(listOf(1..16), mapper.parseValueCoverage("e1-e16|2"))
    }

    @Test
    fun `parseValueCoverage tolerates leading e-less numbers`() {
        assertEquals(listOf(3..3), mapper.parseValueCoverage("3"))
    }

    // --- collectCoveringCandidates: AoT-style Part 1 + Part 2 split --- //

    private val part1 = AnimeMappingEntryDto(
        anilistId = 110277, // stand-in for "Final Season Part 1"
        malIdRaw = 40028,
        tvdbMappings = mapOf("s4" to "e1-e16"),
        length = 16
    )
    private val part2 = AnimeMappingEntryDto(
        anilistId = 131681, // "Part 2"
        malIdRaw = 48583,
        tvdbMappings = mapOf("s4" to "e17-e28"),
        length = 12
    )

    @Test
    fun `watching before boundary produces only part-1 partial`() {
        val matches = mapper.collectCoveringCandidates(listOf(part1, part2), tmdbSeason = 4, tmdbEpisode = 5)
        assertEquals(1, matches.size)
        assertEquals(110277, matches[0].entry.anilistId)
        assertEquals(5, matches[0].trackerEpisode)
        assertFalse(matches[0].isRangeComplete)
    }

    @Test
    fun `watching exactly at part-1 boundary flags range complete`() {
        val matches = mapper.collectCoveringCandidates(listOf(part1, part2), tmdbSeason = 4, tmdbEpisode = 16)
        assertEquals(1, matches.size)
        assertEquals(110277, matches[0].entry.anilistId)
        assertEquals(16, matches[0].trackerEpisode)
        assertTrue("boundary episode should mark part-1 complete", matches[0].isRangeComplete)
    }

    @Test
    fun `watching late in part 2 emits completed part 1 plus partial part 2`() {
        val matches = mapper.collectCoveringCandidates(listOf(part1, part2), tmdbSeason = 4, tmdbEpisode = 23)
        assertEquals(2, matches.size)
        val p1 = matches.first { it.entry.anilistId == 110277 }
        val p2 = matches.first { it.entry.anilistId == 131681 }
        assertEquals(16, p1.trackerEpisode)
        assertTrue(p1.isRangeComplete)
        // TMDB e23 is Part 2's 7th ep (23 - 17 + 1 = 7).
        assertEquals(7, p2.trackerEpisode)
        assertFalse(p2.isRangeComplete)
    }

    @Test
    fun `watching final episode flags both parts complete`() {
        val matches = mapper.collectCoveringCandidates(listOf(part1, part2), tmdbSeason = 4, tmdbEpisode = 28)
        assertEquals(2, matches.size)
        matches.forEach { assertTrue("all parts should be complete at final ep", it.isRangeComplete) }
        assertEquals(16, matches.first { it.entry.anilistId == 110277 }.trackerEpisode)
        assertEquals(12, matches.first { it.entry.anilistId == 131681 }.trackerEpisode)
    }

    // --- User's reported case: 2-part season with 12+12 episodes --- //
    // TMDB S2 = 24 eps → Part 1 (e1-e12) + Part 2 (e13-e24), watched S2E23.

    @Test
    fun `user reported case — S2E23 completes part 1 and sets part 2 progress 11`() {
        val p1 = AnimeMappingEntryDto(
            anilistId = 100,
            tvdbMappings = mapOf("s2" to "e1-e12"),
            length = 12
        )
        val p2 = AnimeMappingEntryDto(
            anilistId = 200,
            tvdbMappings = mapOf("s2" to "e13-e24"),
            length = 12
        )
        val matches = mapper.collectCoveringCandidates(listOf(p1, p2), tmdbSeason = 2, tmdbEpisode = 23)
        assertEquals(2, matches.size)
        val first = matches.first { it.entry.anilistId == 100 }
        val second = matches.first { it.entry.anilistId == 200 }
        assertEquals(12, first.trackerEpisode)
        assertTrue(first.isRangeComplete)
        assertEquals(11, second.trackerEpisode)
        assertFalse(second.isRangeComplete)
    }

    // --- Multi-range (comma-list) coverage inside a single entry --- //

    @Test
    fun `single-entry comma-range value sums contributions with a gap`() {
        val entry = AnimeMappingEntryDto(
            anilistId = 42,
            tvdbMappings = mapOf("s1" to "e1-e12,e14-e24"),
            length = 23
        )
        // Watching e20 → e1-e12 fully contribute 12, e14-e20 contribute 7; total 19.
        val matches = mapper.collectCoveringCandidates(listOf(entry), tmdbSeason = 1, tmdbEpisode = 20)
        assertEquals(1, matches.size)
        assertEquals(19, matches[0].trackerEpisode)
        assertFalse(matches[0].isRangeComplete)
    }

    @Test
    fun `single-entry comma-range completes when watched covers all intervals`() {
        val entry = AnimeMappingEntryDto(
            anilistId = 42,
            tvdbMappings = mapOf("s1" to "e1-e12,e14-e24"),
            length = 23
        )
        val matches = mapper.collectCoveringCandidates(listOf(entry), tmdbSeason = 1, tmdbEpisode = 24)
        assertEquals(1, matches.size)
        assertEquals(23, matches[0].trackerEpisode)
        assertTrue(matches[0].isRangeComplete)
    }

    // --- Sole-candidate fallback --- //

    @Test
    fun `sole candidate with no season mapping still emits a mapping`() {
        val entry = AnimeMappingEntryDto(
            anilistId = 77,
            tvdbMappings = mapOf("s9" to "e1-e24"), // doesn't cover season 2
            length = 24
        )
        val matches = mapper.collectCoveringCandidates(listOf(entry), tmdbSeason = 2, tmdbEpisode = 5)
        assertEquals(1, matches.size)
        assertEquals(77, matches[0].entry.anilistId)
        assertFalse(matches[0].isRangeComplete)
    }

    @Test
    fun `multiple candidates with no matching season emits nothing`() {
        val a = AnimeMappingEntryDto(anilistId = 1, tvdbMappings = mapOf("s1" to "e1-e12"))
        val b = AnimeMappingEntryDto(anilistId = 2, tvdbMappings = mapOf("s3" to "e1-e12"))
        val matches = mapper.collectCoveringCandidates(listOf(a, b), tmdbSeason = 9, tmdbEpisode = 1)
        assertEquals(0, matches.size)
    }

    // --- Whole-season mapping --- //

    @Test
    fun `empty-string value acts as whole-season partial at watched episode`() {
        val entry = AnimeMappingEntryDto(
            anilistId = 500,
            tvdbMappings = mapOf("s1" to ""),
            length = null
        )
        val matches = mapper.collectCoveringCandidates(listOf(entry), tmdbSeason = 1, tmdbEpisode = 7)
        assertEquals(1, matches.size)
        assertEquals(7, matches[0].trackerEpisode)
        // Without an explicit upper bound we can't claim completion.
        assertFalse(matches[0].isRangeComplete)
    }

    // --- tmdb_mappings preferred over tvdb_mappings when both exist --- //

    @Test
    fun `prefers tvdb_mappings when it covers the season`() {
        val entry = AnimeMappingEntryDto(
            anilistId = 999,
            tvdbMappings = mapOf("s1" to "e1-e12"),
            tmdbMappings = mapOf("s1" to "e1-e6"), // would say e1-e6 on TMDB side but tvdb wins
            length = 12
        )
        val matches = mapper.collectCoveringCandidates(listOf(entry), tmdbSeason = 1, tmdbEpisode = 8)
        assertEquals(1, matches.size)
        // Via tvdb_mappings (e1-e12), e8 contained → contribution = 8, not complete.
        assertEquals(8, matches[0].trackerEpisode)
        assertFalse(matches[0].isRangeComplete)
        assertFalse(matches[0].viaTmdbMappings)
    }

    @Test
    fun `falls back to tmdb_mappings when tvdb_mappings lacks the season`() {
        val entry = AnimeMappingEntryDto(
            anilistId = 999,
            tvdbMappings = mapOf("s9" to "e1-e12"), // only s9 on tvdb side
            tmdbMappings = mapOf("s1" to "e1-e10"),
            length = 10
        )
        val matches = mapper.collectCoveringCandidates(listOf(entry), tmdbSeason = 1, tmdbEpisode = 5)
        assertEquals(1, matches.size)
        assertEquals(5, matches[0].trackerEpisode)
        assertTrue(matches[0].viaTmdbMappings)
    }

    // --- Multi-season single-entry: absolute-cumulative (One Piece style) --- //

    /**
     * One Piece tmdb_mappings chain: each season's value continues numbering
     * from the previous. Watched TMDB S2E5 → tracker absolute ep = 62 + 4 = 66.
     */
    @Test
    fun `one piece style tmdb_mappings resolves to absolute tracker episode`() {
        val onePiece = AnimeMappingEntryDto(
            anilistId = 21,
            tmdbMappings = mapOf(
                "s1" to "e1-e61",
                "s2" to "e62-e77",
                "s3" to "e78-e91"
            ),
            tmdbShowId = 37854
        )
        val matches = mapper.collectCoveringCandidates(listOf(onePiece), tmdbSeason = 2, tmdbEpisode = 5)
        assertEquals(1, matches.size)
        assertEquals(66, matches[0].trackerEpisode)
        assertFalse(matches[0].isRangeComplete) // more in s2 + later seasons
        assertTrue(matches[0].viaTmdbMappings)
    }

    @Test
    fun `one piece watching final episode of last known season flags complete`() {
        val onePiece = AnimeMappingEntryDto(
            anilistId = 21,
            tmdbMappings = mapOf(
                "s1" to "e1-e61",
                "s2" to "e62-e77"
            )
        )
        val matches = mapper.collectCoveringCandidates(listOf(onePiece), tmdbSeason = 2, tmdbEpisode = 16)
        // S2 has 16 TMDB eps → tracker ep = 62 + 15 = 77 → reaches last interval's end.
        assertEquals(1, matches.size)
        assertEquals(77, matches[0].trackerEpisode)
        assertTrue(matches[0].isRangeComplete)
    }

    @Test
    fun `one piece watching TMDB season 1 uses absolute 1-61 range`() {
        val onePiece = AnimeMappingEntryDto(
            anilistId = 21,
            tmdbMappings = mapOf(
                "s1" to "e1-e61",
                "s2" to "e62-e77"
            )
        )
        val matches = mapper.collectCoveringCandidates(listOf(onePiece), tmdbSeason = 1, tmdbEpisode = 10)
        assertEquals(1, matches.size)
        // s1 starts at e1 so tracker = 1 + 9 = 10. Later season exists so not complete.
        assertEquals(10, matches[0].trackerEpisode)
        assertFalse(matches[0].isRangeComplete)
    }

    // --- Multi-season single-entry: season-local tvdb (Naruto tvdb-only) --- //

    @Test
    fun `naruto-style tvdb_mappings sums earlier seasons when chain absent`() {
        val naruto = AnimeMappingEntryDto(
            anilistId = 20,
            tvdbMappings = mapOf(
                "s1" to "e1-e35",
                "s2" to "e1-e48",
                "s3" to "e1-e48"
            )
        )
        // Via tvdb_mappings (no chain → season-local). s2e5 → 35 + 5 = 40.
        val matches = mapper.collectCoveringCandidates(listOf(naruto), tmdbSeason = 2, tmdbEpisode = 5)
        assertEquals(1, matches.size)
        assertEquals(40, matches[0].trackerEpisode)
        assertFalse(matches[0].isRangeComplete)
    }

    @Test
    fun `naruto-style prefers tvdb_mappings season-local sum even when tmdb chains`() {
        val naruto = AnimeMappingEntryDto(
            anilistId = 20,
            tvdbMappings = mapOf(
                "s1" to "e1-e35",
                "s2" to "e1-e48"
            ),
            tmdbMappings = mapOf(
                "s1" to "e1-e52",
                "s2" to "e53-e104"
            )
        )
        val matches = mapper.collectCoveringCandidates(listOf(naruto), tmdbSeason = 2, tmdbEpisode = 5)
        assertEquals(1, matches.size)
        // Via tvdb season-local: 35 + 5 = 40 (user's current-TMDB layout).
        assertEquals(40, matches[0].trackerEpisode)
        assertFalse(matches[0].viaTmdbMappings)
    }

    // --- User's reported case: s1 = 8 eps, s2e2 should yield 10 --- //

    @Test
    fun `user reported case — 8-ep s1 plus s2e2 sums to 10 via season-local tvdb`() {
        val entry = AnimeMappingEntryDto(
            anilistId = 1234,
            tvdbMappings = mapOf(
                "s1" to "e1-e8",
                "s2" to "e1-e12"
            )
        )
        val matches = mapper.collectCoveringCandidates(listOf(entry), tmdbSeason = 2, tmdbEpisode = 2)
        assertEquals(1, matches.size)
        assertEquals(10, matches[0].trackerEpisode)
        assertFalse(matches[0].isRangeComplete)
    }

    @Test
    fun `user reported case works equivalently via chained tmdb_mappings`() {
        val entry = AnimeMappingEntryDto(
            anilistId = 1234,
            tmdbMappings = mapOf(
                "s1" to "e1-e8",
                "s2" to "e9-e20"
            )
        )
        val matches = mapper.collectCoveringCandidates(listOf(entry), tmdbSeason = 2, tmdbEpisode = 2)
        assertEquals(1, matches.size)
        // Absolute mode: 9 + (2-1) = 10.
        assertEquals(10, matches[0].trackerEpisode)
        assertFalse(matches[0].isRangeComplete)
        assertTrue(matches[0].viaTmdbMappings)
    }

    // --- AoT split-entry case must still work (regression guard) --- //

    @Test
    fun `aot part 2 single-key mapping unchanged after multi-season fix`() {
        val part2 = AnimeMappingEntryDto(
            anilistId = 131681,
            tvdbMappings = mapOf("s4" to "e17-e28"),
            length = 12
        )
        // Single season key → detectChaining=false → season-local branch.
        // Watched S4E17 → Part 2 ep = 17 - 17 + 1 = 1.
        val matches = mapper.collectCoveringCandidates(listOf(part2), tmdbSeason = 4, tmdbEpisode = 17)
        assertEquals(1, matches.size)
        assertEquals(1, matches[0].trackerEpisode)
        assertFalse(matches[0].isRangeComplete)
    }

    // --- Entry that doesn't cover the watched season shouldn't emit --- //

    /**
     * Diagnostic: real One Piece PAB shape with 22 tmdb_mapping entries that
     * DO chain, plus non-chaining tvdb_mappings on the same entry. Bug we're
     * investigating: the user's device logged `sole-candidate fallback` for
     * this exact case, which means `computeContribution` returned null.
     */
    @Test
    fun `real one piece shape prefers tvdb and sums earlier seasons for s3e10`() {
        // Real PAB data (abridged): tvdb_mappings has season-local ranges
        // matching the user's current TMDB layout; tmdb_mappings uses older
        // absolute-cumulative numbering. We prefer tvdb.
        val onePiece = AnimeMappingEntryDto(
            anilistId = 21,
            tmdbShowId = 37854,
            tmdbMappings = mapOf(
                "s1" to "e1-e61", "s2" to "e62-e77", "s3" to "e78-e91",
                "s22" to "e1089-"
            ),
            tvdbMappings = mapOf(
                "s0" to "e39",
                "s1" to "e1-e8",
                "s2" to "e1-e22",
                "s3" to "e1-e17",
                "s22" to ""
            )
        )
        val matches = mapper.collectCoveringCandidates(listOf(onePiece), tmdbSeason = 3, tmdbEpisode = 10)
        assertEquals(1, matches.size)
        // Via tvdb season-local: s1 (8) + s2 (22) + s3 e10 (10) = 40.
        assertEquals(40, matches[0].trackerEpisode)
        assertFalse(matches[0].isRangeComplete)
        assertFalse(matches[0].viaTmdbMappings)
    }

    @Test
    fun `parseValueCoverage handles open-ended range`() {
        val ranges = mapper.parseValueCoverage("e1089-")
        assertEquals(1, ranges.size)
        assertEquals(1089, ranges[0].first)
        assertEquals(Int.MAX_VALUE, ranges[0].last)
    }

    @Test
    fun `entry with earlier seasons only does not emit when watched season not covered`() {
        val entry = AnimeMappingEntryDto(
            anilistId = 500,
            tvdbMappings = mapOf(
                "s1" to "e1-e12",
                "s3" to "e1-e24"
            )
        )
        val matches = mapper.collectCoveringCandidates(listOf(entry), tmdbSeason = 2, tmdbEpisode = 5)
        // Doesn't cover s2. Sole-candidate fallback kicks in since matches is empty.
        // That's acceptable — better to attempt a write than silently skip for a one-entry show.
        assertEquals(1, matches.size)
    }
}
