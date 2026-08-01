package com.hub.media.features.books.network

import com.hub.media.core.util.Resource

/**
 * Composes two [BookMetadataProvider]s with fallback semantics: tries [primary] first, and only
 * calls [secondary] if [primary] returns [Resource.Error] (this includes "not found" responses,
 * since providers map those to [Resource.Error] as well). Per AGENTS.md §4, this is how the
 * Open Library → Google Books primary/fallback chain is expressed.
 *
 * @param primary The preferred provider (Open Library per AGENTS.md §4).
 * @param secondary The fallback provider (Google Books per AGENTS.md §4), only invoked when
 *   [primary] fails.
 */
public class FallbackBookMetadataProvider(
    private val primary: BookMetadataProvider,
    private val secondary: BookMetadataProvider,
) : BookMetadataProvider {

    override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> {
        val primaryResult = primary.fetchByIsbn(isbn)
        if (primaryResult is Resource.Success) {
            return primaryResult
        }

        val primaryError = primaryResult as Resource.Error
        val secondaryResult = secondary.fetchByIsbn(isbn)
        if (secondaryResult is Resource.Success) {
            return secondaryResult
        }

        val secondaryError = secondaryResult as Resource.Error
        return Resource.Error(
            "Book metadata lookup failed for ISBN $isbn on both providers. " +
                "Primary: ${primaryError.message}. Secondary: ${secondaryError.message}",
        )
    }
}
