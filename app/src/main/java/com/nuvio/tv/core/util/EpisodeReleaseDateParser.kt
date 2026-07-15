package com.nuvio.tv.core.util

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val ISO_DATE_PATTERN = Regex("(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)")

/**
 * Converts timestamped episode releases into the viewer's local calendar date. Plain dates have
 * no timezone information and are preserved as supplied by the provider.
 */
internal fun parseEpisodeReleaseLocalDate(
    raw: String?,
    zoneId: ZoneId = ZoneId.systemDefault()
): LocalDate? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return runCatching { LocalDate.parse(value) }.getOrNull()
        ?: parseExplicitReleaseInstant(value)?.atZone(zoneId)?.toLocalDate()
        ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: parseEmbeddedReleaseDate(value)
}

/**
 * Returns whether a known release has been reached. Zoned timestamps use their exact instant;
 * date-only values become available at local midnight. A local timestamp without a zone is
 * interpreted in the viewer's timezone but is never considered precise provider metadata.
 */
internal fun isEpisodeReleaseAired(
    raw: String?,
    clock: Clock = Clock.systemDefaultZone()
): Boolean? {
    val releaseInstant = parseEpisodeReleaseInstant(raw, clock.zone) ?: return null
    return !releaseInstant.isAfter(clock.instant())
}

internal fun parseEpisodeReleaseInstant(
    raw: String?,
    zoneId: ZoneId = ZoneId.systemDefault()
): Instant? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return parseExplicitReleaseInstant(value)
        ?: runCatching { LocalDateTime.parse(value).atZone(zoneId).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(value).atStartOfDay(zoneId).toInstant() }.getOrNull()
        ?: parseEmbeddedReleaseDate(value)?.atStartOfDay(zoneId)?.toInstant()
}

/**
 * TMDB episode air dates are date-only and may use the broadcaster's calendar day. Keep an
 * addon's timestamp when present because it carries enough information to derive the viewer's
 * local day; use TMDB as enrichment only when that precision is unavailable.
 */
internal fun selectEpisodeReleaseValue(
    addonReleased: String?,
    tmdbAirDate: String?,
    useTmdbReleaseDates: Boolean
): String? {
    val addonValue = addonReleased?.trim()?.takeIf { it.isNotEmpty() }
    if (!useTmdbReleaseDates) return addonValue
    if (addonValue != null && hasExplicitReleaseZone(addonValue)) return addonValue
    return tmdbAirDate?.trim()?.takeIf { it.isNotEmpty() } ?: addonValue
}

private fun hasExplicitReleaseZone(value: String): Boolean =
    runCatching { Instant.parse(value) }.isSuccess ||
        runCatching { OffsetDateTime.parse(value) }.isSuccess ||
        runCatching { ZonedDateTime.parse(value) }.isSuccess

private fun parseExplicitReleaseInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()

private fun parseEmbeddedReleaseDate(value: String): LocalDate? =
    ISO_DATE_PATTERN.find(value)?.value?.let { datePortion ->
        runCatching { LocalDate.parse(datePortion) }.getOrNull()
    }
