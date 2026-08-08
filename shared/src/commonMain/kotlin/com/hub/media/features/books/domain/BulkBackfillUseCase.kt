package com.hub.media.features.books.domain

import com.hub.media.core.database.entities.joinAuthors
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.network.BookMetadataProvider
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.books.network.CoverProbeResult
import com.hub.media.features.books.network.FallbackBookMetadataProvider
import com.hub.media.features.books.network.GoogleBooksClient
import com.hub.media.features.books.network.OPEN_LIBRARY_COVER_QUOTA_WINDOW
import com.hub.media.features.books.network.OpenLibraryClient
import com.hub.media.features.books.network.OpenLibraryCoverRateLimiter
import com.hub.media.features.books.network.OpenLibraryIsbnCoverProbe
import com.hub.media.features.settings.data.BulkBackfillState
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.clearBulkBackfillState
import com.hub.media.features.settings.data.getBulkBackfillState
import com.hub.media.features.settings.data.saveBulkBackfillState
import io.ktor.client.HttpClient
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.ensureActive

/**
 * Progress/result snapshot for a [BulkBackfillUseCase.execute] run (ROADMAP Task 14 Phase A),
 * reported honestly per the ROADMAP's brief ("312 of 480 done, paused until the quota resets" --
 * never an all-or-nothing failure). [processed] + [remaining] always equals [totalCandidates];
 * [updated] + [noProviderData] always equals the number of candidates permanently resolved so far
 * (which may be less than [processed] if some are still transiently deferred -- see
 * [BulkBackfillUseCase]'s KDoc).
 *
 * @property totalCandidates Books that needed backfilling and had an ISBN, fixed at the moment this
 *   backfill was first seeded (see [BulkBackfillState.totalCandidates]).
 * @property processed `totalCandidates - remaining`.
 * @property updated Cumulative count of books a new cover and/or authors were written for.
 * @property noProviderData Cumulative count of books fully resolved with nothing to write (every
 *   provider confirmed no cover, no authors).
 * @property noIsbnSkipped Books that can never be backfilled from a provider (ROADMAP Task 14 Phase
 *   A: "report them rather than retrying forever") -- fixed at seed time, never retried.
 * @property remaining Books still needing a future run (rate-limited this run, or a transient
 *   lookup/download failure).
 * @property isPaused `true` if this run stopped attempting new cover probes because the shared
 *   quota ([com.hub.media.features.books.network.OpenLibraryCoverRateLimiter]) was exhausted.
 * @property retryAfter Estimated wait before the quota frees up, meaningful only when [isPaused].
 */
public data class BulkBackfillProgress(
    public val totalCandidates: Int,
    public val processed: Int,
    public val updated: Int,
    public val noProviderData: Int,
    public val noIsbnSkipped: Int,
    public val remaining: Int,
    public val isPaused: Boolean,
    public val retryAfter: Duration?,
) {
    /** `true` once nothing is left to backfill -- [remaining] is 0. */
    public val isComplete: Boolean get() = remaining == 0
}

