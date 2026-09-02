package com.hub.media.ui

/**
 * One row in the search results, carrying only what the list draws and what selecting it needs.
 *
 * Shared by the film and show search screens: TMDB answers `/search/movie` and `/search/tv` with
 * the same shape, and the row draws the same four things either way. One type rather than two
 * identical ones, which is the duplication #81 asks this codebase to stop repeating per media
 * type.
 *
 * A UI type rather than [com.hub.media.features.tv.network.dto.TmdbSearchResultDto] so the screen
 * holds no dependency on a wire format:
 * the DTO's field names are TMDB's (`name` for shows, `title` for films), and a screen written
 * against them would have to be edited if a provider were ever added or swapped.
 *
 * @property year Already formatted for display, or `null` when TMDB gave no date. Derived here
 *   rather than in the composable so the "what does an empty date mean" question is answered once.
 */
public data class TmdbSearchResult(
    val tmdbId: Int,
    val title: String,
    val year: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
)
