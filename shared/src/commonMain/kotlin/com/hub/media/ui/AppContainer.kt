package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.books.domain.AddBookByIsbnUseCase
import com.hub.media.features.books.domain.LogReadingSessionUseCase
import com.hub.media.features.books.domain.createDefaultAddBookByIsbnUseCase
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
 */
public class AppContainer(
    private val database: AppDatabase,
    imageStorage: LocalImageStorageManager,
) {
    private val httpClient = createHttpClient()

    /** Reactive book CRUD, shared by [LibraryViewModel] and future book-detail screens. */
    public val bookRepository: BookRepository = BookRepository(database)

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
     * Connects timer/manual reading-session results plus position bounds to
     * [readingSessionRepository], consumed by [BookDetailViewModel] (ROADMAP Task 4 Phase B).
     */
    public val logReadingSessionUseCase: LogReadingSessionUseCase =
        LogReadingSessionUseCase(readingSessionRepository)

    /** End-to-end ISBN ingestion, consumed by [AddBookViewModel]. */
    public val addBookByIsbnUseCase: AddBookByIsbnUseCase = createDefaultAddBookByIsbnUseCase(
        httpClient = httpClient,
        imageStorage = imageStorage,
        bookRepository = bookRepository,
    )

    /**
     * Releases resources owned by this container: closes the internally-created
     * [io.ktor.client.HttpClient] and the [database] it was constructed with. Safe to call at
     * most once (per the underlying HttpClient/RoomDatabase close semantics); intended for
     * process-teardown paths where these connections should not be leaked.
     */
    public fun close() {
        httpClient.close()
        database.close()
    }
}
