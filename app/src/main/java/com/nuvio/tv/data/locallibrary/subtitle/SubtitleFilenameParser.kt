package com.nuvio.tv.data.locallibrary.subtitle

import androidx.media3.common.MimeTypes
import com.nuvio.tv.data.locallibrary.match.FilenameParser
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils
import java.util.Locale

/**
 * Pulls language / forced / SDH metadata out of sidecar subtitle filenames such as:
 *  - `Inception.srt`              → language null
 *  - `Inception.en.srt`           → language "en"
 *  - `Inception.eng.srt`          → language "en"
 *  - `Inception.English.srt`      → language "en"
 *  - `Inception.pt-BR.srt`        → language "pt-br"
 *  - `Inception.en.forced.srt`    → language "en", forced
 *  - `Inception.English.SDH.srt`  → language "en", sdh
 */
object SubtitleFilenameParser {
    val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    private val FORCED_FLAGS = setOf("forced", "foreign")
    private val SDH_FLAGS = setOf("sdh", "cc", "hi", "hearingimpaired", "hearing-impaired")

    data class ParsedInfo(
        val language: String?,
        val isForced: Boolean,
        val isSdh: Boolean,
        val displayName: String
    )

    fun isSubtitleFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return ext.isNotEmpty() && ext in SUBTITLE_EXTENSIONS
    }

    /** True when [subtitleFileName] looks like a sidecar for a video file named [videoBaseName].* */
    fun matchesVideo(subtitleFileName: String, videoBaseName: String): Boolean {
        if (!isSubtitleFile(subtitleFileName)) return false
        if (videoBaseName.isBlank()) return false
        if (matchesByPrefix(subtitleFileName, videoBaseName)) return true
        return matchesByParsedTitle(subtitleFileName, videoBaseName)
    }

    private fun matchesByPrefix(subtitleFileName: String, videoBaseName: String): Boolean {
        val subBase = subtitleFileName.substringBeforeLast('.')
        if (subBase.equals(videoBaseName, ignoreCase = true)) return true
        if (subBase.length <= videoBaseName.length) return false
        if (!subBase.regionMatches(0, videoBaseName, 0, videoBaseName.length, ignoreCase = true)) return false
        val sep = subBase[videoBaseName.length]
        return sep == '.' || sep == '_' || sep == '-' || sep == ' '
    }

    /**
     * Fallback that compares the parsed `(title, year, season, episode)` of both files.
     * Catches the common case where the video uses a release-tagged name
     * (`F1.The.Movie.2025.1080p.WEBRip.x264.AAC-[YTS.MX].mp4`) and the user-curated
     * SRT uses the clean form (`F1 The Movie (2025).srt`).
     */
    private fun matchesByParsedTitle(subtitleFileName: String, videoBaseName: String): Boolean {
        val videoParsed = FilenameParser.parse(videoBaseName)
        val subParsed = FilenameParser.parse(subtitleFileName)

        val videoTitle = normalizeTitle(videoParsed.title)
        val subTitle = normalizeTitle(subParsed.title)
        if (videoTitle.isBlank() || subTitle.isBlank()) return false
        if (videoTitle != subTitle) return false

        if (videoParsed.year != null && subParsed.year != null &&
            videoParsed.year != subParsed.year
        ) return false

        if (videoParsed.contentType == ContentType.SERIES &&
            subParsed.contentType == ContentType.SERIES
        ) {
            if (videoParsed.season != subParsed.season) return false
            if (videoParsed.episode != subParsed.episode) return false
        }

        return true
    }

    private fun normalizeTitle(title: String): String {
        return title
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "")
    }

    fun parse(subtitleFileName: String, videoBaseName: String): ParsedInfo {
        val tokens = extractTailTokens(subtitleFileName, videoBaseName)

        var isForced = false
        var isSdh = false
        val remaining = mutableListOf<String>()
        for (token in tokens) {
            val low = token.lowercase(Locale.ROOT)
            when (low) {
                in FORCED_FLAGS -> isForced = true
                in SDH_FLAGS -> isSdh = true
                else -> remaining += token
            }
        }

        val language = detectLanguage(remaining)
        val displayName = buildDisplayName(language, remaining, subtitleFileName, isForced, isSdh)
        return ParsedInfo(language = language, isForced = isForced, isSdh = isSdh, displayName = displayName)
    }

    /**
     * Pulls the "tail" tokens from a subtitle filename — the part that may hold
     * language / forced / SDH flags.
     *
     * - Strict prefix match (`Movie.mkv` + `Movie.en.srt`): tokens come from
     *   stripping the video basename, splitting on `. _ - space`.
     * - Fuzzy match (`Movie.2025.1080p.mp4` + `Movie (2025).en.srt`): we don't
     *   know the boundary, so we walk `.`-separated segments from the END and
     *   collect short, ASCII-ish tokens until we hit something that obviously
     *   belongs to the title (long token, contains digits, parens/brackets).
     */
    private fun extractTailTokens(subtitleFileName: String, videoBaseName: String): List<String> {
        val subBase = subtitleFileName.substringBeforeLast('.')

        if (subBase.equals(videoBaseName, ignoreCase = true)) return emptyList()
        if (subBase.length > videoBaseName.length &&
            subBase.regionMatches(0, videoBaseName, 0, videoBaseName.length, ignoreCase = true)
        ) {
            val sep = subBase[videoBaseName.length]
            if (sep == '.' || sep == '_' || sep == '-' || sep == ' ') {
                return subBase.substring(videoBaseName.length + 1)
                    .split('.', '_', '-', ' ')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
        }

        val segments = subBase.split('.').map { it.trim() }
        val tail = mutableListOf<String>()
        for (i in segments.indices.reversed()) {
            val seg = segments[i]
            if (seg.isBlank()) continue
            if (looksLikeTailToken(seg)) {
                tail.add(0, seg)
            } else {
                break
            }
        }
        return tail
    }

    private fun looksLikeTailToken(segment: String): Boolean {
        if (segment.length > 20) return false
        if (segment.any { !it.isLetterOrDigit() && it != '-' }) return false
        if (segment.any { it.isDigit() }) return false
        return segment.isNotBlank()
    }

    fun mimeTypeFor(extension: String): String {
        return when (extension.lowercase(Locale.ROOT)) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    /**
     * Try the trailing tokens first (most common: `Movie.2023.1080p.WEB-DL.en.srt`)
     * and walk backward. Also try joined two-token combos for locale-like codes that
     * may have been split (e.g. ["pt", "BR"] → "pt-BR").
     */
    private fun detectLanguage(tokens: List<String>): String? {
        if (tokens.isEmpty()) return null

        for (i in tokens.indices.reversed()) {
            val single = tokens[i]
            PlayerSubtitleUtils.normalizeLanguage(single)?.let { return it.tag }
            if (i > 0) {
                val pair = "${tokens[i - 1]}-${tokens[i]}"
                PlayerSubtitleUtils.normalizeLanguage(pair)?.let { return it.tag }
            }
        }

        val joined = tokens.joinToString(" ")
        return PlayerSubtitleUtils.normalizeLanguage(joined)?.tag
    }

    private fun buildDisplayName(
        language: String?,
        remainingTokens: List<String>,
        fileName: String,
        isForced: Boolean,
        isSdh: Boolean
    ): String {
        val base = when {
            language != null -> displayNameFromLanguage(language)
            remainingTokens.isNotEmpty() -> remainingTokens.joinToString(" ")
            else -> fileName
        }
        val flags = buildList {
            if (isForced) add("Forced")
            if (isSdh) add("SDH")
        }
        return if (flags.isEmpty()) base else "$base (${flags.joinToString(", ")})"
    }

    private fun displayNameFromLanguage(tag: String): String {
        val base = tag.substringBefore('-')
        val region = tag.substringAfter('-', missingDelimiterValue = "")
        val locale = if (region.isBlank()) Locale(base) else Locale(base, region.uppercase(Locale.ROOT))
        val name = locale.getDisplayLanguage(Locale.ENGLISH)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        if (name.isBlank() || name.equals(base, ignoreCase = true)) return tag
        return if (region.isBlank()) name else "$name (${region.uppercase(Locale.ROOT)})"
    }
}
