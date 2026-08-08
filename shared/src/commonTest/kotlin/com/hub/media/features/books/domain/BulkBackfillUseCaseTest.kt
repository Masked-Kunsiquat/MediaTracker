package com.hub.media.features.books.domain

import com.hub.media.core.database.AppDatabase
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
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.getBulkBackfillState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private suspend fun insertBook(
        mediaId: String = newId(),
        isbn: String? = "9780547928227",
        coverImageHash: String? = null,
        authors: String? = null,
    ): String {
        db.mediaItemDao().insert(
            sampleMediaItem(id = mediaId, type = MediaType.BOOK, title = "Book $mediaId", coverImageHash = coverImageHash),
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

    private fun metadata(authors: List<String> = emptyList(), coverImageUrl: String? = null): Resource<BookMetadata> =
        Resource.Success(
            BookMetadata(
                title = "Some Title",
                authors = authors,
                coverImageUrl = coverImageUrl,
                provider = IdentifierProvider.OPEN_LIBRARY,
            ),
        )

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
    ): BulkBackfillUseCase = BulkBackfillUseCase(
        metadataProvider = metadataProvider,
        isbnCoverProbe = OpenLibraryIsbnCoverProbe(createHttpClient(probeEngine), rateLimiter),
        coverDownloader = CoverImageDownloader(createHttpClient(downloadEngine)),
        imageStorage = LocalImageStorageManager(tempDir),
        bookRepository = bookRepository,
        settingsRepository = settingsRepository,
    )

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
        val mediaIdA = insertBook(isbn = isbnA, coverImageHash = null, authors = null)
        val mediaIdB = insertBook(isbn = isbnB, coverImageHash = null, authors = null)

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
}