/**
 * Bulk cover-and-author backfill across the whole library (ROADMAP Task 14 Phase A), the
 * many-books generalization of [RefetchCoverUseCase]'s single-book re-fetch.
 *
 * ### Why covers AND authors in one pass, not a cover-only backfill
 * The motivating case (a Goodreads import) leaves books with **neither** a cover nor an author on
 * record -- Goodreads exports carry no cover data (`GoodreadsCsvImporter` sets `coverImageHash =
 * null` by design) and no reliable pre-Task-9-Phase-A author data. A single provider lookup
 * ([com.hub.media.features.books.network.BookMetadata]) already carries both the cover URL and the
 * author names, so repairing only the cover here and leaving authors for a hypothetical future
 * "bulk author backfill" would mean crawling the same rate-limited API twice for data already in
 * hand from the first crawl. Each candidate book is therefore checked for *either* gap
 * independently (a book might be missing just one of the two, e.g. a pre-Task-9 book with a cover
 * but no author), and whichever is missing is written in the one
 * [BookRepository.applyBackfilledMetadata] call for that book.
 *
 * ### Relationship to [RefetchCoverUseCase]
 * This is a **new, sibling** use case, not a generalization/wrapper of [RefetchCoverUseCase]:
 * [RefetchCoverUseCase] only ever touches the cover column and returns a single-book UX-facing
 * [Resource], neither of which fits a many-book, resumable, cover-*and*-author operation. The two
 * do share almost every underlying dependency (the same provider chain shape, the same
 * [CoverImageDownloader]/[LocalImageStorageManager]/[BookRepository]), and -- critically -- the same
 * [OpenLibraryCoverRateLimiter] instance in production wiring (`AppContainer`), which is what makes
 * the shared-quota requirement below actually hold.
 *
 * ### Only touches books missing data, never refreshes a book that already has both
 * A book with an existing cover/authors is never re-queried, even if a "better" provider match
 * might exist -- this is a *repair* pass for gaps, not a re-sync, and re-fetching data a user may
 * have manually corrected (once manual cover entry exists, per the ROADMAP backlog) would risk
 * clobbering it. [RefetchCoverUseCase] remains the explicit, opt-in way to force a single book's
 * cover to be looked up again regardless of whether one already exists.
 *
 * ### Rate limiting and honest partial progress
 * The only rate-limited network call in this whole pass is the last-resort ISBN cover probe (see
 * [isbnCoverProbe]) -- primary/secondary metadata lookups (title/authors/page count, and often the
 * cover too) are **not** rate limited. This use case therefore calls [metadataProvider] (with no
 * last-resort probe wired into it -- see [isbnCoverProbe]'s param doc) for every candidate freely,
 * and only reaches for [isbnCoverProbe] -- through the *shared* [OpenLibraryCoverRateLimiter] every
 * other ISBN-keyed cover-probe caller in the app also draws from -- when a book still needs a cover
 * after that. The moment the probe reports [CoverProbeResult.RateLimited] for any book, this run
 * stops issuing further probe calls (an exhausted shared window will just deny them anyway) but
 * keeps processing remaining candidates for whatever doesn't need the probe (a book only missing
 * authors, or one whose cover primary/secondary already resolved) -- partial progress is real
 * progress, not withheld until the whole library can be completed in one pass.
 *
 * ### Resumability
 * [execute] persists [BulkBackfillState] via [settingsRepository] after *every single book*, not
 * just at the end -- see [com.hub.media.features.settings.data.saveBulkBackfillState]'s call site
 * below. This is what makes an interruption by quota exhaustion, caller cancellation (structured
 * concurrency: cancelling the caller's coroutine simply stops this suspend loop at its next
 * suspension point, same as any other coroutine), or process death all recoverable the same way: the
 * next [execute] call reads back whatever was last saved and continues from there, re-checking each
 * pending book's *current* database state (not a stale scan-time snapshot) before deciding what
 * still needs doing.
 *
 * @param metadataProvider Primary → secondary metadata lookup chain, **without** the last-resort
 *   ISBN cover probe folded in (construct via [createDefaultBulkBackfillUseCase], which passes
 *   `isbnCoverProbe = null` to [com.hub.media.features.books.network.createDefaultBookMetadataProvider]'s
 *   underlying [FallbackBookMetadataProvider]) -- this use case calls [isbnCoverProbe] itself so it
 *   can see [CoverProbeResult.RateLimited] directly, which [FallbackBookMetadataProvider] would
 *   otherwise collapse into "no cover" (see that class's KDoc).
 * @param isbnCoverProbe The shared-quota last-resort cover probe, called directly (not through
 *   [metadataProvider]) specifically so [CoverProbeResult.RateLimited] is visible to this loop.
 * @param coverDownloader Downloads a resolved cover URL's raw bytes.
 * @param imageStorage Content-addressed local disk store for cover images (AGENTS.md §4).
 * @param bookRepository Source of the library scan, per-book current state, and the atomic
 *   cover+authors write.
 * @param settingsRepository Backing store for [BulkBackfillState] (see that class's KDoc for why
 *   `app_settings` -- no schema change -- is where resume state lives).
 */
