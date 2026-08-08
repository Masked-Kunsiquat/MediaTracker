package com.hub.media.features.books.network

import com.hub.media.core.util.Resource
import io.ktor.client.HttpClient

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
 * also has no cover, [isbnCoverProbe] (when supplied) is consulted as one further last-resort
 * step — see that parameter's doc. This probe chain never downgrades a primary success into a
 * failure.
 *
 * @param primary The preferred provider (Open Library per AGENTS.md §4).
 * @param secondary The fallback provider (Google Books per AGENTS.md §4), invoked when [primary]
 *   fails, or as a cover-only probe when [primary] succeeds without a cover image.
 * @param isbnCoverProbe Last-resort ISBN-keyed cover probe (ROADMAP Task 6 Phase E), consulted
 *   only when [primary] succeeds but neither it nor [secondary] has a cover. `null` (the default)
 *   disables this step entirely — every existing caller/test that constructs this class without
 *   a third argument keeps its exact prior behavior. See [OpenLibraryIsbnCoverProbe]'s KDoc for
 *   why this is a separate, explicitly opt-in step rather than folded into [primary]/[secondary]
 *   themselves.
 */
public class FallbackBookMetadataProvider(
    private val primary: BookMetadataProvider,
    private val secondary: BookMetadataProvider,
    private val isbnCoverProbe: OpenLibraryIsbnCoverProbe? = null,
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
     * per the field-level cover fallback semantics documented on this class. If [secondary] also
     * has no cover, [isbnCoverProbe] (when non-null) is tried as one further last-resort step —
     * same merge-only-the-cover semantics, applied on top of [primaryResult] either way.
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
        if (secondaryCoverUrl != null) {
            return Resource.Success(primaryResult.data.copy(coverImageUrl = secondaryCoverUrl))
        }

        // A RateLimited probe result is deliberately treated the same as NotFound here, not
        // surfaced separately: this method's contract is "never downgrade a primary success," and
        // for a single interactive lookup (add-by-ISBN, per-book re-fetch) there is no meaningful
        // difference between "confirmed no cover" and "couldn't check right now" -- either way,
        // this one call simply doesn't get a cover, and the user can retry later same as any other
        // coverless result. A caller that DOES need to distinguish the two (the bulk backfill,
        // which loops over many books and must pause rather than write off every remaining one as
        // coverless) talks to [OpenLibraryIsbnCoverProbe] directly instead of through this merge --
        // see [com.hub.media.features.books.domain.BulkBackfillUseCase]'s KDoc.
        val probeResult = isbnCoverProbe?.probeCoverUrl(isbn)
        if (probeResult is CoverProbeResult.Found) {
            return Resource.Success(primaryResult.data.copy(coverImageUrl = probeResult.url))
        }

        return primaryResult
    }
}

/**
 * Standard Open Library → Google Books → ISBN-probe cover-resolution chain (AGENTS.md §4,
 * ROADMAP Task 6 Phase E), shared by [com.hub.media.features.books.domain.createDefaultAddBookByIsbnUseCase]
 * and [com.hub.media.features.books.domain.createDefaultRefetchCoverUseCase] so both entry points
 * (initial ingestion and the per-book re-fetch-cover affordance) resolve covers identically.
 *
 * @param httpClient Shared Ktor client used for all three underlying requests (Open Library
 *   metadata, Google Books metadata, and the last-resort ISBN cover probe).
 * @param coverRateLimiter Shared quota tracker for the ISBN-keyed cover probe (ROADMAP Task 14
 *   Phase A) -- defaults to a fresh, private [OpenLibraryCoverRateLimiter] so every pre-existing
 *   call site (and test) that doesn't pass one keeps working exactly as before, but production
 *   wiring (`AppContainer`) passes the *same* instance here and to
 *   [com.hub.media.features.books.domain.BulkBackfillUseCase] so both draw on one budget -- see
 *   [OpenLibraryCoverRateLimiter]'s KDoc for why a per-call-site limiter would be wrong.
 */
public fun createDefaultBookMetadataProvider(
    httpClient: HttpClient,
    coverRateLimiter: OpenLibraryCoverRateLimiter = OpenLibraryCoverRateLimiter(),
): BookMetadataProvider =
    FallbackBookMetadataProvider(
        primary = OpenLibraryClient(httpClient),
        secondary = GoogleBooksClient(httpClient),
        isbnCoverProbe = OpenLibraryIsbnCoverProbe(httpClient, coverRateLimiter),
    )
