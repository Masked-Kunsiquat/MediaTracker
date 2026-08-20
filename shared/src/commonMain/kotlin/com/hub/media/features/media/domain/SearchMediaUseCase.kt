package com.hub.media.features.media.domain

import com.hub.media.core.util.LruCache
import com.hub.media.core.util.Resource
import com.hub.media.features.media.network.MediaSearchProvider
import com.hub.media.features.media.network.MediaSearchResult

/** Minimum number of characters before a search is worth sending. */
public const val MIN_SEARCH_QUERY_LENGTH: Int = 3

/**
 * "Search media by title or creator" as a single call, owning the policy that keeps a type-ahead
 * from abusing the provider: a minimum query length, query normalization, and an in-memory LRU of
 * recent results (ROADMAP Task 9 Phase B1).
 *
 * Consolidated from `SearchBooksUseCase` per Issue #67.
 */
public interface SearchMediaUseCase {
    /**
     * Searches for [query], answering from cache when the same normalized query was seen recently.
     */
    public suspend fun execute(query: String): Resource<List<MediaSearchResult>>

    /**
     * Whether [query] is long enough to search at all, using the same normalization [execute] does.
     */
    public fun isQueryLongEnough(query: String): Boolean

    public companion object {
        public const val MIN_SEARCH_QUERY_LENGTH: Int = com.hub.media.features.media.domain.MIN_SEARCH_QUERY_LENGTH
    }
}

/**
 * Concrete implementation of [SearchMediaUseCase] with an in-memory LRU cache.
 */
public class RealSearchMediaUseCase(
    private val provider: MediaSearchProvider,
    private val limit: Int = 20,
    cacheSize: Int = 32,
) : SearchMediaUseCase {
    private val cache = LruCache<String, List<MediaSearchResult>>(cacheSize)

    override suspend fun execute(query: String): Resource<List<MediaSearchResult>> {
        val normalized = normalize(query)
        if (normalized.length < MIN_SEARCH_QUERY_LENGTH) {
            return Resource.Success(emptyList())
        }

        cache.get(normalized)?.let { return Resource.Success(it) }

        return when (val result = provider.search(normalized, limit)) {
            is Resource.Success -> {
                cache.put(normalized, result.data)
                result
            }
            is Resource.Error -> result
        }
    }

    override fun isQueryLongEnough(query: String): Boolean = normalize(query).length >= MIN_SEARCH_QUERY_LENGTH

    private fun normalize(query: String): String = query.trim().replace(WHITESPACE_RUN, " ").lowercase()

    private companion object {
        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
