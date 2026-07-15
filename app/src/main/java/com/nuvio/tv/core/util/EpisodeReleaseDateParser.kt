package com.nuvio.tv.core.util

import java.time.LocalDate

private val ISO_DATE_PATTERN = Regex("(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)")

/**
 * Episode release values represent calendar dates, even when an addon serializes them as a
 * UTC timestamp. Preserve the encoded date instead of converting midnight UTC into the device
 * timezone, which can shift the displayed air date back one day.
 */
internal fun parseEpisodeCalendarDate(raw: String?): LocalDate? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val datePortion = ISO_DATE_PATTERN.find(value)?.value ?: return null
    return runCatching { LocalDate.parse(datePortion) }.getOrNull()
}
