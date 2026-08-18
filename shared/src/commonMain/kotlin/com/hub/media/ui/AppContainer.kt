package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.RestoreMarker
import com.hub.media.core.network.createHttpClient
import io.ktor.client.HttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.LogFileStore
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.books.domain.AddBookByIsbnUseCase
import com.hub.media.features.books.domain.BulkBackfillUseCase
import com.hub.media.features.books.domain.DeleteBooksUseCase
import com.hub.media.features.books.domain.LogReadingSessionUseCase
import com.hub.media.features.books.domain.RealSearchBooksUseCase
import com.hub.media.features.books.domain.RefetchCoverUseCase
import com.hub.media.features.books.domain.SearchBooksUseCase
import com.hub.media.features.books.domain.createDefaultAddBookByIsbnUseCase
import com.hub.media.features.books.domain.createDefaultBulkBackfillUseCase
import com.hub.media.features.books.domain.createDefaultRefetchCoverUseCase
import com.hub.media.features.books.network.OpenLibraryCoverRateLimiter
import com.hub.media.features.books.network.OpenLibrarySearchClient
import com.hub.media.features.portability.data.ImportWriteRepository
import com.hub.media.features.portability.domain.DatabaseBackupUseCase
import com.hub.media.features.portability.domain.DefaultDatabaseBackupUseCase
import com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase
import com.hub.media.features.portability.domain.ExportDataUseCase
import com.hub.media.features.portability.domain.ImportDataUseCase
import com.hub.media.features.portability.domain.RestoreDatabaseUseCase
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.getGoogleBooksApiKey
import com.hub.media.features.stats.data.StatsRepository

/**
 * Manual composition root for the shared layer (AGENTS.md §5 "No Unnecessary Dependencies" —
 * no DI framework such as Hilt/Koin). Wires a platform-supplied [AppDatabase] and
 * [LocalImageStorageManager] together with an internally-created Ktor [io.ktor.client.HttpClient]
 * into the repositories and use cases that ViewModels depend on.
 *
 * Lives under `ui/` (blueprint §6: "Shared ViewModels & UI Contracts") rather than a new
 * `core/di/` package: this is the composition root *for the UI layer* specifically (it exists
 * only to hand ViewModels their dependencies), it has exactly one consumer package, and adding
 * it here avoids introducing an undocumented top-level package for a single small class.
 *
 * ### Ownership
 * [AppContainer] takes ownership of the resources it is handed for the lifetime of the
 * container: both the internally-created [io.ktor.client.HttpClient] and the [database]
 * passed in are closed together by [close]. This matches the one production caller
 * ([com.hub.media.ui.createAppContainer] on Android), which constructs a fresh [AppDatabase]
 * solely to hand it to this container — the database has no other owner. Call [close] exactly
 * once, when the container itself is being torn down (see `MediaTrackerApplication.onTerminate`
 * for the best-effort Android hook); do not close [database] separately after construction.
 *
 * @param database The production (or test) [AppDatabase] instance. Ownership transfers to this
 *   container; it is closed by [close].
 * @param imageStorage Content-addressed cover image storage rooted at a platform-appropriate
 *   directory (see the platform `coverStorageDirectory` helpers).
 * @param databaseFilePath The live database's on-disk path (from
 *   [com.hub.media.core.database.DatabaseFactory.databaseFilePath]), needed by
 *   [backupDatabaseUseCase]/[restoreDatabaseUseCase] (ROADMAP Task 8 Phase C) to locate the real
 *   file on disk -- everything else in this container only ever talks to [database] through Room.
 * @param pendingRestoreMarker The outcome of a restore attempt that completed just before this
 *   process was (re)launched, if any -- see [com.hub.media.core.database.consumeRestoreMarker]'s
 *   KDoc for why this is surfaced exactly once per process launch rather than read reactively.
 *   `null` on every ordinary launch (the overwhelming majority of launches, including every one
 *   that never involves a restore at all).
 * @param logFileStore The persistent log store (ROADMAP Task 15 Phase B), exposed for the (not
 *   yet built) Phase B2 in-app viewer and export path. **Not constructed by this container** --
 *   unlike [database]/[imageStorage], the caller must build this and hand it in already
 *   initialized. See `MediaTrackerApplication`'s KDoc for why: the store has to exist before
 *   [com.hub.media.core.util.AppLogger.configure] runs, which happens before this container is
 *   even constructed (it is created lazily, on first access, well after
 *   `MediaTrackerApplication.onCreate` returns) -- constructing a second, independent
 *   [LogFileStore] here pointed at the same directory would give two in-memory sequence counters
 *   and buffers racing over one pair of files, which [LogFileStore]'s single-writer design does
 *   not support.
 */
