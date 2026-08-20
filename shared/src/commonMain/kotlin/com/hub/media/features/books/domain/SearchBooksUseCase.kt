package com.hub.media.features.books.domain

import com.hub.media.core.util.LruCache
import com.hub.media.core.util.Resource
import com.hub.media.features.books.network.BookSearchProvider
import com.hub.media.features.media.domain.SearchMediaUseCase
import com.hub.media.features.media.network.MediaSearchResult

/**
 * Minimum number of characters before a search is worth sending.
 *
 * Open Library's guidelines ask callers to make "useful, time-sensitive requests on behalf of
 * human users" and rate-limit at 1 request/second unidentified, 3/second identified. One or two
 * characters match essentially the whole catalogue, so a query that short spends budget to return
 * results nobody wants. Three is the point where a prefix starts to mean something.
 */
public const val MIN_SEARCH_QUERY_LENGTH: Int = 3

/** How many results to request per search — roughly one dropdown's worth. */
private const val DEFAULT_SEARCH_LIMIT = 20

/** How many distinct queries to remember. */
private const val SEARCH_CACHE_SIZE = 32

/**
 * "Search books by title or author" as a single call, owning the policy that keeps a type-ahead
 * from abusing the provider: a minimum query length, query normalization, and an in-memory LRU of
 * recent results (ROADMAP Task 9 Phase B1).
 *
 * Consolidated and generalized to [SearchMediaUseCase] per Issue #67.
 */
public interface SearchBooksUseCase : SearchMediaUseCase {
    /**
     * Searches for [query], answering from cache when the same normalized query was seen recently.
     *
     * @return [Resource.Success] with zero or more hits — including for a query shorter than
     *   [MIN_SEARCH_QUERY_LENGTH], which returns empty without touching the network. Callers that
     *   need to tell "keep typing" apart from "no matches" should ask [isQueryLongEnough] rather
     *   than re-deriving the normalization rules. [Resource.Error] means the search itself failed.
     */
    public suspend fun searchBooks(query: String): Resource<List<MediaSearchResult>>

    /** Bridge implementation for generalized [SearchMediaUseCase]. */
    override suspend fun execute(query: String): Resource<List<MediaSearchResult>> =
        searchBooks(query)

    public companion object {
        public const val MIN_SEARCH_QUERY_LENGTH: Int = com.hub.media.features.books.domain.MIN_SEARCH_QUERY_LENGTH
    }
}

/**
 * Concrete implementation of [SearchBooksUseCase] with an in-memory LRU cache.
 */
public class RealSearchBooksUseCase(
    private val provider: BookSearchProvider,
    private val limit: Int = DEFAULT_SEARCH_LIMIT,
    cacheSize: Int = SEARCH_CACHE_SIZE,
) : SearchBooksUseCase {
    private val cache = LruCache<String, List<MediaSearchResult>>(cacheSize)

    public override suspend fun searchBooks(query: String): Resource<List<MediaSearchResult>> {
        val normalized = normalize(query)
        if (normalized.length < MIN_SEARCH_QUERY_LENGTH) {
            return Resource.Success(emptyList())
        }

        cache.get(normalized)?.let { return Resource.Success(it) }

        return when (val result = provider.searchByTitleOrAuthor(normalized, limit)) {
            is Resource.Success -> {
                cache.put(normalized, result.data)
                result
            }
            is Resource.Error -> result
        }
    }

    public override fun isQueryLongEnough(query: String): Boolean = normalize(query).length >= MIN_SEARCH_QUERY_LENGTH

    private fun normalize(query: String): String = query.trim().replace(WHITESPACE_RUN, " ").lowercase()

    private companion object {
        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
