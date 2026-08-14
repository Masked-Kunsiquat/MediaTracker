package com.hub.media.features.books.domain

import com.hub.media.core.util.LruCache
import com.hub.media.core.util.Resource
import com.hub.media.features.books.network.BookSearchProvider
import com.hub.media.features.books.network.BookSearchResult

/**
 * Minimum number of characters before a search is worth sending.
 *
 * Open Library's guidelines ask callers to make "useful, time-sensitive requests on behalf of
 * human users" and rate-limit at 1 request/second unidentified, 3/second identified. One or two
 * characters match essentially the whole catalogue, so a query that short spends budget to return
 * results nobody wants. Three is the point where a prefix starts to mean something.
 *
 * The cost is real and worth stating: a genuine two-letter title cannot be found this way. Manual
 * entry (ROADMAP Task 9 Phase C) is the answer for those, as it is for anything the providers
 * don't know.
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
 * **The cache exists because typing is not monotonic.** A user types "hobbi", backspaces to
 * "hobb", then forward again — without a cache that is three network round-trips for two distinct
 * queries. Open Library's guidelines ask outright that clients "cache responses whenever
 * possible". It is in-memory and per-process on purpose: search results are a transient
 * convenience, not library data, and nothing here is worth a database table or a file.
 *
 * **What this class deliberately does not do is debounce or cancel.** Both need a coroutine scope
 * tied to the UI lifecycle, which belongs to the ViewModel in the app module (Phase B2). Putting a
 * timer in here would make every call sleep — including the cache hits this class exists to make
 * instant.
 *
 * @param provider Where results come from. Open Library only, by design: Google Books is consulted
 *   on selection or as a fallback, never per keystroke (ROADMAP Task 9).
 * @param limit Maximum hits per search.
 */
public class SearchBooksUseCase(
    private val provider: BookSearchProvider,
    private val limit: Int = DEFAULT_SEARCH_LIMIT,
    cacheSize: Int = SEARCH_CACHE_SIZE,
) {

    private val cache = LruCache<String, List<BookSearchResult>>(cacheSize)

    /**
     * Searches for [query], answering from cache when the same normalized query was seen recently.
     *
     * @return [Resource.Success] with zero or more hits — including for a query shorter than
     *   [MIN_SEARCH_QUERY_LENGTH], which returns empty without touching the network. Callers that
     *   need to tell "keep typing" apart from "no matches" should ask [isQueryLongEnough] rather
     *   than re-deriving the normalization rules. [Resource.Error] means the search itself failed.
     *
     * Cancellation propagates rather than being converted to an error, matching
     * [BookSearchProvider.searchByTitleOrAuthor] — a superseded keystroke is not a failure.
     */
    public suspend fun execute(query: String): Resource<List<BookSearchResult>> {
        val normalized = normalize(query)
        if (normalized.length < MIN_SEARCH_QUERY_LENGTH) {
            return Resource.Success(emptyList())
        }

        cache.get(normalized)?.let { return Resource.Success(it) }

        return when (val result = provider.searchByTitleOrAuthor(normalized, limit)) {
            is Resource.Success -> {
                // Only successes are cached. Caching an error would pin a transient offline blip to
                // a query for as long as the process lives, so the user would have to change what
                // they typed to retry something that started working again seconds later.
                cache.put(normalized, result.data)
                result
            }
            is Resource.Error -> result
        }
    }

    /**
     * Whether [query] is long enough to search at all, using the same normalization [execute] does.
     * Exposed so the UI can prompt "keep typing" without duplicating the rules.
     */
    public fun isQueryLongEnough(query: String): Boolean =
        normalize(query).length >= MIN_SEARCH_QUERY_LENGTH

    /**
     * Collapses queries that differ only in whitespace or case onto one cache key, so "The Hobbit",
     * "the hobbit" and "the  hobbit" are one entry and one request rather than three. Lowercasing
     * is safe to do before sending because Open Library's search is case-insensitive anyway.
     */
    private fun normalize(query: String): String =
        query.trim().replace(WHITESPACE_RUN, " ").lowercase()

    private companion object {
        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
