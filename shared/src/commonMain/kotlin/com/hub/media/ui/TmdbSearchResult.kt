package com.hub.media.ui

import com.hub.media.features.tv.network.dto.TmdbSearchResultDto

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

/**
 * A search hit as the list draws it, or `null` when TMDB sent one with no usable title.
 *
 * Dropped rather than shown untitled: `addShow`/`addMovie` both reject a blank title, so offering
 * such a row would be offering a tap that always fails.
 *
 * Shared by both search ViewModels. [TmdbSearchResultDto.displayTitle] already resolves TMDB's
 * `name`-for-shows / `title`-for-films split, so the function that builds a row out of it has no
 * media type in it either -- and two identical copies of it was the duplication moving
 * [TmdbSearchResult] into this file was meant to remove.
 */
internal fun TmdbSearchResultDto.toSearchResult(): TmdbSearchResult? {
    val name = displayTitle?.takeIf { it.isNotBlank() } ?: return null
    return TmdbSearchResult(
        tmdbId = id,
        title = name,
        year = displayDate?.takeIf { it.isNotBlank() }?.take(4),
        overview = overview?.takeIf { it.isNotBlank() },
        posterPath = posterPath,
    )
}
