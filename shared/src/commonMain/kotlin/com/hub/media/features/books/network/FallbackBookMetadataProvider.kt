package com.hub.media.features.books.network

import com.hub.media.core.util.Resource

/**
 * Composes two [BookMetadataProvider]s with fallback semantics: tries [primary] first, and only
 * calls [secondary] if [primary] returns [Resource.Error] (this includes "not found" responses,
 * since providers map those to [Resource.Error] as well). Per AGENTS.md §4, this is how the
 * Open Library → Google Books primary/fallback chain is expressed.
 *
 * **Field-level cover fallback:** a [primary] success is not always a *complete* success —
 * Open Library edition records can legitimately have `covers: null` (e.g. ISBN 9798217298976 /
 * edition OL61570965M) even though the lookup itself is otherwise valid. When [primary] succeeds
 * but its [BookMetadata.coverImageUrl] is `null`, [secondary] is additionally consulted as a
 * cover-only probe: if it succeeds with a non-null [BookMetadata.coverImageUrl], the returned
 * result is the **primary's** [BookMetadata] with only [BookMetadata.coverImageUrl] copied over
 * from the secondary (via [BookMetadata.copy]) — every other field (title, authors, page count,
 * year, provider, external id, ...) always comes from the primary, since the primary is the
 * preferred, authoritative source. A cover is a nice-to-have: if the secondary probe errors, or
 * also has no cover, the primary's result is returned completely unchanged. This probe never
 * downgrades a primary success into a failure.
 *
 * @param primary The preferred provider (Open Library per AGENTS.md §4).
 * @param secondary The fallback provider (Google Books per AGENTS.md §4), invoked when [primary]
 *   fails, or as a cover-only probe when [primary] succeeds without a cover image.
 */
public class FallbackBookMetadataProvider(
    private val primary: BookMetadataProvider,
    private val secondary: BookMetadataProvider,
) : BookMetadataProvider {

    override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> {
        val primaryResult = primary.fetchByIsbn(isbn)
        if (primaryResult is Resource.Success) {
            return withCoverFallback(primaryResult, isbn)
        }

        val primaryError = primaryResult as Resource.Error
        val secondaryResult = secondary.fetchByIsbn(isbn)
        if (secondaryResult is Resource.Success) {
            return secondaryResult
        }

        val secondaryError = secondaryResult as Resource.Error
        return Resource.Error(
            "Book metadata lookup failed for ISBN $isbn on both providers. " +
                "Primary: ${primaryError.message}. Secondary: ${secondaryError.message}",
        )
    }

    /**
     * If [primaryResult] already has a cover, returns it unchanged (secondary is never called).
     * Otherwise probes [secondary] for a cover image and merges it into the primary's metadata,
     * per the field-level cover fallback semantics documented on this class.
     */
    private suspend fun withCoverFallback(
        primaryResult: Resource.Success<BookMetadata>,
        isbn: String,
    ): Resource<BookMetadata> {
        if (primaryResult.data.coverImageUrl != null) {
            return primaryResult
        }

        val secondaryResult = secondary.fetchByIsbn(isbn)
        val secondaryCoverUrl = (secondaryResult as? Resource.Success)?.data?.coverImageUrl
            ?: return primaryResult

        return Resource.Success(primaryResult.data.copy(coverImageUrl = secondaryCoverUrl))
    }
}
