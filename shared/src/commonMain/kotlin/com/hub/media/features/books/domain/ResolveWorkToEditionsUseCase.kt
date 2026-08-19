package com.hub.media.features.books.domain

import com.hub.media.core.util.Resource
import com.hub.media.features.books.network.BookEditionSearchResult
import com.hub.media.features.books.network.BookSearchProvider

/**
 * Use case to resolve a work key to its available editions (GitHub Issue #63).
 *
 * This bridges the UI's work-level selection to the detailed edition-level selection.
 */
public class ResolveWorkToEditionsUseCase(
    private val searchProvider: BookSearchProvider,
) {
    /**
     * Fetches and filters editions for [workKey].
     *
     * Only editions with valid ISBNs are returned, as they are the only ones that can be ingested
     * by [AddBookByIsbnUseCase].
     */
    public suspend fun execute(workKey: String): Resource<List<BookEditionSearchResult>> =
        searchProvider.fetchEditionsForWork(workKey)
}
