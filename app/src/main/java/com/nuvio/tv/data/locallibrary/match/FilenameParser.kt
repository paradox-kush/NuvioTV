package com.nuvio.tv.data.locallibrary.match

import com.nuvio.tv.domain.model.ContentType

/**
 * Result of parsing a media filename into queryable fields. When [season] and
 * [episode] are both set, the file is treated as a TV episode; otherwise as a movie.
 */
data class ParsedFilename(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val contentType: ContentType = if (season != null && episode != null) ContentType.SERIES else ContentType.MOVIE,
    /** Title with no cleanup applied — useful for debugging or display. */
    val originalTitle: String = title
)

/**
 * Stateless parser that extracts title, year, and S/E hints from a filename.
 * Tuned for Plex/Jellyfin-style naming conventions:
 *
 *  Inception (2010) 1080p BluRay.mkv
 *  Inception.2010.1080p.BluRay.mkv
 *  Show.Name.S01E03.720p.WEBRip.mkv
 *  Show Name - 1x03 - Episode Title.mkv
 *  Show Name Season 01 Episode 03.mkv
 */
object FilenameParser {

    private val episodePatterns = listOf(
        // S01E02, S1E2
        Regex("""[Ss](\d{1,2})[._\-\s]?[Ee](\d{1,3})"""),
        // 1x02, 01x02
        Regex("""(?<![A-Za-z\d])(\d{1,2})[xX](\d{1,3})(?![A-Za-z\d])"""),
        // Season 01 Episode 02
        Regex("""(?i)season[._\-\s]*(\d{1,2})[._\-\s]+(?:episode|ep)[._\-\s]*(\d{1,3})"""),
        // [E02] (assume season 1)
        Regex("""[\[(](?:E|Ep|Episode)[._\-\s]?(\d{1,3})[\])]""", RegexOption.IGNORE_CASE)
    )

    private val yearPattern = Regex("""(?<![\dA-Za-z])((?:19|20)\d{2})(?![\dA-Za-z])""")

    /** Tokens that mark the end of the title — everything from here on is junk. */
    private val junkTokens = listOf(
        "2160p", "1080p", "720p", "480p", "360p",
        "4k", "uhd", "hdr10", "hdr", "dv", "dolby",
        "bluray", "blu-ray", "bdrip", "brrip", "webrip", "web-dl", "webdl", "web",
        "hdtv", "hdrip", "dvdrip", "dvdscr", "screener", "ts", "cam", "remux",
        "x264", "x265", "h264", "h265", "hevc", "av1",
        "ac3", "aac", "dts", "dts-hd", "atmos", "truehd", "ddp5", "5.1", "7.1",
        "internal", "proper", "repack", "extended", "uncut", "directors", "director's", "remastered",
        "amzn", "nf", "hulu", "dsnp", "atvp", "mzn", "stan",
        "yify", "yts", "rarbg", "ettv", "eztv", "tgx", "torrentgalaxy"
    )

    fun parse(fileName: String): ParsedFilename {
        // Strip extension
        val noExt = fileName.substringBeforeLast('.', fileName)
        // Strip trailing release-group tag like "-GROUP" at the end
        val noGroup = noExt.replace(Regex("""[-\s]+[A-Za-z0-9]+$"""), { match ->
            // Only strip if the tail looks like a release group (all caps or short)
            val tail = match.value.trimStart('-', ' ').trim()
            if (tail.length <= 12 && tail.all { it.isLetterOrDigit() } &&
                (tail.uppercase() == tail || tail.lowercase() == tail) &&
                tail.lowercase() !in setOf("part", "vol", "chapter", "the", "and")
            ) "" else match.value
        })

        // Find episode S/E in the original normalized string before lowercase truncation
        val normalized = noGroup.replace('_', ' ').replace('.', ' ').replace(Regex("""\s+"""), " ").trim()

        var season: Int? = null
        var episode: Int? = null
        var sePatternMatchStart = -1
        for ((index, regex) in episodePatterns.withIndex()) {
            val m = regex.find(normalized) ?: continue
            sePatternMatchStart = m.range.first
            if (index == 3) {
                season = 1
                episode = m.groupValues[1].toIntOrNull()
            } else {
                season = m.groupValues[1].toIntOrNull()
                episode = m.groupValues[2].toIntOrNull()
            }
            break
        }

        // Find year
        val yearMatch = yearPattern.find(normalized)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        // Title is whatever comes before the earliest of: episode marker, year, first junk token
        val titleEndCandidates = mutableListOf<Int>()
        if (sePatternMatchStart >= 0) titleEndCandidates += sePatternMatchStart
        if (yearMatch != null) titleEndCandidates += yearMatch.range.first
        val lowerNorm = normalized.lowercase()
        for (token in junkTokens) {
            val idx = lowerNorm.indexOf(" $token", ignoreCase = false)
            if (idx >= 0) titleEndCandidates += idx
        }
        val titleEnd = titleEndCandidates.filter { it > 0 }.minOrNull() ?: normalized.length
        var title = normalized.substring(0, titleEnd).trim()

        // Strip trailing punctuation / leftover separators / parens
        title = title.trim().trimEnd('-', ' ', '(', '[', ',', ':', '.')
        title = title.replace(Regex("""\s+"""), " ").trim()

        // Strip trailing year in parens that survived (e.g. "Inception (2010)")
        title = title.replace(Regex("""\s*[\[(]\s*(?:19|20)\d{2}\s*[\])]\s*$"""), "").trim()

        val type = if (season != null && episode != null) ContentType.SERIES else ContentType.MOVIE

        return ParsedFilename(
            title = title.ifBlank { noGroup.trim() },
            year = year,
            season = season,
            episode = episode,
            contentType = type,
            originalTitle = noGroup
        )
    }
}
