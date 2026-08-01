package com.github.maskedkunisquat.mediatracker

import android.app.Application
import com.hub.media.ui.AppContainer
import com.hub.media.ui.createAppContainer

/**
 * Application class holding a lazily-created [AppContainer].
 *
 * The container is initialized on first access and shared across the entire application
 * (single process, single app container instance). This ensures a single database connection
 * and a single HTTP client throughout the app's lifetime.
 *
 * Read by [MainActivity] via `(application as MediaTrackerApplication).appContainer`.
 */
class MediaTrackerApplication : Application() {

    /**
     * Lazily-created AppContainer, initialized on first access.
     * Contains [com.hub.media.features.books.data.BookRepository],
     * [com.hub.media.features.books.data.ReadingSessionRepository], and
     * [com.hub.media.features.books.domain.AddBookByIsbnUseCase].
     */
    val appContainer: AppContainer by lazy {
        createAppContainer(this)
    }
}
