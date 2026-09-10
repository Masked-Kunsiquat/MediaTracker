package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.RestoreMarker
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.LogFileStore
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.books.domain.AddBookByIsbnUseCase
import com.hub.media.features.books.domain.BulkBackfillUseCase
import com.hub.media.features.books.domain.LogReadingSessionUseCase
import com.hub.media.features.books.domain.RefetchCoverUseCase
import com.hub.media.features.books.domain.ResolveWorkToEditionsUseCase
import com.hub.media.features.books.domain.createDefaultAddBookByIsbnUseCase
import com.hub.media.features.books.domain.createDefaultBulkBackfillUseCase
import com.hub.media.features.books.domain.createDefaultRefetchCoverUseCase
import com.hub.media.features.books.network.BookSearchProvider
import com.hub.media.features.books.network.OpenLibraryCoverRateLimiter
import com.hub.media.features.books.network.OpenLibrarySearchClient
import com.hub.media.features.media.domain.BulkDeleteUseCase
import com.hub.media.features.media.domain.DeleteMediaUseCase
import com.hub.media.features.media.domain.RealSearchMediaUseCase
import com.hub.media.features.media.domain.SearchMediaUseCase
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.portability.data.ImportWriteRepository
import com.hub.media.features.portability.domain.DatabaseBackupUseCase
import com.hub.media.features.portability.domain.DefaultDatabaseBackupUseCase
import com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase
import com.hub.media.features.portability.domain.ExportDataUseCase
import com.hub.media.features.portability.domain.ImportDataUseCase
import com.hub.media.features.portability.domain.RestoreDatabaseUseCase
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.getGoogleBooksApiKey
import com.hub.media.features.settings.data.getTmdbCredential
import com.hub.media.features.stats.data.StatsRepository
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.domain.BackfillShowEpisodesUseCase
import com.hub.media.features.tv.network.TmdbClient
import io.ktor.client.HttpClient

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
    /** Shared [HttpClient] for all outbound requests. */
    public val httpClient: HttpClient = createHttpClient()

    /** Universal media repository (ROADMAP foundation for Task 13). */
    public val mediaRepository: MediaRepository = MediaRepository(database)

    /** Reactive book CRUD, shared by [LibraryViewModel] and future book-detail screens. */
    public val bookRepository: BookRepository = BookRepository(database)

    /** Movie data operations (ROADMAP Task 13 Phase B). */
    public val movieRepository: MovieRepository = MovieRepository(database)

    /** TV show data operations (ROADMAP Task 13 Phase C). */
    public val tvShowRepository: TVShowRepository = TVShowRepository(database)

    /**
     * Bulk delete with reference-aware cover cleanup. Consumed by [LibraryViewModel]'s selection mode.
     */
    public val deleteMediaUseCase: BulkDeleteUseCase =
        DeleteMediaUseCase(
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
     * [searchMediaUseCase] -- type-ahead is Open Library only by design (see its KDoc), and Open
     * Library issues no keys.
     */
    private val googleBooksApiKeyProvider: suspend () -> String? = {
        settingsRepository.getGoogleBooksApiKey()
    }

    /**
     * The user-supplied TMDB credential, read fresh on every request (#75).
     *
     * Every word of [googleBooksApiKeyProvider]'s reasoning applies here unchanged, and one thing
     * does not: a `null` Google Books key degrades to keyless requests, while a `null` here means
     * TMDB cannot be called at all. That makes the stale-value hazard worse rather than better --
     * a captured credential would keep working after the user cleared it, which is precisely the
     * state a restore leaves them in, since backups have credentials scrubbed out of them.
     */
    private val tmdbCredentialProvider: suspend () -> String? = {
        settingsRepository.getTmdbCredential()
    }

    /**
     * TMDB client for films and shows (#75), consumed by the Settings screen's credential check.
     *
     * **Unpaced, deliberately.** Every current caller is interactive and issues a single request, so
     * paying an interval would be latency for nothing -- the same trade [openLibraryIdentifiedPacer]
     * documents. When a bulk backfill arrives it must construct its *own* client with a
     * [com.hub.media.core.network.tmdbPacer], not add one here: a pacer shared between a crawl and a
     * user-facing path lands the crawl's sleeps on a request someone is waiting on, which is the
     * mistake #42 exists to prevent.
     */
    public val tmdbClient: TmdbClient =
        TmdbClient(
            client = httpClient,
            credentialProvider = tmdbCredentialProvider,
        )

    /**
     * Fills provider metadata onto a show's existing episode rows (#75).
     *
     * Built with the *unpaced* [tmdbClient], which is right for the one place it is used from: a
     * refresh a user has asked for on one show is a single request they are waiting on. A
     * library-wide pass would hand in a paced client instead -- see #42 for why that distinction
     * exists at all.
     */
    public val backfillShowEpisodesUseCase: BackfillShowEpisodesUseCase =
        BackfillShowEpisodesUseCase(
            db = database,
            tmdbClient = tmdbClient,
            tvShowRepository = tvShowRepository,
        )

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
            mediaRepository = mediaRepository,
            coverRateLimiter = coverRateLimiter,
            googleBooksApiKeyProvider = googleBooksApiKeyProvider,
        )

    /**
     * Edition-level search client backing [searchMediaUseCase].
     */
    private val openLibrarySearchClient = OpenLibrarySearchClient(httpClient)

    /**
     * Title/author type-ahead search (ROADMAP Task 9 Phase B1).
     */
    public val searchMediaUseCase: SearchMediaUseCase =
        RealSearchMediaUseCase(
            openLibrarySearchClient,
        )

    /**
     * Edition-to-ISBN resolver for search result selection (ROADMAP Task 9 Phase B2).
     *
     * Exposed separately (in addition to [searchMediaUseCase]) so [AddBookViewModel] can resolve a
     * search result's [com.hub.media.features.media.network.MediaSearchResult.coverEditionKey] to a concrete ISBN, which is then passed
     * to [addBookByIsbnUseCase].
     */
    public val searchProvider: BookSearchProvider =
        openLibrarySearchClient

    /**
     * Resolve a work key to its available editions (GitHub Issue #63).
     */
    public val resolveWorkToEditionsUseCase: ResolveWorkToEditionsUseCase =
        ResolveWorkToEditionsUseCase(openLibrarySearchClient)

    /**
     * Bulk cover-and-author backfill across the whole library (ROADMAP Task 14 Phase A), consumed
     * by `BackfillViewModel` from the Settings screen. Shares [coverRateLimiter] with
     * [addBookByIsbnUseCase]/[refetchCoverUseCase] -- see [coverRateLimiter]'s KDoc -- and
     * [settingsRepository] for its persisted resume state (see
     * [com.hub.media.features.settings.data.BulkBackfillState]).
     *
     * Its openlibrary.org metadata requests are additionally paced to the documented
     * identified-traffic rate (#42). That is *not* wired here, deliberately, and the asymmetry with
     * [coverRateLimiter] one line above is the point: a quota is per-IP so every caller has to share
     * one tracker, which only this file can arrange, while a pacer must be exclusive to the crawl --
     * the single-request paths ([addBookByIsbnUseCase]/[refetchCoverUseCase]) are meant to stay
     * unpaced, and [resolveWorkToEditionsUseCase] is unpaced today but should not be (#120, and it
     * would want its own pacer, not this one). Making it an argument here would invite
     * exactly the sharing that would be wrong, so
     * [com.hub.media.features.books.domain.createDefaultBulkBackfillUseCase] defaults it privately.
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
            mediaRepository = mediaRepository,
            bookRepository = bookRepository,
            readingSessionRepository = readingSessionRepository,
        )

    /**
     * CSV data-import workflow (ROADMAP Task 8 Phase B), consumed by [ImportViewModel] from the
     * Settings screen. [importWriteRepository] wraps the single all-or-nothing write transaction
     * ([com.hub.media.core.database.dao.ImportWriteDao.importAtomically]); [mediaRepository]/
     * [bookRepository]/[readingSessionRepository] are reused for reading the current-library
     * snapshot import needs for duplicate matching, same as [exportDataUseCase] reuses them for
     * reading everything out.
     */
    private val importWriteRepository: ImportWriteRepository = ImportWriteRepository(database)

    public val importDataUseCase: ImportDataUseCase =
        ImportDataUseCase(
            mediaRepository = mediaRepository,
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
     * Clears all tables in the database (ROADMAP Task 14 Phase B test isolation).
     * Only intended for use in instrumented tests.
     */
    public suspend fun clearAllData() {
        database.mediaItemDao().deleteAll()
        database.appSettingsDao().deleteAll()
    }

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
