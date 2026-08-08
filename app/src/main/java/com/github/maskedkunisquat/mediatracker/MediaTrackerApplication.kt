package com.github.maskedkunisquat.mediatracker

import android.app.Application
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.LogLevel
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
     * ROADMAP Task 15: the one platform entry point that configures [AppLogger]'s verbosity
     * threshold, since `BuildConfig.DEBUG` is generated per-module and is not visible from
     * `shared/` (see [AppLogger]'s KDoc). Runs before [appContainer] can possibly be constructed --
     * `Application.onCreate()` is guaranteed by the framework to run before any other app
     * component (including the first `Activity`, which is what actually triggers
     * [appContainer]'s lazy initialization via [MainActivity]) -- so every log call issued for the
     * rest of this process's lifetime is already governed by the right threshold. A debug build
     * gets everything down to [LogLevel.DEBUG]; a release build gets [LogLevel.WARN] explicitly
     * (matching [AppLogger]'s own pre-[AppLogger.configure] default, restated here rather than
     * relied upon implicitly, so the release behavior is a deliberate statement, not an accident
     * of never having called [AppLogger.configure] at all).
     */
    override fun onCreate() {
        super.onCreate()
        AppLogger.configure(minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN)
    }

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
