package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider

/**
 * One provider-agnostic edition of a work, resolved from [BookSearchProvider.fetchEditionsForWork]
 * (GitHub Issue #63).
 *
 * Unlike [BookSearchResult], which is work-level, this represents a specific printing or edition
 * with a concrete ISBN.
 */
public data class BookEditionSearchResult(
    val title: String,
    val publisher: String?,
    val publishDate: String?,
    val isbn: String,
    val pageCount: Int?,
    val coverThumbnailUrl: String?,
    val editionKey: String,
    val provider: IdentifierProvider,
)
