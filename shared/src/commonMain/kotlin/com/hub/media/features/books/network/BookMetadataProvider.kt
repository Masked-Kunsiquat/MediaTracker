package com.hub.media.features.books.network

import com.hub.media.core.util.Resource

/**
 * A source of book metadata keyed by ISBN. Implementations must never throw out of
 * [fetchByIsbn] — all failure modes (network, parsing, non-2xx status, not-found) are surfaced
 * as [Resource.Error] per AGENTS.md §5.
 */
public interface BookMetadataProvider {
    /**
     * Looks up book metadata for the given [isbn].
     *
     * @param isbn ISBN-10 or ISBN-13 string, as provided by the caller (not normalized here).
     * @return [Resource.Success] with the parsed [BookMetadata], or [Resource.Error] describing
     *   why the lookup failed (not found, malformed response, network failure, etc.).
     */
    public suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata>
}
