package com.nuvio.tv.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

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
        ?: runCatching { Instant.parse(value).atZone(zoneId).toLocalDate() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zoneId).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: ISO_DATE_PATTERN.find(value)?.value?.let { datePortion ->
            runCatching { LocalDate.parse(datePortion) }.getOrNull()
        }
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
    if (addonValue != null && hasEpisodeReleaseTime(addonValue)) return addonValue
    return tmdbAirDate?.trim()?.takeIf { it.isNotEmpty() } ?: addonValue
}

private fun hasEpisodeReleaseTime(value: String): Boolean =
    runCatching { Instant.parse(value) }.isSuccess ||
        runCatching { OffsetDateTime.parse(value) }.isSuccess ||
        runCatching { LocalDateTime.parse(value) }.isSuccess