public class BulkBackfillUseCase(
    private val metadataProvider: BookMetadataProvider,
    private val isbnCoverProbe: OpenLibraryIsbnCoverProbe,
    private val coverDownloader: CoverImageDownloader,
    private val imageStorage: LocalImageStorageManager,
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Runs (or resumes) one backfill pass. Processes [BulkBackfillState.pendingMediaIds] in order,
     * checkpointing to [settingsRepository] after every book, until either every candidate is
     * resolved, the shared cover-probe quota is exhausted for every remaining probe-needing book,
     * or the caller's coroutine is cancelled.
     *
     * @param onProgress Optional callback invoked with the current [BulkBackfillProgress] after
     *   every book this run touches (not just at the end) -- the UI-facing hook for a live progress
     *   bar. Never invoked with a stale/duplicate snapshot: each call reflects the state exactly as
     *   just persisted.
     * @return The final [BulkBackfillProgress] for this run. [BulkBackfillProgress.isComplete]
     *   tells the caller whether anything is left to resume later.
     */
    public suspend fun execute(
        onProgress: (suspend (BulkBackfillProgress) -> Unit)? = null,
    ): BulkBackfillProgress {
        var state = settingsRepository.getBulkBackfillState() ?: seedState()

        if (state.pendingMediaIds.isEmpty()) {
            settingsRepository.clearBulkBackfillState()
            return state.toProgress(isPaused = false, retryAfter = null)
        }

        val toProcess = state.pendingMediaIds
        val stillPending = mutableListOf<String>()
        var updated = state.updated
        var noProviderData = state.noProviderData
        var quotaExhausted = false
        var retryAfterSeen: Duration? = null

        for (index in toProcess.indices) {
            // Cooperative cancellation between books: a caller (e.g. the Settings screen's "cancel
            // backfill" action) cancelling this coroutine stops the loop here rather than mid-book,
            // and since state is checkpointed after every *completed* book below, whatever was last
            // saved remains the correct resume point either way.
            coroutineContext.ensureActive()
            val mediaId = toProcess[index]

            when (val outcome = processOneBook(mediaId, quotaExhausted, retryAfterSeen)) {
                StepOutcome.Removed -> Unit
                is StepOutcome.Done -> if (outcome.wroteAnything) updated++ else noProviderData++
                StepOutcome.DeferredTransient -> stillPending += mediaId
                is StepOutcome.DeferredRateLimited -> {
                    stillPending += mediaId
                    quotaExhausted = true
                    retryAfterSeen = outcome.retryAfter
                }
            }

            state = state.copy(
                pendingMediaIds = stillPending + toProcess.subList(index + 1, toProcess.size),
                updated = updated,
                noProviderData = noProviderData,
            )
            settingsRepository.saveBulkBackfillState(state)
            onProgress?.invoke(state.toProgress(isPaused = quotaExhausted, retryAfter = retryAfterSeen))
        }

        if (state.pendingMediaIds.isEmpty()) {
            settingsRepository.clearBulkBackfillState()
        }
        return state.toProgress(
            isPaused = quotaExhausted && state.pendingMediaIds.isNotEmpty(),
            retryAfter = retryAfterSeen,
        )
    }

    /**
     * One-shot peek at whether a backfill is currently resumable, for UI that wants to offer
     * "Resume backfill (168 remaining)" instead of "Start backfill" without actually running one.
     * `null` if nothing is in progress (see [BulkBackfillState]'s KDoc). [BulkBackfillProgress.isPaused]
     * is always `false` here (pause is a property of a *run*, not of the stored state -- this method
     * never touches the network), and [BulkBackfillProgress.retryAfter] is always `null`.
     */
    public suspend fun peekProgress(): BulkBackfillProgress? =
        settingsRepository.getBulkBackfillState()?.toProgress(isPaused = false, retryAfter = null)

    /**
     * Scans the whole library ([BookRepository.getAllBooksWithDetails]) for backfill candidates,
     * persists the freshly-seeded [BulkBackfillState], and returns it. Only called when
     * [settingsRepository] holds no existing resume state -- a resumed run reuses the already-seeded
     * pending list instead of rescanning (see [BulkBackfillState]'s KDoc: [BulkBackfillState.totalCandidates]/
     * [BulkBackfillState.noIsbnSkipped] must stay fixed across a resume chain for the progress
     * numbers to stay meaningful).
     *
     * A candidate is any book missing a cover and/or missing authors. Candidates with a blank/absent
     * ISBN are split out into [BulkBackfillState.noIsbnSkipped] and never placed in
     * [BulkBackfillState.pendingMediaIds] -- ROADMAP Task 14 Phase A: "books with no ISBN can never
     * be backfilled from a provider... report them rather than retrying forever."
     */
    private suspend fun seedState(): BulkBackfillState {
        val candidates = bookRepository.getAllBooksWithDetails().filter { book ->
            val needsCover = book.mediaItem.coverImageHash == null
            val needsAuthors = book.details?.authors == null
            needsCover || needsAuthors
        }
        val (withIsbn, withoutIsbn) = candidates.partition { !it.details?.isbn.isNullOrBlank() }

        val state = BulkBackfillState(
            pendingMediaIds = withIsbn.map { it.mediaItem.id },
            totalCandidates = withIsbn.size,
            noIsbnSkipped = withoutIsbn.size,
            updated = 0,
            noProviderData = 0,
        )
        settingsRepository.saveBulkBackfillState(state)
        return state
    }

    /**
     * Resolves whatever [mediaId] is still missing, using its **current** database state (re-read
     * here, not the possibly-stale seed-time snapshot -- a book could have been manually fixed, or
     * deleted, since this pending list was written).
     */
    private suspend fun processOneBook(
        mediaId: String,
        quotaExhausted: Boolean,
        knownRetryAfter: Duration?,
    ): StepOutcome {
        val bookWithDetails = bookRepository.getBookWithDetails(mediaId) ?: return StepOutcome.Removed
        val details = bookWithDetails.details
        val isbn = details?.isbn
        if (isbn.isNullOrBlank()) return StepOutcome.Removed // guarded at seed time; never expected

        val needsCover = bookWithDetails.mediaItem.coverImageHash == null
        val needsAuthors = details.authors == null
        if (!needsCover && !needsAuthors) return StepOutcome.Removed // resolved by other means already

        val metadataResult = metadataProvider.fetchByIsbn(isbn)
        if (metadataResult !is Resource.Success) return StepOutcome.DeferredTransient
        val metadata = metadataResult.data

        val authorsToWrite = if (needsAuthors) joinAuthors(metadata.authors) else null

        var coverUrl = metadata.coverImageUrl
        if (needsCover && coverUrl == null) {
            if (quotaExhausted) {
                // The shared quota already refused once this run; a fresh probe call for this book
                // would just be denied again by the same window (tryAcquire is process-local and
                // stateful, not per-ISBN). Skip straight to deferring, but still write authors below
                // if they were resolved -- that never touched the rate-limited probe. The Resource
                // this returns is deliberately not inspected: this book is already deferred either
                // way (a failed authors write just means needsAuthors is still true when this mediaId
                // is re-read on the next run, so it naturally retries), and neither wroteAnything nor
                // updated is ever computed from this branch.
                applyWrite(mediaId, coverHash = null, authors = authorsToWrite)
                return StepOutcome.DeferredRateLimited(knownRetryAfter ?: OPEN_LIBRARY_COVER_QUOTA_WINDOW)
            }
            when (val probeResult = isbnCoverProbe.probeCoverUrl(isbn)) {
                is CoverProbeResult.Found -> coverUrl = probeResult.url
                CoverProbeResult.NotFound -> Unit // confirmed absent; cover dimension resolved as "none"
                is CoverProbeResult.RateLimited -> {
                    // Same reasoning as the quotaExhausted branch above -- already deferred
                    // regardless of whether this write succeeds.
                    applyWrite(mediaId, coverHash = null, authors = authorsToWrite)
                    return StepOutcome.DeferredRateLimited(probeResult.retryAfter)
                }
            }
        }

        var coverHashToWrite: String? = null
        var coverDeferred = false
        if (needsCover && coverUrl != null) {
            val downloadResult = coverDownloader.download(coverUrl)
            if (downloadResult is Resource.Success) {
                coverHashToWrite = imageStorage.saveImage(downloadResult.data).getOrNull()
                if (coverHashToWrite == null) coverDeferred = true // save failed -- transient, retry later
            } else {
                coverDeferred = true // download failed -- transient, retry later
            }
        }

        val writeResult = applyWrite(mediaId, coverHash = coverHashToWrite, authors = authorsToWrite)

        // A failed write (Resource.Error -- mediaId vanished between the read above and this write,
        // or the underlying DB write itself threw, see BookRepository.applyBackfilledMetadata's
        // KDoc) must be treated exactly like a failed download: transient, retried on a future run.
        // Reporting it as Done here would be the actual bug this branch exists to prevent -- neither
        // coverHashToWrite nor authorsToWrite actually landed in the database, so counting this book
        // as "updated" and dropping it from the pending queue would silently lose it forever.
        if (coverDeferred || writeResult is Resource.Error) return StepOutcome.DeferredTransient

        val wroteAnything = coverHashToWrite != null || authorsToWrite != null
        return StepOutcome.Done(wroteAnything)
    }

    /**
     * Writes whatever of [coverHash]/[authors] this pass resolved for [mediaId], or a no-op
     * [Resource.Success] if both are `null` (nothing to write is not an error -- see
     * [BookRepository.applyBackfilledMetadata]'s KDoc).
     *
     * @return The [Resource] [BookRepository.applyBackfilledMetadata] reported, so every call site
     *   can tell a genuine write failure from success rather than discarding it (a discarded write
     *   failure would let this book get reported as [StepOutcome.Done] despite nothing having
     *   actually landed in the database) -- see [processOneBook]'s handling of this return value
     *   below.
     */
    private suspend fun applyWrite(mediaId: String, coverHash: String?, authors: String?): Resource<Unit> {
        if (coverHash == null && authors == null) return Resource.Success(Unit)
        return bookRepository.applyBackfilledMetadata(mediaId, coverHash, authors)
    }

    /** Outcome of resolving a single pending book, driving [execute]'s bookkeeping for that book. */
    private sealed class StepOutcome {
        /** Deleted since being queued, already complete, or (never expected) missing an ISBN. */
        data object Removed : StepOutcome()

        /** Fully resolved this run -- removed from the pending list permanently. */
        data class Done(val wroteAnything: Boolean) : StepOutcome()

        /** A transient failure (lookup, download, image save, or database write) -- retried on a
         * future run. */
        data object DeferredTransient : StepOutcome()

        /** The shared cover-probe quota is exhausted -- retried on a future run. */
        data class DeferredRateLimited(val retryAfter: Duration) : StepOutcome()
    }
}

