package com.hub.media.features.media.network

import com.hub.media.core.util.Resource

/**
 * A source of media *candidates* for a free-text query (ROADMAP Task 9 Phase B1).
 * Consolidated from `BookSearchProvider` per Issue #67.
 */
public interface MediaSearchProvider {
    /**
     * Searches for media items matching free-text [query].
     *
     * Implementations must never throw out of this method — every failure mode (network, parsing,
     * non-2xx status) is surfaced as [Resource.Error] per AGENTS.md §5 — with **one deliberate
     * exception: [kotlinx.coroutines.CancellationException] propagates.**
     *
     * @param query Raw user input.
     * @param limit Maximum number of hits to return.
     * @return [Resource.Success] with zero or more hits, or [Resource.Error] on failure.
     */
    public suspend fun search(
        query: String,
        limit: Int,
    ): Resource<List<MediaSearchResult>>
}
