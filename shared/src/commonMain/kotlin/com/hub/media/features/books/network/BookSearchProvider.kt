package com.hub.media.features.books.network

import com.hub.media.core.util.Resource

/**
 * A source of book *candidates* for a free-text title/author query (ROADMAP Task 9 Phase B1).
 *
 * Separate from [BookMetadataProvider] rather than a method on it, because the two have different
 * shapes and different costs: that one resolves exactly one known edition by ISBN, this one
 * returns a ranked list of guesses for text a user is still typing. The fallback chain also
 * differs — Google Books is consulted only on selection or as a fallback, never per keystroke,
 * since its keyless per-IP quota is limited and 429s have already been observed against it
 * (ROADMAP Task 9). Folding both into one interface would have forced every implementation to
 * answer a question it has no business answering.
 */
public interface BookSearchProvider {
    /**
     * Searches for works matching free-text [query], which may be a title, an author, or both.
     *
     * Implementations must never throw out of this method — every failure mode (network, parsing,
     * non-2xx status) is surfaced as [Resource.Error] per AGENTS.md §5 — with **one deliberate
     * exception: [kotlinx.coroutines.CancellationException] propagates.** This method is called
     * from a type-ahead where each keystroke cancels the request before it, so cancellation is the
     * ordinary case rather than a fault; swallowing it into a `Resource.Error` would break
     * structured concurrency and log a provider failure every time the user types another letter
     * (ROADMAP Task 15 Phase C).
     *
     * @param query Raw user input. Implementations are responsible for their own trimming and
     *   encoding, but **not** for the minimum-length and debounce policy — that belongs to
     *   [com.hub.media.features.books.domain.SearchBooksUseCase], so a caller cannot bypass it.
     * @param limit Maximum number of hits to return.
     * @return [Resource.Success] with zero or more hits — an empty list is a successful search that
     *   found nothing, not an error — or [Resource.Error] describing why the search failed.
     */
    public suspend fun searchByTitleOrAuthor(
        query: String,
        limit: Int,
    ): Resource<List<BookSearchResult>>

    /**
     * Resolves an edition key to a concrete ISBN (ROADMAP Task 9 Phase B2).
     *
     * A search result carries a `coverEditionKey` but no ISBN; selecting that result means resolving
     * the edition to an ISBN so it can be passed to [com.hub.media.features.books.domain.AddBookByIsbnUseCase].
     * This method is the bridge from work-level search results to edition-level metadata.
     *
     * @param editionKey The provider-native edition identifier (e.g. `OL51711263M` for Open Library).
     * @return [Resource.Success] with an ISBN string (either `isbn_10` or `isbn_13`), or `null` if
     *   the edition is found but carries no ISBN in the provider's index — this is not an error,
     *   merely the case where this edition cannot be added (it legitimately has no ISBN). [Resource.Error]
     *   means the lookup itself failed (network, parse, status).
     *
     * Implementations must never throw out of this method — the same [Resource.Error] contract and
     * [kotlinx.coroutines.CancellationException] propagation rule as [searchByTitleOrAuthor] apply.
     */
    public suspend fun resolveEditionToIsbn(editionKey: String): Resource<String?>

    /**
     * Fetches all editions for a given [workKey] (GitHub Issue #63).
     *
     * This method allows the user to select a specific edition from a list when a work-level search
     * result is selected. The result is filtered to only include editions with valid ISBNs.
     *
     * @param workKey The provider-native work identifier (e.g. `/works/OL27482W`).
     * @return [Resource.Success] with a list of [BookEditionSearchResult]s, or [Resource.Error].
     */
    public suspend fun fetchEditionsForWork(workKey: String): Resource<List<BookEditionSearchResult>>
}
