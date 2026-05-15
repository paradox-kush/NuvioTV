package com.nuvio.tv.domain.model.locallibrary

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.ContentType

/**
 * The TMDB match outcome for a [ScannedItem], persisted in MatchOverrideStore
 * so it survives rescans. A user-picked override carries [userSet] = true and
 * must not be clobbered by auto-matching.
 */
@Immutable
data class LocalMatch(
    val itemKey: String,
    val tmdbId: Int,
    val contentType: ContentType,
    /** For series items: the season/episode this file represents. */
    val season: Int? = null,
    val episode: Int? = null,
    val userSet: Boolean = false,
    /** Confidence in [0.0, 1.0]; meaningless when [userSet] is true. */
    val score: Float = 0f
)