public class AppContainer(
    private val database: AppDatabase,
    imageStorage: LocalImageStorageManager,
    databaseFilePath: String,
    public val logFileStore: LogFileStore,
    public val pendingRestoreMarker: RestoreMarker? = null,
) {
    /**
     * Shared [HttpClient] for all outbound requests. Configured with a [com.hub.media.core.network.USER_AGENT]
     * and timeouts per AGENTS.md §4.
     */
    public val httpClient: HttpClient = createHttpClient()

    /** Reactive book CRUD, shared by [LibraryViewModel] and future book-detail screens. */
    public val bookRepository: BookRepository = BookRepository(database)

    /**
     * Bulk delete with reference-aware cover cleanup (ROADMAP Task 14 Phase B), consumed by
     * [LibraryViewModel]'s selection mode. Needs [imageStorage] as well as the database because
     * deleting a book can leave its content-addressed cover unreferenced -- see that use case's
     * KDoc for why the file cannot simply be deleted alongside the row.
     */
    public val deleteBooksUseCase: DeleteBooksUseCase =
        DeleteBooksUseCase(
            database = database,
            imageStorage = imageStorage,
        )

    /** Reading session logging/history, shared by future reading-session screens. */
    public val readingSessionRepository: ReadingSessionRepository = ReadingSessionRepository(database)

    /**
     * Reactive aggregate reading stats (ROADMAP Task 5), consumed by `StatsViewModel`. The
     * ViewModel factory for the stats screen itself lands in Phase C — this wiring only prepares
     * the repository, matching how [readingSessionRepository]/[bookRepository] are made available
     * ahead of their consuming screens.
     */
    public val statsRepository: StatsRepository = StatsRepository(database)

    /**
     * Typed access to the app-wide `app_settings` key-value store (schema v4, ROADMAP Task 7 Phase
     * A), consumed by `SettingsViewModel` (Phase B, the first concrete setting) and by
     * `StatsViewModel` (which reads the week-start-day preference this repository holds to drive
     * its "this week" bounds — see that class's KDoc).
     */
    public val settingsRepository: SettingsRepository = SettingsRepository(database.appSettingsDao())

    /**
     * Connects timer/manual reading-session results plus position bounds to
     * [readingSessionRepository], consumed by [BookDetailViewModel] (ROADMAP Task 4 Phase B).
     */
    public val logReadingSessionUseCase: LogReadingSessionUseCase =
        LogReadingSessionUseCase(readingSessionRepository)

    /**
     * Shared quota tracker for every ISBN-keyed Open Library cover probe in the app (ROADMAP Task
     * 14 Phase A) -- passed to [addBookByIsbnUseCase], [refetchCoverUseCase], and
     * [bulkBackfillUseCase] alike, since Open Library's 100-requests-per-IP-per-5-minutes cover
     * quota is per *device*, not per call site. A bulk-only limiter would let a user tapping
     * "re-fetch cover" mid-backfill silently push the combined total over the limit while the
     * backfill took the blame -- see [OpenLibraryCoverRateLimiter]'s KDoc.
     */
    private val coverRateLimiter: OpenLibraryCoverRateLimiter = OpenLibraryCoverRateLimiter()

    /**
     * The user-supplied Google Books API key, read fresh from [settingsRepository] on every request
     * that might use one.
     *
     * A function rather than a captured value, and deliberately not cached: this container is built
     * once per process and outlives every visit to the Settings screen, so a key read at
     * construction would be stale the moment the user entered, changed or cleared one -- and the
     * stale-and-cleared case is the bad one, since it would keep sending a credential the user had
     * just deleted. The cost is one indexed key-value read per lookup, alongside a network call that
     * dwarfs it.
     *
     * Handed to every path that talks to Google Books ([addBookByIsbnUseCase],
     * [refetchCoverUseCase], [bulkBackfillUseCase]), for the same reason [coverRateLimiter] is
     * shared: a quota is a property of the device, not of the call site. Not handed to
     * [searchBooksUseCase] -- type-ahead is Open Library only by design (see its KDoc), and Open
     * Library issues no keys.
     */
    private val googleBooksApiKeyProvider: suspend () -> String? = {
        settingsRepository.getGoogleBooksApiKey()
    }

    /** End-to-end ISBN ingestion, consumed by [AddBookViewModel]. */
    public val addBookByIsbnUseCase: AddBookByIsbnUseCase =
        createDefaultAddBookByIsbnUseCase(
            httpClient = httpClient,
            imageStorage = imageStorage,
            bookRepository = bookRepository,
            coverRateLimiter = coverRateLimiter,
            googleBooksApiKeyProvider = googleBooksApiKeyProvider,
        )

    /**
     * Per-book "re-fetch cover" affordance (ROADMAP Task 6 Phase E), consumed by
     * [BookDetailViewModel]. Shares the same Open Library -> Google Books -> ISBN-probe cover
     * chain as [addBookByIsbnUseCase] (see [createDefaultRefetchCoverUseCase]), and the same
     * [coverRateLimiter].
     */
    public val refetchCoverUseCase: RefetchCoverUseCase =
        createDefaultRefetchCoverUseCase(
            httpClient = httpClient,
            imageStorage = imageStorage,
            bookRepository = bookRepository,
            coverRateLimiter = coverRateLimiter,
            googleBooksApiKeyProvider = googleBooksApiKeyProvider,
        )

    /**
     * Edition-level search client backing [searchBooksUseCase]. Exposed separately (in addition to
     * [searchBooksUseCase]) so [AddBookViewModel] can call [com.hub.media.features.books.network.BookSearchProvider.resolveEditionToIsbn]
     * on the result of a search — resolving the work-level search hit to a concrete ISBN
     * (ROADMAP Task 9 Phase B2).
     */
    private val openLibrarySearchClient = OpenLibrarySearchClient(httpClient)

    /**
     * Title/author type-ahead search (ROADMAP Task 9 Phase B1).
     *
     * A single instance on purpose: the LRU inside it is the cache, so a per-screen instance would
     * throw the results away every time the Add Book screen closed and re-request them on the next
     * visit. Open Library only — Google Books is consulted on selection or as a fallback, never
     * per keystroke, since its keyless per-IP quota is limited and 429s have already been observed
     * against it.
     *
     * Not wired through [coverRateLimiter]: that limiter tracks the ISBN-keyed *cover* quota, a
     * different endpoint with a different budget. Search is throttled by the debounce and minimum
     * query length in [SearchBooksUseCase] and its caller instead.
     */
    public val searchBooksUseCase: SearchBooksUseCase =
        RealSearchBooksUseCase(
            openLibrarySearchClient,
        )

    /**
     * Edition-to-ISBN resolver for search result selection (ROADMAP Task 9 Phase B2).
     * Exposed to [AddBookViewModel] so it can resolve a search result's [BookSearchResult.coverEditionKey]
     * to a concrete ISBN, which is then passed to [addBookByIsbnUseCase].
     */
    public val searchProvider: com.hub.media.features.books.network.BookSearchProvider =
        openLibrarySearchClient

    /**
     * Bulk cover-and-author backfill across the whole library (ROADMAP Task 14 Phase A), consumed
     * by `BackfillViewModel` from the Settings screen. Shares [coverRateLimiter] with
     * [addBookByIsbnUseCase]/[refetchCoverUseCase] -- see [coverRateLimiter]'s KDoc -- and
     * [settingsRepository] for its persisted resume state (see
     * [com.hub.media.features.settings.data.BulkBackfillState]).
     */
    public val bulkBackfillUseCase: BulkBackfillUseCase =
        createDefaultBulkBackfillUseCase(
            httpClient = httpClient,
            imageStorage = imageStorage,
            bookRepository = bookRepository,
            settingsRepository = settingsRepository,
            coverRateLimiter = coverRateLimiter,
            googleBooksApiKeyProvider = googleBooksApiKeyProvider,
        )

    /**
     * CSV data-export workflow (ROADMAP Task 8 Phase A), consumed by [ExportViewModel] from the
     * Settings screen. Pure Kotlin -- see [ExportDataUseCase]'s KDoc.
     */
    public val exportDataUseCase: ExportDataUseCase =
        ExportDataUseCase(
            bookRepository = bookRepository,
            readingSessionRepository = readingSessionRepository,
        )

    /**
     * CSV data-import workflow (ROADMAP Task 8 Phase B), consumed by [ImportViewModel] from the
     * Settings screen. [importWriteRepository] wraps the single all-or-nothing write transaction
     * ([com.hub.media.core.database.dao.ImportWriteDao.importAtomically]); [bookRepository]/
     * [readingSessionRepository] are reused for reading the current-library snapshot import needs
     * for duplicate matching, same as [exportDataUseCase] reuses them for reading everything out.
     */
    private val importWriteRepository: ImportWriteRepository = ImportWriteRepository(database)

    public val importDataUseCase: ImportDataUseCase =
        ImportDataUseCase(
            bookRepository = bookRepository,
            readingSessionRepository = readingSessionRepository,
            importWriteRepository = importWriteRepository,
        )

    /**
     * Whole-database `.sqlite` backup workflow (ROADMAP Task 8 Phase C), consumed by
     * [BackupViewModel] from the Settings screen.
     */
    public val backupDatabaseUseCase: DatabaseBackupUseCase =
        DefaultDatabaseBackupUseCase(
            database = database,
            databaseFilePath = databaseFilePath,
        )

    /**
     * Whole-database `.sqlite` restore workflow (ROADMAP Task 8 Phase C), consumed by
     * [RestoreViewModel] from the Settings screen. See [DefaultRestoreDatabaseUseCase]'s KDoc for
     * the full safety sequence -- notably, [close] must be called on this exact [AppContainer]
     * instance before [RestoreDatabaseUseCase.commit] is invoked, and the process must be
     * restarted afterward; neither of those steps can happen from within this container itself,
     * since they're about the container's own lifecycle and the platform process, respectively.
     */
    public val restoreDatabaseUseCase: RestoreDatabaseUseCase =
        DefaultRestoreDatabaseUseCase(
            liveDatabaseFilePath = databaseFilePath,
        )

    /**
     * Releases resources owned by this container: cancels [logFileStore]'s background flush loop
     * (see [LogFileStore.shutdown]), closes the internally-created [io.ktor.client.HttpClient], and
     * closes the [database] it was constructed with. Safe to call at most once (per the underlying
     * HttpClient/RoomDatabase close semantics); intended for process-teardown paths where these
     * connections should not be leaked.
     */
    public fun close() {
        // Isolated deliberately: the log store is the least critical resource here, but it is
        // closed first (so the flush loop stops before the things it might log about go away).
        // Letting a failure from it propagate would skip the HTTP client and the database --
        // leaking a real connection and a real file handle over a diagnostics-only failure, which
        // inverts the priority. Consistent with the rule that logging must never become a new
        // source of failure for its caller (ROADMAP Task 15 Phase A).
        try {
            logFileStore.shutdown()
        } catch (_: Throwable) {
            // Best-effort -- see above.
        }
        httpClient.close()
        database.close()
    }
}
