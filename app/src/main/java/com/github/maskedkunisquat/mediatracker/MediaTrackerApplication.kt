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

    /**
     * Best-effort resource cleanup: [onTerminate] is documented by the Android framework as
     * emulator/testing-only and is NOT called on real devices, where the OS simply reclaims the
     * process (there is no reliable "app is shutting down" callback on Android). We still close
     * the container here so the local emulator/dev-run case doesn't leak the HTTP client or
     * database connection; production process teardown relies on the OS, not this method.
     */
    override fun onTerminate() {
        super.onTerminate()
        appContainer.close()
    }
}
