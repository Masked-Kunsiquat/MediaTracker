package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.books.domain.AddBookByIsbnUseCase
import com.hub.media.features.books.domain.createDefaultAddBookByIsbnUseCase

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
 * @param database The production (or test) [AppDatabase] instance. Callers own its lifecycle.
 * @param imageStorage Content-addressed cover image storage rooted at a platform-appropriate
 *   directory (see the platform `coverStorageDirectory` helpers).
 */
public class AppContainer(
    database: AppDatabase,
    imageStorage: LocalImageStorageManager,
) {
    private val httpClient = createHttpClient()

    /** Reactive book CRUD, shared by [LibraryViewModel] and future book-detail screens. */
    public val bookRepository: BookRepository = BookRepository(database)

    /** Reading session logging/history, shared by future reading-session screens. */
    public val readingSessionRepository: ReadingSessionRepository = ReadingSessionRepository(database)

    /** End-to-end ISBN ingestion, consumed by [AddBookViewModel]. */
    public val addBookByIsbnUseCase: AddBookByIsbnUseCase = createDefaultAddBookByIsbnUseCase(
        httpClient = httpClient,
        imageStorage = imageStorage,
        bookRepository = bookRepository,
    )
}
