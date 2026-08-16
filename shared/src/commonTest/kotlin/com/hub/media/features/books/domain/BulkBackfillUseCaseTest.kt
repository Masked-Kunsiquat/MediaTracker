package com.hub.media.features.books.domain

import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.Logger
import com.hub.media.core.util.RecordingLogger
import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.network.BookMetadata
import com.hub.media.features.books.network.BookMetadataProvider
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.books.network.OpenLibraryCoverRateLimiter
import com.hub.media.features.books.network.OpenLibraryIsbnCoverProbe
import com.hub.media.features.settings.data.BulkBackfillState
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.getBulkBackfillState
import com.hub.media.features.settings.data.saveBulkBackfillState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * Tests for [BulkBackfillUseCase] (ROADMAP Task 14 Phase A): the shared library-wide cover/author
 * backfill. Uses a real in-memory [AppDatabase] / temp-dir [LocalImageStorageManager] (same style
 * as [RefetchCoverUseCaseTest][com.hub.media.features.books.domain.RefetchCoverUseCaseTest]),
 * hand-rolled [BookMetadataProvider] fakes for metadata, and [MockEngine]-backed
 * [OpenLibraryIsbnCoverProbe]/[CoverImageDownloader] for the network-facing pieces -- never a real
 * network call (AGENTS.md §7).
 */
class BulkBackfillUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var tempDir: String
    private lateinit var bookRepository: BookRepository
    private lateinit var settingsRepository: SettingsRepository

    @BeforeTest
    fun setUp() = runTest {
        db = testAppDatabase()
        tempDir = createTestTempDir()
        bookRepository = BookRepository(db)
        settingsRepository = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() = runTest {
        db.close()
        cleanupTestTempDir(tempDir)
    }

    private val coverImageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 4)

    /**
     * @param title Explicit, alphabetically-orderable title (defaults to embedding [mediaId], a
     *   random UUID). [BulkBackfillUseCase.seedState] seeds `pendingMediaIds` in
     *   [BookRepository.getAllBooksWithDetails]'s title order, so a test with two-or-more books
     *   whose relative *processing order* matters must pass distinct, deliberately-ordered titles
     *   here -- a default UUID-embedded title makes that order effectively a coin flip.
     */
    private suspend fun insertBook(
        mediaId: String = newId(),
        isbn: String? = "9780547928227",
        coverImageHash: String? = null,
        authors: String? = null,
        title: String = "Book $mediaId",
    ): String {
        db.mediaItemDao().insert(
            sampleMediaItem(id = mediaId, type = MediaType.BOOK, title = title, coverImageHash = coverImageHash),
        )
        db.bookDetailsDao().insert(sampleBookDetails(mediaId = mediaId, isbn = isbn).copy(authors = authors))
        return mediaId
    }

    /** A [BookMetadataProvider] fake keyed by ISBN, tracking how many times each ISBN was queried. */
    private class FakeMetadataProvider(
        private val responses: Map<String, Resource<BookMetadata>>,
    ) : BookMetadataProvider {
        val callCounts = mutableMapOf<String, Int>()
        override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> {
            callCounts[isbn] = (callCounts[isbn] ?: 0) + 1
            return responses[isbn] ?: Resource.Error("no fake response configured for $isbn")
        }
    }

    private fun metadata(
        authors: List<String> = emptyList(),
        coverImageUrl: String? = null,
        workKey: String? = null,
    ): Resource<BookMetadata> =
        Resource.Success(
            BookMetadata(
                title = "Some Title",
                authors = authors,
                coverImageUrl = coverImageUrl,
                provider = IdentifierProvider.OPEN_LIBRARY,
                workKey = workKey,
            ),
        )

    /** Gives [mediaId] a pre-existing work key, so it is *not* a work-key backfill candidate. */
    private suspend fun insertWorkKey(mediaId: String, workKey: String = "/works/OL27482W") {
        db.externalIdentifierDao().insert(
            ExternalIdentifierEntity(
                mediaId = mediaId,
                provider = IdentifierProvider.OPEN_LIBRARY_WORK,
                externalId = workKey,
            ),
        )
    }

    private suspend fun storedWorkKey(mediaId: String): String? =
        db.externalIdentifierDao().getByKey(mediaId, IdentifierProvider.OPEN_LIBRARY_WORK)?.externalId

    /** A probe-target [MockEngine] answering the `?default=false` cover probe per-ISBN. */
    private fun probeEngine(found: Set<String> = emptySet(), rateLimited: Set<String> = emptySet()) = MockEngine { request ->
        val url = request.url.toString()
        when {
            rateLimited.any { url.contains(it) } ->
                respondError(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "300"))
            found.any { url.contains(it) } -> respond(content = ByteArray(4), status = HttpStatusCode.OK)
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private fun downloadEngine() = MockEngine { _ ->
        respond(content = coverImageBytes, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
    }

    private fun useCase(
        metadataProvider: BookMetadataProvider,
        probeEngine: MockEngine,
        downloadEngine: MockEngine = downloadEngine(),
        rateLimiter: OpenLibraryCoverRateLimiter = OpenLibraryCoverRateLimiter(),
        imageStorage: LocalImageStorageManager = LocalImageStorageManager(tempDir),
        logger: Logger = AppLogger,
    ): BulkBackfillUseCase = BulkBackfillUseCase(
        metadataProvider = metadataProvider,
        isbnCoverProbe = OpenLibraryIsbnCoverProbe(createHttpClient(probeEngine), rateLimiter),
        coverDownloader = CoverImageDownloader(createHttpClient(downloadEngine)),
        imageStorage = imageStorage,
        bookRepository = bookRepository,
        settingsRepository = settingsRepository,
        logger = logger,
    )

    @Test
    fun execute_withNothingPending_stillTracesThatTheRunHappened() = runTest {
        // Found on a device, not here: running a backfill with nothing to do returned before any
        // tracing, so the log was unchanged and a no-op was indistinguishable from a button that
        // did nothing. That is the case that needs the entry *most* -- when work happens, the
        // library visibly changes; when none does, the log is the only evidence the run occurred.
        //
        // The existing tests all seed a book first, which is exactly why they covered the loop and
        // said nothing about this path.
        val recorder = RecordingLogger()
        val useCase = useCase(
            metadataProvider = FakeMetadataProvider(emptyMap()),
            probeEngine = MockEngine { respondError(HttpStatusCode.NotFound) },
            logger = recorder,
        )

        useCase.execute()

        val info = recorder.entries.single { it.level == LogLevel.INFO }
        assertEquals("BulkBackfillUseCase", info.tag)
        assertTrue(info.message.contains("nothing pending"), "got: ${info.message}")
    }

    @Test
    fun bookMissingOnlyTheWorkKey_isStillACandidate_andTheKeyIsWritten() = runTest {
        // The case the whole widening exists for: after Task 14's original crawl, a library's
        // covers and authors are complete, so nothing here was a candidate and the work key --
        // which only a provider lookup can produce -- would never have been captured for any book
        // that predates AddBookByIsbnUseCase recording it.
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = "abc123.jpg", authors = "Ada Lovelace")
        val provider = FakeMetadataProvider(mapOf(isbn to metadata(workKey = "/works/OL27482W")))
        val probe = probeEngine() // nothing here needs a cover, so the quota must stay untouched

        val progress = useCase(provider, probe).execute()

        assertEquals("/works/OL27482W", storedWorkKey(mediaId))
        assertEquals(1, progress.updated)
        assertTrue(progress.isComplete)
        assertTrue(probe.requestHistory.isEmpty(), "a work-key-only candidate must never reach the rate-limited probe")

        val mediaItem = db.mediaItemDao().getById(mediaId)
        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals("abc123.jpg", mediaItem?.coverImageHash, "an existing cover must not be touched")
        assertEquals("Ada Lovelace", details?.authors, "existing authors must not be touched")
    }

    @Test
    fun bookAlreadyHoldingAWorkKey_isNotACandidateAtAll() = runTest {
        // The positive control's negative half (AGENTS.md §7): the test above passes just as well
        // if *every* book is a candidate forever. This is what proves the check actually reads the
        // stored key rather than always reporting it missing.
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = "abc123.jpg", authors = "Ada Lovelace")
        insertWorkKey(mediaId)
        val provider = FakeMetadataProvider(mapOf(isbn to metadata(workKey = "/works/SHOULD_NOT_BE_FETCHED")))

        val progress = useCase(provider, probeEngine()).execute()

        assertEquals(0, progress.totalCandidates, "a book with cover, authors and a work key needs nothing")
        assertNull(provider.callCounts[isbn], "it must not be looked up at all")
        assertEquals("/works/OL27482W", storedWorkKey(mediaId), "the existing key must survive untouched")
    }

    @Test
    fun workKeyRidesTheSameLookupAsTheCoverAndAuthors() = runTest {
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(
            mapOf(isbn to metadata(authors = listOf("Ada Lovelace"), workKey = "/works/OL27482W")),
        )

        val progress = useCase(provider, probeEngine(found = setOf(isbn))).execute()

        assertEquals(1, provider.callCounts[isbn], "all three fields come from one lookup, not three crawls")
        assertEquals(1, progress.updated)
        assertNotNull(db.mediaItemDao().getById(mediaId)?.coverImageHash)
        assertEquals("Ada Lovelace", db.bookDetailsDao().getByMediaId(mediaId)?.authors)
        assertEquals("/works/OL27482W", storedWorkKey(mediaId))
    }

    @Test
    fun providerWithNoWorkConcept_dropsOutOfThePendingQueue_ratherThanDeferringForever() = runTest {
        // Google Books answers with no work key and never will. Such a book must resolve and drop
        // out of the pending queue, exactly as a book with a confirmed-absent cover does -- the
        // failure mode being guarded is a candidate that is deferred rather than resolved, and so
        // is retried on every resume of this run without ever being satisfiable.
        //
        // The guarantee is scoped to the run and its resumes. A later fresh run rescans it -- see
        // providerWithNoWorkConcept_isRescannedByAFreshRun_theAcceptedLimitation below.
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = "abc123.jpg", authors = "Ada Lovelace")
        val provider = FakeMetadataProvider(mapOf(isbn to metadata(workKey = null)))

        val progress = useCase(provider, probeEngine()).execute()

        assertEquals(1, progress.totalCandidates)
        assertEquals(0, progress.updated)
        assertEquals(1, progress.noProviderData, "nothing to write is resolved, not deferred")
        assertEquals(0, progress.remaining)
        assertTrue(progress.isComplete)
        assertNull(storedWorkKey(mediaId))
        assertNull(settingsRepository.getBulkBackfillState(), "a resolved run clears its resume state")
    }

    @Test
    fun providerWithNoWorkConcept_isRescannedByAFreshRun_theAcceptedLimitation() = runTest {
        // Pins the *actual* boundary of the guarantee above, which is per resume chain, not
        // forever. A book that can never have a work key drops out of the pending queue and is
        // never retried within a run or its resumes -- but a later fresh run rescans the library
        // from current state, where the key is still absent, so it is a candidate again and costs
        // one more lookup.
        //
        // This is identical to how a book with a genuinely-absent cover has always behaved, and is
        // accepted for the same reason: distinguishing "confirmed unavailable" from "not fetched
        // yet" needs a persisted negative cache, which would have to cover covers and authors too
        // or leave the three dimensions inconsistent. Recorded as a test so the cost is a known
        // quantity rather than a surprise.
        val isbn = "9780547928227"
        insertBook(isbn = isbn, coverImageHash = "abc123.jpg", authors = "Ada Lovelace")
        val provider = FakeMetadataProvider(mapOf(isbn to metadata(workKey = null)))

        useCase(provider, probeEngine()).execute()
        assertEquals(1, provider.callCounts[isbn])
        assertNull(settingsRepository.getBulkBackfillState(), "the first run resolved everything")

        useCase(provider, probeEngine()).execute()

        assertEquals(2, provider.callCounts[isbn], "a fresh run rescans from current state")
    }

    @Test
    fun rateLimitedCover_stillPersistsTheWorkKeyItAlreadyResolved() = runTest {
        // The deferral branches write whatever did not depend on the quota. Losing the work key
        // here would mean paying for the same lookup again on the resume run.
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(
            mapOf(isbn to metadata(authors = listOf("Ada Lovelace"), workKey = "/works/OL27482W")),
        )

        val progress = useCase(provider, probeEngine(rateLimited = setOf(isbn))).execute()

        assertTrue(progress.isPaused)
        assertEquals(1, progress.remaining, "the cover is still owed")
        assertEquals("/works/OL27482W", storedWorkKey(mediaId), "the work key never needed the quota")
        assertEquals("Ada Lovelace", db.bookDetailsDao().getByMediaId(mediaId)?.authors)
    }

    @Test
    fun bookNeedingBothCoverAndAuthors_resolvesBothInOnePass_andClearsResumeState() = runTest {
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(mapOf(isbn to metadata(authors = listOf("Ada Lovelace"))))

        val progress = useCase(provider, probeEngine(found = setOf(isbn))).execute()

        assertEquals(1, progress.updated)
        assertEquals(0, progress.noProviderData)
        assertTrue(progress.isComplete)
        assertEquals(1, provider.callCounts[isbn], "one metadata lookup covers both fields, not two crawls")

        val mediaItem = db.mediaItemDao().getById(mediaId)
        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertNotNull(mediaItem?.coverImageHash)
        assertEquals("Ada Lovelace", details?.authors)
        assertNull(settingsRepository.getBulkBackfillState(), "a fully-resolved run must clear its resume state")
    }

    @Test
    fun coverAlreadyResolvedByPrimaryProvider_probeNeverCalled_authorsStillWritten() = runTest {
        val isbn = "9780547928227"
        insertBook(isbn = isbn, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(
            mapOf(isbn to metadata(authors = listOf("Author"), coverImageUrl = "https://covers.example.com/cover.jpg")),
        )
        val probe = probeEngine() // would 404 anything, but must never even be called

        val progress = useCase(provider, probe).execute()

        assertEquals(1, progress.updated)
        assertTrue(probe.requestHistory.isEmpty(), "a cover already resolved by metadata must never reach the probe")
    }

    @Test
    fun bookWithNoIsbn_reportedSkipped_neverQueriedAndNeverRetried() = runTest {
        val isbn = "9780547928227"
        insertBook(isbn = isbn, coverImageHash = null, authors = null) // real candidate
        insertBook(isbn = null, coverImageHash = null, authors = null) // no-ISBN candidate
        val provider = FakeMetadataProvider(mapOf(isbn to metadata(authors = listOf("Author"))))

        val progress = useCase(provider, probeEngine(found = setOf(isbn))).execute()

        assertEquals(1, progress.totalCandidates, "the no-ISBN book must never enter the pending work queue")
        assertEquals(1, progress.noIsbnSkipped)
        assertEquals(1, provider.callCounts.values.sum(), "the no-ISBN book must never trigger a provider lookup")
        assertTrue(progress.isComplete)
    }

    @Test
    fun rateLimitedProbe_pausesRun_leavesCoverUntouchedRatherThanMarkingItAbsent_butStillWritesAuthors() = runTest {
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(mapOf(isbn to metadata(authors = listOf("Author"))))

        val progress = useCase(provider, probeEngine(rateLimited = setOf(isbn))).execute()

        assertTrue(progress.isPaused, "a 429 must pause the run, not report a clean finish")
        assertEquals(1, progress.remaining)
        assertEquals(0, progress.updated, "the book isn't fully resolved yet -- cover is still pending")

        val mediaItem = db.mediaItemDao().getById(mediaId)
        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertNull(mediaItem?.coverImageHash, "a rate-limited probe must NOT be treated as \"no cover\" -- must stay null, not some sentinel")
        assertEquals("Author", details?.authors, "authors don't touch the rate-limited probe, so they should still be written")

        val state = settingsRepository.getBulkBackfillState()
        assertNotNull(state, "a paused run must leave resumable state behind")
        assertEquals(listOf(mediaId), state.pendingMediaIds)
    }

    @Test
    fun confirmedNoCover404_isMarkedResolvedNotDeferred() = runTest {
        val isbn = "9780547928227"
        insertBook(isbn = isbn, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(mapOf(isbn to metadata(authors = listOf("Author"))))

        // probeEngine() with no found/rateLimited sets -> plain 404 for everything.
        val progress = useCase(provider, probeEngine()).execute()

        assertTrue(progress.isComplete, "a confirmed 404 resolves the book -- it must not stay pending forever")
        assertEquals(1, progress.updated, "authors alone still count as a real update")
    }

    @Test
    fun resumeAcrossAFreshUseCaseInstance_continuesFromWhereItStopped_doesNotRescanTheWholeLibrary() = runTest {
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(mapOf(isbn to metadata(authors = listOf("Author"))))

        // First "process": gets rate-limited and pauses.
        val firstRunProgress = useCase(provider, probeEngine(rateLimited = setOf(isbn))).execute()
        assertTrue(firstRunProgress.isPaused)
        assertEquals(1, firstRunProgress.remaining)

        // Simulate a book added to the library *after* the first (interrupted) run -- a resumed
        // run must not pick this up; only a fresh scan would.
        insertBook(isbn = "9780140449136", coverImageHash = null, authors = null)

        // Second "process" (fresh instance -- standing in for a relaunch after process death, or a
        // resume after the quota window has passed): a fresh rate limiter (quota reset) and a probe
        // that now succeeds for the original ISBN.
        val secondRunProgress = useCase(provider, probeEngine(found = setOf(isbn))).execute()

        assertTrue(secondRunProgress.isComplete, "the deferred book should now fully resolve")
        assertEquals(0, secondRunProgress.remaining)
        assertEquals(
            1,
            secondRunProgress.totalCandidates,
            "totalCandidates must stay fixed from the original seed, not grow to include the newly added book",
        )
        assertNotNull(db.mediaItemDao().getById(mediaId)?.coverImageHash)

        // The newly-added book was never part of this resume chain, so it must remain untouched.
        val newBook = db.bookDetailsDao().getAll().first { it.isbn == "9780140449136" }
        assertNull(db.mediaItemDao().getById(newBook.mediaId)?.coverImageHash)
    }

    @Test
    fun cancellationMidRun_leavesTheAlreadyCompletedBooksCheckpointed_andPropagatesCancellation() = runTest {
        val isbnA = "9780547928227"
        val isbnB = "9780140449136"
        // Explicit, alphabetically-ordered titles: seedState() seeds pendingMediaIds in title order,
        // and this test's "book A done, book B never reached" assertions depend on that order.
        val mediaIdA = insertBook(isbn = isbnA, coverImageHash = null, authors = null, title = "AAA - processed before cancellation")
        val mediaIdB = insertBook(isbn = isbnB, coverImageHash = null, authors = null, title = "BBB - never reached")

        // A provider fake whose second-book lookup throws CancellationException, standing in for
        // a real coroutine cancellation landing mid-network-call (Ktor propagates cancellation the
        // same way).
        val provider = object : BookMetadataProvider {
            override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> = when (isbn) {
                isbnA -> metadata(authors = listOf("Author A"))
                else -> throw CancellationException("simulated cancellation mid-run")
            }
        }
        val useCase = useCase(provider, probeEngine())

        assertFailsWith<CancellationException> { useCase.execute() }

        // Book A (processed before the cancellation) must be checkpointed as done...
        assertEquals("Author A", db.bookDetailsDao().getByMediaId(mediaIdA)?.authors)
        // ...and book B (never reached) must still be the resumable pending state, not lost.
        val state = settingsRepository.getBulkBackfillState()
        assertNotNull(state)
        assertEquals(listOf(mediaIdB), state.pendingMediaIds)
        assertEquals(1, state.updated)
    }

    @Test
    fun onProgressCallback_isInvokedAfterEveryBook() = runTest {
        val isbnA = "9780547928227"
        val isbnB = "9780140449136"
        insertBook(isbn = isbnA, coverImageHash = null, authors = null)
        insertBook(isbn = isbnB, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(
            mapOf(
                isbnA to metadata(authors = listOf("Author A")),
                isbnB to metadata(authors = listOf("Author B")),
            ),
        )

        val snapshots = mutableListOf<BulkBackfillProgress>()
        useCase(provider, probeEngine()).execute(onProgress = { snapshots += it })

        assertEquals(2, snapshots.size)
        assertEquals(1, snapshots[0].processed)
        assertEquals(2, snapshots[1].processed)
    }

    @Test
    fun peekProgress_nullWhenNothingInProgress_reflectsStateAfterAPause() = runTest {
        val isbn = "9780547928227"
        insertBook(isbn = isbn, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(mapOf(isbn to metadata(authors = listOf("Author"))))
        val bulkUseCase = useCase(provider, probeEngine(rateLimited = setOf(isbn)))

        assertNull(bulkUseCase.peekProgress(), "nothing has run yet")

        bulkUseCase.execute()

        val peeked = bulkUseCase.peekProgress()
        assertNotNull(peeked)
        assertEquals(1, peeked.remaining)
    }

    // ---- processOneBook branch coverage (PR review findings 2 & 5) --------------------------

    @Test
    fun coverDownloadHttpFailure_isDeferredTransient_authorsStillWritten_coverLeftPending() = runTest {
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(
            mapOf(isbn to metadata(authors = listOf("Author"), coverImageUrl = "https://covers.example.com/cover.jpg")),
        )
        val failingDownload = MockEngine { respondError(HttpStatusCode.InternalServerError) }

        val progress = useCase(provider, probeEngine(), failingDownload).execute()

        assertFalse(progress.isComplete, "a download failure must defer the book for retry, not drop it")
        assertEquals(1, progress.remaining)
        assertEquals(0, progress.processed, "totalCandidates(1) - remaining(1)")
        assertEquals(0, progress.updated, "a deferred book must not count as updated")
        assertEquals(0, progress.noProviderData)

        val mediaItem = db.mediaItemDao().getById(mediaId)
        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertNull(mediaItem?.coverImageHash, "a failed download must never write a cover hash")
        assertEquals("Author", details?.authors, "authors resolved this pass must still be written even though the cover download failed")

        val state = settingsRepository.getBulkBackfillState()
        assertEquals(listOf(mediaId), state?.pendingMediaIds, "the book must remain in the pending queue for a future retry")
    }

    @Test
    fun corruptImageBytesRejectedBySaveImage_isDeferredTransient_authorsStillWritten_coverLeftPending() = runTest {
        // CoverImageDownloader.download already turns a literally-empty HTTP body into a
        // Resource.Error before LocalImageStorageManager.saveImage is ever reached (see that
        // class's KDoc), so saveImage's own rejection path -- the `if (coverHashToWrite == null)
        // coverDeferred = true` branch in processOneBook -- can only be reached via a failure
        // saveImage itself reports for otherwise-valid, non-empty bytes (an I/O error writing the
        // file). Simulated here by pointing imageStorage at a base path nested under a plain file
        // instead of a directory, so the write can never succeed.
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = null, authors = null)
        val provider = FakeMetadataProvider(
            mapOf(isbn to metadata(authors = listOf("Author"), coverImageUrl = "https://covers.example.com/cover.jpg")),
        )
        val blockerFile = File(tempDir, "blocker-not-a-directory")
        blockerFile.writeText("occupying this path so it can't also be a directory")
        val brokenImageStorage = LocalImageStorageManager("${blockerFile.absolutePath}/covers")

        val progress = useCase(provider, probeEngine(), imageStorage = brokenImageStorage).execute()

        assertFalse(progress.isComplete, "a saveImage failure must defer the book for retry, not drop it")
        assertEquals(1, progress.remaining)
        assertEquals(0, progress.processed)
        assertEquals(0, progress.updated, "a deferred book must not count as updated")
        assertEquals(0, progress.noProviderData)

        val mediaItem = db.mediaItemDao().getById(mediaId)
        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertNull(mediaItem?.coverImageHash, "a failed local save must never write a cover hash")
        assertEquals("Author", details?.authors, "authors resolved this pass must still be written even though the cover save failed")

        val state = settingsRepository.getBulkBackfillState()
        assertEquals(listOf(mediaId), state?.pendingMediaIds, "the book must remain in the pending queue for a future retry")
    }

    @Test
    fun candidateAlreadyResolvedByOtherMeansBeforeThisRun_reportsRemoved_notCountedAsUpdated() = runTest {
        val isbnA = "9780547928227"
        val isbnB = "9780140449136"
        val mediaIdA = insertBook(isbn = isbnA, coverImageHash = null, authors = null)
        val mediaIdB = insertBook(isbn = isbnB, coverImageHash = null, authors = null)
        // Hand-seed resume state directly, standing in for a prior run's checkpoint, so this run
        // resumes rather than reseeds -- exactly like resumeAcrossAFreshUseCaseInstance above.
        settingsRepository.saveBulkBackfillState(
            BulkBackfillState(pendingMediaIds = listOf(mediaIdA, mediaIdB), totalCandidates = 2, noIsbnSkipped = 0, updated = 0, noProviderData = 0),
        )
        // Simulate mediaIdB having been fully resolved by some other means (a manual edit, or
        // RefetchCoverUseCase) between when this pending list was seeded/checkpointed and this run.
        db.mediaItemDao().updateCoverImageHash(mediaIdB, "already-there.jpg")
        db.bookDetailsDao().insert(sampleBookDetails(mediaId = mediaIdB, isbn = isbnB).copy(authors = "Already There"))
        // "Fully resolved" includes the work key now that a missing one is its own backfill gap --
        // without this, mediaIdB would still legitimately need a lookup and this test would be
        // asserting the old, narrower definition.
        insertWorkKey(mediaIdB)

        // mediaIdA is missing everything, work key included, so its lookup supplies one.
        val provider = FakeMetadataProvider(
            mapOf(isbnA to metadata(authors = listOf("Author A"), workKey = "/works/OL1A")),
        )
        val progress = useCase(provider, probeEngine()).execute()

        assertTrue(progress.isComplete)
        assertEquals(2, progress.processed)
        assertEquals(1, progress.updated, "only mediaIdA's real work should count -- mediaIdB was already resolved, not updated by this run")
        assertEquals(0, progress.noProviderData, "an already-resolved book is Removed, not counted as \"no provider data\" either")
        assertNull(provider.callCounts[isbnB], "a book already resolved by other means must never reach the provider")
    }

    @Test
    fun candidateDeletedAfterSeeding_reportsRemoved_notCountedAsUpdatedOrNoProviderData() = runTest {
        val isbn = "9780547928227"
        val mediaId = insertBook(isbn = isbn, coverImageHash = null, authors = null)
        settingsRepository.saveBulkBackfillState(
            BulkBackfillState(pendingMediaIds = listOf(mediaId), totalCandidates = 1, noIsbnSkipped = 0, updated = 0, noProviderData = 0),
        )
        db.mediaItemDao().deleteById(mediaId) // simulates the book being deleted between seeding and this run

        val provider = FakeMetadataProvider(emptyMap()) // must never even be queried
        val progress = useCase(provider, probeEngine()).execute()

        assertTrue(progress.isComplete)
        assertEquals(1, progress.processed)
        assertEquals(0, progress.updated, "a deleted candidate is Removed, never counted as updated")
        assertEquals(0, progress.noProviderData, "a deleted candidate is Removed, never counted as \"no provider data\" either")
        assertEquals(0, provider.callCounts.values.sum(), "a deleted candidate must never reach the provider")
        assertNull(settingsRepository.getBulkBackfillState(), "a fully-resolved run (even via Removed) must clear resume state")
    }

    // ---- applyWrite failure handling (PR review finding 2) -------------------------------------

    @Test
    fun failedAtomicWrite_isDeferredTransient_notCountedAsUpdated_bookStaysPending() = runTest {
        // needsCover is false (a cover already exists) so this book only needs an authors write --
        // isolates the applyWrite failure from any cover-download machinery.
        val mediaId = insertBook(isbn = "9780547928227", coverImageHash = "existing.jpg", authors = null)

        // Simulates a real race: this book's book_details row is deleted (e.g. the book itself
        // deleted, or corrupted, concurrently) *after* processOneBook's initial read but *before*
        // the resolved metadata is actually written -- BookRepository.applyBackfilledMetadata then
        // reports Resource.Error (0 rows affected) rather than throwing.
        val provider = object : BookMetadataProvider {
            override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> {
                db.bookDetailsDao().deleteByMediaId(mediaId)
                return metadata(authors = listOf("Ghost Author"))
            }
        }

        val progress = useCase(provider, probeEngine()).execute()

        assertFalse(progress.isComplete, "a failed write must leave the book pending for retry, not drop it")
        assertEquals(1, progress.remaining)
        assertEquals(0, progress.processed)
        assertEquals(0, progress.updated, "a failed write must never be counted as updated -- nothing was actually persisted")
        assertEquals(0, progress.noProviderData)

        val state = settingsRepository.getBulkBackfillState()
        assertEquals(listOf(mediaId), state?.pendingMediaIds, "the book must remain in the pending queue for a future retry")
    }
}
