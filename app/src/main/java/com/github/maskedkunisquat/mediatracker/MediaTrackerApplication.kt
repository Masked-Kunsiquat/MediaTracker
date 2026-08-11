package com.github.maskedkunisquat.mediatracker

import android.app.Application
import com.hub.media.core.storage.FileLogSink
import com.hub.media.core.storage.LogFileStore
import com.hub.media.core.storage.createLogFileStore
import com.hub.media.core.storage.logStorageDirectory
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.info
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.withPlatformLogger
import com.hub.media.features.settings.data.observeLogVerbosityOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.hub.media.ui.AppContainer
import com.hub.media.ui.createAppContainer
import kotlinx.coroutines.runBlocking


/** Log tag for this class's lifecycle tracing (ROADMAP Task 15 Phase C). */
private const val TAG = "MediaTrackerApplication"

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
     * ROADMAP Task 15 Phase B: the persistent, capped log-file store, built directly from this
     * `Application`'s own `Context` -- deliberately **not** read off [appContainer] -- so it
     * exists before [onCreate] configures [AppLogger]. See [onCreate]'s KDoc for the ordering
     * hazard this sidesteps. [appContainer] is later constructed with this exact same instance
     * (see [createAppContainer]'s `logFileStore` parameter) rather than building a second,
     * independent [LogFileStore]: two stores pointed at the same directory would each keep their
     * own in-memory sequence counter and buffer, breaking the single-writer assumption
     * [LogFileStore]'s KDoc documents.
     */
    private lateinit var logFileStore: LogFileStore

    /**
     * ROADMAP Task 15: the one platform entry point that configures [AppLogger]'s verbosity
     * threshold, since `BuildConfig.DEBUG` is generated per-module and is not visible from
     * `shared/` (see [AppLogger]'s KDoc). Runs before [appContainer] can possibly be constructed --
     * `Application.onCreate()` is guaranteed by the framework to run before any other app
     * component (including the first `Activity`, which is what actually triggers
     * [appContainer]'s lazy initialization via [MainActivity]) -- so every log call issued for the
     * rest of this process's lifetime is already governed by the right threshold. A debug build
     * gets everything down to [LogLevel.DEBUG]; a release build gets [LogLevel.INFO].
     *
     * Release was [LogLevel.WARN] until ROADMAP Task 15 Phase C, chosen when nothing in the
     * codebase emitted below WARN -- so a healthy app wrote nothing and an empty log viewer read as
     * a broken feature. Now that lifecycle tracing exists, INFO is what gives a later failure the
     * context that led to it. Stated explicitly either way rather than relying on [AppLogger]'s own
     * pre-[AppLogger.configure] default, so the release behavior is a deliberate statement and not
     * an accident of never having called [AppLogger.configure] at all.
     *
     * ### Phase B: always-on logging, and the [logFileStore] construction this now does here
     * ROADMAP Task 15 Phase B makes logging "always-on (not debug-build-gated)". That is about
     * *what gets captured*, not the [minLevel] threshold computed below: a release build still
     * only emits [LogLevel.WARN]/[LogLevel.ERROR] (the verbosity gate from Phase A is unchanged
     * and deliberately out of this phase's scope -- the user-adjustable verbosity setting is
     * Phase B2's). What's new is *where* those WARN/ERROR calls go: before this phase, a release
     * build's logs only ever reached logcat, which a normal user cannot read; after it, every
     * accepted call is additionally captured to [logFileStore] via [FileLogSink] regardless of
     * build type, so a user can retrieve their own diagnostic history without a debugger attached.
     * [logStorageDirectory] and [createLogFileStore] are both unconditional here -- there is no
     * debug/release branch around them at all, which *is* the "always-on" part.
     *
     * Building [logFileStore] requires a directory scan (see [createLogFileStore]'s KDoc: reading
     * at most two ~1MB files to find the highest retained sequence number) that is normally
     * suspend, but it must complete, synchronously, before [AppLogger.configure] is called a few
     * lines below -- otherwise the very first log calls of this process (including any from
     * [createAppContainer] itself, once [appContainer] is later accessed) would race the store's
     * own initialization. [runBlocking] here is deliberate, mirroring [createAppContainer]'s own
     * use of it for [com.hub.media.core.database.selfHealDatabaseIfNeeded]/
     * [com.hub.media.core.database.consumeRestoreMarker] -- but unlike that call (which only runs
     * lazily, on first [appContainer] access, typically triggered by `MainActivity` shortly after
     * this method returns), this one runs unconditionally *inside* [onCreate] itself, adding a
     * small, bounded amount of synchronous I/O directly to process startup latency on every launch.
     * This is a deliberate scope trade-off: the alternative (deferring store construction until
     * [appContainer] is first accessed) would reopen exactly the ordering hazard this whole
     * section exists to avoid -- [AppLogger] would be configured with a delegate that does not yet
     * include the file sink, silently losing every log call issued between [onCreate] and that
     * first access. Flagged for review rather than left implicit; the measured cost on real
     * hardware (bounded by two ~1MB reads, effectively instant on any device this app targets)
     * has not been benchmarked as part of this change.
     */
    // createLogFileStore defaults its `clock` parameter to kotlin.time.Clock.System, still
    // experimental in Kotlin 2.2.x. `shared` opts in module-wide (shared/build.gradle.kts); the
    // app module does not, so the opt-in is stated per call site here exactly as
    // ViewModelFactories.kt already does for the same API.
    @OptIn(kotlin.time.ExperimentalTime::class)
    override fun onCreate() {
        super.onCreate()
        logFileStore = runBlocking { createLogFileStore(logStorageDirectory(applicationContext)) }
        // Release bootstraps at INFO since ROADMAP Task 15 Phase C, not WARN. DEFAULT_LOG_VERBOSITY
        // alone would not have changed anything on a fresh install: the never-set case resolves to
        // this build-type default, not to the setting's default (see observeLogVerbosityOrNull).
        val buildTypeDefault = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO
        AppLogger.configure(
            minLevel = buildTypeDefault,
            delegate = FileLogSink(logFileStore).withPlatformLogger(),
        )
        applyPersistedLogVerbosity(buildTypeDefault)
        // The first entry in every session's log, and the anchor everything after it is read
        // against: which build was running when whatever comes next went wrong. Emitted after
        // configure() so it goes through the real sink rather than being dropped by the
        // pre-configure threshold.
        AppLogger.info(TAG) { "App started (version ${BuildConfig.VERSION_NAME})" }
    }

    /**
     * Keeps [AppLogger]'s threshold in sync with the user's persisted verbosity preference for the
     * lifetime of the process (ROADMAP Task 15 Phase B2).
     *
     * ### Why `persisted ?: buildTypeDefault`, and not just `persisted`
     * A never-set preference must leave [buildTypeDefault] in place rather than replace it with the
     * setting's own default. Overwriting it would make every debug build fall silent moments after
     * startup -- dropping the `DEBUG`/`INFO` output a debug build exists to produce, purely because
     * nobody had opened Settings -- and would render the `BuildConfig.DEBUG` branch above dead code
     * in the one build type it was written for. An explicit choice still always wins; see
     * [AppLogger.setMinLevel]'s KDoc.
     *
     * ### Why here, and not in `SettingsViewModel`
     * The threshold has to hold process-wide, including long after the settings screen is gone, so
     * a ViewModel scoped to that screen is the wrong owner. This scope is deliberately never
     * cancelled: it is tied to the `Application` itself, which lives exactly as long as the process
     * whose logging it governs.
     *
     * Reading the preference touches Room, so it cannot happen during the synchronous
     * [AppLogger.configure] call above -- hence the two-stage bootstrap, with [buildTypeDefault]
     * governing the brief window before the first emission lands. `appContainer` is accessed lazily
     * inside the coroutine rather than at call time so this does not force database construction
     * onto the `onCreate` critical path.
     */
    private fun applyPersistedLogVerbosity(buildTypeDefault: LogLevel) {
        loggingScope.launch {
            appContainer.settingsRepository.observeLogVerbosityOrNull().collectLatest { persisted ->
                AppLogger.setMinLevel(persisted ?: buildTypeDefault)
            }
        }
    }

    /**
     * Process-lifetime scope for [applyPersistedLogVerbosity]'s collection. A [SupervisorJob] so a
     * failure there could never take anything else down with it, on [Dispatchers.Default] since the
     * work is a Room-backed `Flow`, never UI.
     */
    private val loggingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Lazily-created AppContainer, initialized on first access.
     * Contains [com.hub.media.features.books.data.BookRepository],
     * [com.hub.media.features.books.data.ReadingSessionRepository], and
     * [com.hub.media.features.books.domain.AddBookByIsbnUseCase].
     */
    val appContainer: AppContainer by lazy {
        createAppContainer(this, logFileStore)
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
