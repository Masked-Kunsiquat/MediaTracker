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
}
