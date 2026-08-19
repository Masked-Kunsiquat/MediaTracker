package com.hub.media.features.books.domain

import com.hub.media.core.util.LruCache
import com.hub.media.core.util.Resource
import com.hub.media.features.books.network.BookEditionSearchResult
import com.hub.media.features.books.network.BookSearchProvider

/**
 * Use case to resolve a work key to its available editions (GitHub Issue #63).
 *
 * This bridges the UI's work-level selection to the detailed edition-level selection.
 * Caches successful results in memory to avoid redundant network calls during selection.
 */
public class ResolveWorkToEditionsUseCase(
    private val searchProvider: BookSearchProvider,
    cacheSize: Int = 10,
) {
    private val cache = LruCache<String, List<BookEditionSearchResult>>(cacheSize)

    /**
     * Fetches and filters editions for [workKey].
     *
     * Only editions with valid ISBNs are returned, as they are the only ones that can be ingested
     * by [AddBookByIsbnUseCase].
     */
    public suspend fun execute(workKey: String): Resource<List<BookEditionSearchResult>> {
        val trimmed = workKey.trim()
        if (trimmed.isEmpty()) return Resource.Error("Work key cannot be empty")

        cache.get(trimmed)?.let { return Resource.Success(it) }

        val result = searchProvider.fetchEditionsForWork(trimmed)
        if (result is Resource.Success) {
            cache.put(trimmed, result.data)
        }
        return result
    }
}
