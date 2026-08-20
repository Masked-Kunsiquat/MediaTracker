package com.hub.media.features.books.network

import com.hub.media.core.util.Resource
import com.hub.media.features.media.network.MediaSearchResult

/**
 * Hand-rolled fake [BookSearchProvider] for ViewModel tests (AGENTS.md §5 "No Unnecessary
 * Dependencies" — no mocking library). Records method calls and returns configurable results.
 *
 * Tracks [resolveCallCount] and [lastResolvedEditionKey] to verify the selection-to-ISBN
 * flow; used by [com.hub.media.ui.AddBookViewModel] tests to verify the search result
 * selection triggers the right ISBN resolution (ROADMAP Task 9 Phase B2).
 */
internal class FakeBookSearchProvider(
    private val isbn: String? = "9780547928227",
    private val error: Resource.Error? = null,
) : BookSearchProvider {
    var resolveCallCount: Int = 0
        private set

    var lastResolvedEditionKey: String? = null
        private set

    override suspend fun searchByTitleOrAuthor(
        query: String,
        limit: Int,
    ): Resource<List<MediaSearchResult>> = Resource.Success(emptyList())

    override suspend fun resolveEditionToIsbn(editionKey: String): Resource<String?> {
        resolveCallCount++
        lastResolvedEditionKey = editionKey
        return error ?: Resource.Success(isbn)
    }

    override suspend fun fetchEditionsForWork(workKey: String): Resource<List<BookEditionSearchResult>> =
        error ?: Resource.Success(emptyList())
}