private fun BulkBackfillState.toProgress(isPaused: Boolean, retryAfter: Duration?): BulkBackfillProgress =
    BulkBackfillProgress(
        totalCandidates = totalCandidates,
        processed = totalCandidates - pendingMediaIds.size,
        updated = updated,
        noProviderData = noProviderData,
        noIsbnSkipped = noIsbnSkipped,
        remaining = pendingMediaIds.size,
        isPaused = isPaused,
        retryAfter = retryAfter,
    )

/**
 * Convenience factory assembling [BulkBackfillUseCase] from a shared [HttpClient] and
 * [coverRateLimiter], mirroring [createDefaultRefetchCoverUseCase]/[createDefaultAddBookByIsbnUseCase].
 *
 * The metadata chain deliberately passes `isbnCoverProbe = null` to
 * [FallbackBookMetadataProvider] -- unlike the other two factories' use of
 * [com.hub.media.features.books.network.createDefaultBookMetadataProvider] -- so the last-resort
 * probe is only ever reached through [isbnCoverProbe] below, where [BulkBackfillUseCase] can see a
 * [CoverProbeResult.RateLimited] result directly (see [BulkBackfillUseCase]'s KDoc).
 *
 * @param httpClient Shared Ktor client used for every underlying request.
 * @param imageStorage Content-addressed local disk store for cover images.
 * @param bookRepository Source of the library scan and target of every write.
 * @param settingsRepository Backing store for resume state.
 * @param coverRateLimiter Shared ISBN-cover-probe quota tracker -- production wiring
 *   (`AppContainer`) passes the *same* instance also handed to [createDefaultRefetchCoverUseCase]
 *   and [createDefaultAddBookByIsbnUseCase], so this bulk pass and every interactive path draw on
 *   one budget (ROADMAP Task 14 Phase A's hard requirement -- see
 *   [OpenLibraryCoverRateLimiter]'s KDoc).
 */
public fun createDefaultBulkBackfillUseCase(
    httpClient: HttpClient,
    imageStorage: LocalImageStorageManager,
    bookRepository: BookRepository,
    settingsRepository: SettingsRepository,
    coverRateLimiter: OpenLibraryCoverRateLimiter,
): BulkBackfillUseCase = BulkBackfillUseCase(
    metadataProvider = FallbackBookMetadataProvider(
        primary = OpenLibraryClient(httpClient),
        secondary = GoogleBooksClient(httpClient),
        isbnCoverProbe = null,
    ),
    isbnCoverProbe = OpenLibraryIsbnCoverProbe(httpClient, coverRateLimiter),
    coverDownloader = CoverImageDownloader(httpClient),
    imageStorage = imageStorage,
    bookRepository = bookRepository,
    settingsRepository = settingsRepository,
)
