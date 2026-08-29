package com.hub.media.ui

import androidx.room.execSQL
import androidx.room.useWriterConnection
import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.Logger
import com.hub.media.core.util.RecordingLogger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.domain.BulkBackfillUseCase
import com.hub.media.features.books.network.BookMetadata
import com.hub.media.features.books.network.BookMetadataProvider
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.books.network.OpenLibraryCoverRateLimiter
import com.hub.media.features.books.network.OpenLibraryIsbnCoverProbe
import com.hub.media.features.settings.data.BulkBackfillState
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.saveBulkBackfillState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [BackfillViewModel] tests against a real in-memory [AppDatabase] (via `testAppDatabase()`, same
 * style as [BookDetailViewModelTest]/[LibraryViewModelTest]), so [BulkBackfillUseCase] runs against
 * genuine async Room reads/writes rather than a fake -- necessary because
 * [BulkBackfillUseCase]/[BookRepository]/[SettingsRepository] are concrete classes with no seam to
 * fake at the ViewModel boundary, and because the races this file covers (PR review findings 1, 3,
 * 4) are specifically about ordering against real asynchronous DB completion, not virtual-time
 * scheduling. Room-backed, so in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81) for the same reason as `BookDetailViewModelTest`.
 *
 * Every book in this file already has a cover (`coverImageHash` set at insert time) and only needs
 * authors, so [OpenLibraryIsbnCoverProbe]/[CoverImageDownloader]/[LocalImageStorageManager] are
 * wired but never actually exercised -- the only network-shaped seam under test control is
 * [GatedMetadataProvider.fetchByIsbn], gated per-ISBN via [CompletableDeferred] so each test can
 * deterministically freeze [BulkBackfillUseCase.execute] mid-run and observe [BackfillViewModel]'s
 * state at that exact point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackfillViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var settingsRepository: SettingsRepository
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        settingsRepository = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * A book that already has a cover -- only [needsAuthors] can ever be true for it here.
     *
     * @param title Explicit, alphabetically-orderable title (defaults to embedding [mediaId], which
     *   is a random UUID). [BulkBackfillUseCase.seedState] seeds `pendingMediaIds` in
     *   [BookRepository.getAllBooksWithDetails]'s title order (see that method's KDoc), so any test
     *   with two books whose *processing order relative to each other* matters (e.g. "book A
     *   resolves, book B is gated") must pass distinct, deliberately-ordered titles here -- a
     *   default UUID-embedded title makes that order effectively a coin flip.
     */
    private suspend fun insertBook(
        mediaId: String = newId(),
        isbn: String = "9780547928227",
        title: String = "Book $mediaId",
    ): String {
        db.mediaItemDao().insert(
            sampleMediaItem(id = mediaId, type = MediaType.BOOK, title = title, coverImageHash = "existing.jpg"),
        )
        db.bookDetailsDao().insert(sampleBookDetails(mediaId = mediaId, isbn = isbn).copy(authors = null))
        return mediaId
    }

    /**
     * Fake [BookMetadataProvider] whose [fetchByIsbn] suspends on a per-ISBN [CompletableDeferred]
     * gate until [release] is called for that ISBN -- lets a test freeze [BulkBackfillUseCase.execute]
     * at an exact, known point instead of racing real time. ISBNs not passed to [gate] resolve
     * immediately.
     */
    private class GatedMetadataProvider(
        private val responses: Map<String, Resource<BookMetadata>>,
        gatedIsbns: Set<String>,
    ) : BookMetadataProvider {
        private val gates = gatedIsbns.associateWith { CompletableDeferred<Unit>() }

        override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> {
            gates[isbn]?.await()
            return responses[isbn] ?: Resource.Error("no fake response configured for $isbn")
        }

        fun release(isbn: String) {
            gates.getValue(isbn).complete(Unit)
        }
    }

    private fun metadata(authors: List<String>): Resource<BookMetadata> =
        Resource.Success(
            BookMetadata(title = "Some Title", authors = authors, provider = IdentifierProvider.OPEN_LIBRARY),
        )

    private fun newUseCase(
        provider: BookMetadataProvider,
        db: AppDatabase = this.db,
    ): BulkBackfillUseCase {
        val unusedEngine = MockEngine { respondError(HttpStatusCode.NotFound) }
        return BulkBackfillUseCase(
            metadataProvider = provider,
            isbnCoverProbe = OpenLibraryIsbnCoverProbe(createHttpClient(unusedEngine), OpenLibraryCoverRateLimiter()),
            coverDownloader = CoverImageDownloader(createHttpClient(unusedEngine)),
            imageStorage = LocalImageStorageManager("unused"),
            bookRepository = BookRepository(db),
            settingsRepository = SettingsRepository(db.appSettingsDao()),
        )
    }

    private fun newViewModel(
        provider: BookMetadataProvider,
        logger: Logger = AppLogger,
    ) = viewModels.track(BackfillViewModel(newUseCase(provider), logger = logger))

    /**
     * Repeatedly checks [condition], yielding real (non-virtual) time between attempts so work
     * dispatched to Room's own query/invalidation executor (entirely outside this test's virtual
     * scheduler) gets a chance to run and post its continuation back onto the (Unconfined) Main
     * dispatcher -- same technique and same reasoning as
     * [BookDetailViewModelTest.runCurrentUntilOrTimeOut]. Bounded to 5 real seconds (generous
     * because a real Room round trip -- as opposed to the virtual-time DAO tests elsewhere in this
     * module -- can be genuinely slow under a loaded/sandboxed CI host) so a genuine regression
     * fails loudly with a clear diagnostic instead of silently asserting against stale state or
     * hanging forever.
     */
    private suspend fun TestScope.waitUntilOrTimeOut(
        maxAttempts: Int = 1000,
        condition: () -> Boolean,
    ) {
        var attempts = 0
        while (attempts < maxAttempts) {
            if (condition()) return
            withContext(Dispatchers.Default) { delay(5) }
            attempts++
        }
        error("waitUntilOrTimeOut timed out after ${maxAttempts * 5}ms real time waiting for condition to become true")
    }

    // ---- Finding 1: a DB failure mid-backfill must not crash the coroutine or strand uiState ---

    @Test
    fun start_dbFailureMidBackfill_doesNotStrandUiStateAtRunning() =
        runTest {
            insertBook()
            val provider =
                GatedMetadataProvider(mapOf("9780547928227" to metadata(listOf("Author"))), gatedIsbns = emptySet())
            val viewModel = newViewModel(provider)

            // Drop the table BulkBackfillUseCase.execute reads first (settingsRepository.getBulkBackfillState(),
            // the very first line of execute()) out from under the ViewModel before starting, so every
            // read against it fails with a genuine androidx.sqlite.SQLiteException -- standing in for
            // finding 1's "a DB failure mid-backfill propagates out as a raw exception" scenario.
            //
            // Deliberately NOT db.close() (as this test used to do): verified empirically (PR review
            // round 3) that closing the whole Room/bundled-SQLite connection pool out from under an
            // in-flight coroutine surfaces as a kotlinx.coroutines CancellationException (the bundled
            // driver's connection pool is itself coroutine-backed, and closing it cancels that internal
            // Job) rather than a plain database exception. That CancellationException is legitimately
            // caught by start()'s own `catch (e: CancellationException)` branch above the generic
            // `catch (_: Exception)` -- so a db.close()-based test exercises settleOutOfRunning() (Idle),
            // never settleAsFailed() (Failed), regardless of whether settleAsFailed() is even reachable.
            // Dropping just this one table keeps the connection pool itself alive and healthy, so the
            // failure surfaces as an ordinary SQLiteException through the normal catch(Exception) path.
            db.useWriterConnection { connection -> connection.execSQL("DROP TABLE app_settings") }

            viewModel.start()
            waitUntilOrTimeOut { viewModel.uiState.value !is BackfillUiState.Running }

            // Nothing was ever checkpointed this run (the very first DB read failed), so there is no
            // progress to show -- Failed(null) is the correct settle point per settleAsFailed()'s KDoc:
            // a genuine failure must stay distinguishable from Idle/a clean stop, not collapse into it.
            // Without settleAsFailed() (PR review round 2's finding-3 fix) this assertion fails: the old
            // behavior settled on the same Stopped/Idle state cancellation does, making a genuine DB
            // failure indistinguishable from the user pressing cancel.
            assertEquals(
                BackfillUiState.Failed(progress = null),
                viewModel.uiState.value,
                "a DB failure must settle on Failed, not silently collapse into Idle/Stopped",
            )
        }

    // ---- ROADMAP Task 15: the same mid-backfill failure above must now be logged ----

    /**
     * Same forced failure as [start_dbFailureMidBackfill_doesNotStrandUiStateAtRunning] (dropping
     * `app_settings` out from under `BulkBackfillUseCase.execute`'s first read), but asserting on
     * [RecordingLogger] instead of [BackfillUiState]: before ROADMAP Task 15, this exact exception
     * was caught and discarded with a `catch (_: Exception)` -- nothing anywhere recorded that it had
     * even happened. It must now be logged at ERROR under this ViewModel's own tag, and the logged
     * message/cause must never contain the book's title -- the identifier rule
     * ([com.hub.media.core.util.Logger]'s KDoc) this ViewModel has no reason to violate, since the
     * catch site never has a title in scope at all.
     */
    @Test
    fun start_dbFailureMidBackfill_logsErrorWithoutBookContent() =
        runTest {
            val title = "A Title That Must Never Reach The Log"
            insertBook(title = title)
            val provider =
                GatedMetadataProvider(mapOf("9780547928227" to metadata(listOf("Author"))), gatedIsbns = emptySet())
            val recorder = RecordingLogger()
            val viewModel = newViewModel(provider, logger = recorder)

            db.useWriterConnection { connection -> connection.execSQL("DROP TABLE app_settings") }

            viewModel.start()
            waitUntilOrTimeOut { viewModel.uiState.value !is BackfillUiState.Running }

            assertIs<BackfillUiState.Failed>(viewModel.uiState.value)
            val errorEntries = recorder.entries.filter { it.level == LogLevel.ERROR }
            assertTrue(errorEntries.isNotEmpty(), "a mid-backfill DB failure must be logged at ERROR")
            assertTrue(errorEntries.all { it.tag == "BackfillViewModel" })
            assertTrue(
                errorEntries.all {
                    it.throwable != null
                },
                "the underlying exception must be attached, not just a bare message",
            )
            assertTrue(
                errorEntries.none { it.message.contains(title) },
                "the logged message must never contain book content (the identifier rule)",
            )
        }

    @Test
    fun init_whenReadingPersistedProgressFails_logsInsteadOfCrashingTheScope() =
        runTest {
            // Drops the table *before* constructing the ViewModel, so init's peekProgress() is
            // guaranteed to fail rather than racing the drop. That race is how this was found:
            // start_dbFailureMidBackfill below drops the table after construction, so whether init
            // resumed before or after decided the outcome, and CI failed intermittently with a raw
            // SQLiteException escaping viewModelScope. On a device that escape is a crash when Settings
            // opens -- from a read whose only job is to restore a progress bar.
            db.useWriterConnection { connection -> connection.execSQL("DROP TABLE app_settings") }
            val recorder = RecordingLogger()

            newViewModel(GatedMetadataProvider(emptyMap(), gatedIsbns = emptySet()), logger = recorder)
            waitUntilOrTimeOut { recorder.entries.any { it.level == LogLevel.ERROR } }

            val errors = recorder.entries.filter { it.level == LogLevel.ERROR }
            assertTrue(errors.isNotEmpty(), "a failed progress read must be logged, not discarded")
            assertTrue(errors.all { it.tag == "BackfillViewModel" })
            assertTrue(errors.all { it.throwable != null }, "the cause must be attached, not just a message")
        }

    // ---- Finding 3: init's late peekProgress() must not clobber a start() already in flight ----

    @Test
    fun init_lateProgressSnapshot_doesNotClobberAnAlreadyStartedRun() =
        runTest {
            val isbn = "9780547928227"
            val mediaId = insertBook(isbn = isbn)
            // Pre-seed resume state, standing in for a previous session's checkpoint -- this is what
            // gives init's peekProgress() something to (wrongly, pre-fix) apply.
            settingsRepository.saveBulkBackfillState(
                BulkBackfillState(
                    pendingMediaIds = listOf(mediaId),
                    totalCandidates = 1,
                    noIsbnSkipped = 0,
                    updated = 0,
                    noProviderData = 0,
                ),
            )
            val provider = GatedMetadataProvider(mapOf(isbn to metadata(listOf("Author"))), gatedIsbns = setOf(isbn))

            // Construction launches init's viewModelScope.launch, which calls peekProgress() -- a real
            // suspend Room read that has not resolved by the time this constructor call returns (Room
            // dispatches it to its own executor thread, genuinely asynchronously).
            val viewModel = viewModels.track(BackfillViewModel(newUseCase(provider)))

            // start() runs synchronously up to its first suspension point (metadataProvider.fetchByIsbn,
            // gated forever below), so this assignment -- and the Running state it produces -- happens
            // deterministically before init's peekProgress() coroutine has had any chance to resume.
            viewModel.start()
            assertIs<BackfillUiState.Running>(viewModel.uiState.value)

            // Deterministic completion signal for init's peekProgress() Room read, instead of a fixed
            // ~200ms real-time delay (PR review round 2 finding 4: a fixed delay can assert *before*
            // Room's async snapshot read resolves, so this test could pass whether or not the guard it's
            // meant to cover is even present -- worse than no test, because it looks like coverage). A
            // second, independent BackfillViewModel constructed fresh over this exact same pre-seeded,
            // still-untouched DB state (the first viewModel's run is frozen on provider's gate and has
            // written nothing yet) runs the identical peekProgress() read that the first viewModel's
            // init already issued. Waiting for *that* witness's uiState to resolve to Stopped proves a
            // peekProgress() read of this shape has gone all the way through Room's async executor and
            // back onto Main by this point -- exactly the condition that needed to hold for the
            // original (pre-fix) init to have had its chance to clobber uiState.
            val witness = viewModels.track(BackfillViewModel(newUseCase(provider)))
            waitUntilOrTimeOut { witness.uiState.value is BackfillUiState.Stopped }

            // Without finding 3's fix this assertion fails: init's peekProgress() (once its Room read
            // resolves) unconditionally overwrites uiState with Stopped(preSeededProgress), clobbering
            // the run that start() already put in flight.
            assertIs<BackfillUiState.Running>(
                viewModel.uiState.value,
                "a late init snapshot must never clobber a run already in flight",
            )

            provider.release(isbn) // let the frozen coroutine finish so tearDown's clearAll() doesn't hang
        }

    // ---- Finding 4: cancellation must always leave Running, using the last progress if any ----

    @Test
    fun cancel_beforeAnyProgressReported_settlesOnIdleNotStuckRunning() =
        runTest {
            val isbn = "9780547928227"
            insertBook(isbn = isbn)
            val provider = GatedMetadataProvider(mapOf(isbn to metadata(listOf("Author"))), gatedIsbns = setOf(isbn))
            val viewModel = newViewModel(provider)

            viewModel.start()
            assertEquals(
                BackfillUiState.Running(progress = null),
                viewModel.uiState.value,
                "no book has been checkpointed yet",
            )

            viewModel.cancel()
            waitUntilOrTimeOut { viewModel.uiState.value !is BackfillUiState.Running }

            // Without finding 4's fix, `(_uiState.value as? Running)?.progress?.let { ... }` on a null
            // progress does nothing, so uiState stays Running(null) forever after cancellation.
            assertEquals(
                BackfillUiState.Idle,
                viewModel.uiState.value,
                "cancelling before any progress must settle on Idle, not stay stuck Running",
            )
        }

    @Test
    fun cancel_afterProgressReported_settlesOnStoppedWithLastProgress() =
        runTest {
            val isbnA = "9780547928227"
            val isbnB = "9780140449136"
            // Explicit, alphabetically-ordered titles: seedState() seeds pendingMediaIds in title order
            // (see insertBook's KDoc), and this test's freeze point depends on book A being processed
            // strictly before gated book B.
            insertBook(isbn = isbnA, title = "AAA - resolves immediately")
            insertBook(isbn = isbnB, title = "BBB - gated forever")
            // isbnA resolves immediately; isbnB is gated forever, so this run's second book freezes
            // execute() right after the first book's progress has been checkpointed and reported.
            val provider =
                GatedMetadataProvider(
                    mapOf(isbnA to metadata(listOf("Author A")), isbnB to metadata(listOf("Author B"))),
                    gatedIsbns = setOf(isbnB),
                )
            val viewModel = newViewModel(provider)

            viewModel.start()
            waitUntilOrTimeOut { (viewModel.uiState.value as? BackfillUiState.Running)?.progress?.processed == 1 }
            val runningProgress = (viewModel.uiState.value as BackfillUiState.Running).progress
            assertEquals(1, runningProgress?.processed)

            viewModel.cancel()
            waitUntilOrTimeOut { viewModel.uiState.value !is BackfillUiState.Running }

            // Without finding 4's fix this test would still pass by accident for THIS specific case
            // (progress is non-null, so the pre-fix `?.let` does fire) -- it's
            // cancel_beforeAnyProgressReported_settlesOnIdleNotStuckRunning above that actually exposes
            // the bug. This test instead guards the "prefer last progress" half of the contract: the
            // Stopped state must carry the real snapshot, not just any non-Running state.
            val finalState = viewModel.uiState.value
            assertIs<BackfillUiState.Stopped>(finalState)
            assertEquals(1, finalState.progress.processed)
            assertEquals(runningProgress, finalState.progress)
        }
}
