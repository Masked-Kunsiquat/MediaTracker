package com.hub.media.core.util

/**
 * Minimal KMP logging facility (ROADMAP Task 15). Lives under `core/util` (AGENTS.md §6 blueprint:
 * "Dispatchers, Extensions, Result wrappers") alongside [Resource] and [newId] rather than a new
 * `core/logging/` package -- like those two, this is a small, cross-cutting typed utility every
 * layer of `shared/` may need, not a domain concept with its own DAOs/network calls/entities the
 * way `core/database`/`core/network`/`core/storage` are.
 *
 * ### Why hand-rolled, not Napier/Kermit
 * AGENTS.md §5 rules out a new third-party dependency without explicit project-context approval.
 * This whole facility -- an interface, four levels, one platform-routing function, and a
 * verbosity gate -- is a few dozen lines; neither Napier nor Kermit would buy this project anything
 * it cannot already do with plain `android.util.Log`/stdout, so no proposal to add one is made here.
 *
 * ### Shape: interface + platform factory, not `expect class`
 * [DatabaseFactory][com.hub.media.core.database.DatabaseFactory] is an `expect class` because each
 * platform constructor needs different captured state (a `Context` on Android, nothing on JVM).
 * Logging needs no per-instance platform state at all -- `android.util.Log`'s functions are static,
 * and JVM logging is just `println` -- so the lighter shape this task's own brief calls out as an
 * alternative applies instead: a plain [Logger] interface in `commonMain`, with [platformLogger] (see
 * `PlatformLogger.kt`) as the `expect`/`actual` seam that picks the real per-platform sink. This is
 * also what makes [RecordingLogger] (`commonTest`) trivial: it's just another [Logger], no fake
 * platform state to stand up.
 *
 * ### Verbosity control -- gated centrally, not per platform (see [AppLogger])
 * `BuildConfig.DEBUG` lives in the `app` module and is not visible from `shared/`, so the release/
 * debug decision cannot be made inside this file. [AppLogger] is the single place that decision is
 * applied: it wraps a [platformLogger] delegate with a minimum [LogLevel] threshold, configured
 * exactly once at process start (`MediaTrackerApplication.onCreate`, the one platform entry point
 * that *does* see `BuildConfig.DEBUG`) and defaulting to [LogLevel.WARN] until that happens --
 * see [AppLogger]'s KDoc for what that means for a release build specifically.
 *
 * ### Privacy rule -- enforced by convention at every call site, not by this file
 * This app is local-first with no cloud (AGENTS.md §1): nothing in this facility ever leaves the
 * device (no crash-reporting/analytics SDK sits behind [Logger] or ever will -- see [AppLogger]'s
 * KDoc), and the same restraint applies to *what* gets logged, not just where it goes.
 *
 * **The identifier rule this whole codebase's adoption sites follow:**
 * - Fine to log: a `mediaId` (an opaque UUID, meaningless outside this device's own database) or an
 *   ISBN (a book *edition* identifier, not personal content -- the same reasoning that already lets
 *   [com.hub.media.features.books.network.OpenLibraryIsbnCoverProbe] pass one to a third-party HTTP
 *   request). Exception type/class name, HTTP status codes, schema version numbers, and file paths
 *   already shown to the user in-app (e.g. a restore failure's backup path) are likewise fine.
 * - Never fine to log: a book/media title, an author name, reading-session notes, or any other field
 *   that is the user's actual library content -- log **what failed and why**, never **what the user
 *   is reading**. A [Throwable]'s own `message`/stack trace is logged as-is (via the `throwable`
 *   parameter every [log] call accepts) since exception text from this codebase's own network/DB/
 *   file-I/O layers never embeds book content -- but a call site must never *additionally*
 *   interpolate a title/author/note into the lazy `message` string alongside it.
 */
public enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * A single, tag-scoped logging sink. [message] is a lambda (not a plain `String`) specifically so a
 * suppressed call -- one below the effective [LogLevel] threshold (see [AppLogger]) -- costs nothing
 * beyond the lambda allocation itself: string concatenation/interpolation in the caller's message
 * never runs unless the call is actually going to be emitted.
 *
 * Implementations: [AppLogger] (the production entry point every adoption site should default to),
 * the platform-specific [platformLogger] results it delegates to (`android.util.Log` / stdout), and
 * [RecordingLogger] (`commonTest`) for assertions.
 */
public interface Logger {
    /**
     * Logs [message] at [level] under [tag], optionally attaching [throwable]. See [Logger]'s KDoc
     * for the identifier rule [message] must follow.
     */
    public fun log(level: LogLevel, tag: String, throwable: Throwable? = null, message: () -> String)
}

/** [Logger.log] at [LogLevel.DEBUG]. See [Logger.log]. */
public fun Logger.debug(tag: String, throwable: Throwable? = null, message: () -> String): Unit =
    log(LogLevel.DEBUG, tag, throwable, message)

/** [Logger.log] at [LogLevel.INFO]. See [Logger.log]. */
public fun Logger.info(tag: String, throwable: Throwable? = null, message: () -> String): Unit =
    log(LogLevel.INFO, tag, throwable, message)

/** [Logger.log] at [LogLevel.WARN]. See [Logger.log]. */
public fun Logger.warn(tag: String, throwable: Throwable? = null, message: () -> String): Unit =
    log(LogLevel.WARN, tag, throwable, message)

/** [Logger.log] at [LogLevel.ERROR]. See [Logger.log]. */
public fun Logger.error(tag: String, throwable: Throwable? = null, message: () -> String): Unit =
    log(LogLevel.ERROR, tag, throwable, message)

/**
 * The production [Logger] every adoption site in `shared/` defaults to (mirroring how
 * [com.hub.media.features.books.network.OpenLibraryIsbnCoverProbe] already defaults its `clock`
 * parameter to [kotlin.time.Clock.System] -- a real default, overridable by tests). Delegates to
 * [platformLogger] (`android.util.Log` on Android, stdout/stderr on JVM), gated by [minLevel].
 *
 * ### Default threshold: [LogLevel.WARN] until [configure] is called
 * This is the release-safe default: if [configure] is somehow never called on a given process (a
 * missing wiring bug, or a `shared/`-only host such as `:shared:jvmTest` that has no application
 * entry point at all), only [LogLevel.WARN]/[LogLevel.ERROR] ever emit -- never the chattier
 * [LogLevel.DEBUG]/[LogLevel.INFO] levels a debug build opts into. [MediaTrackerApplication][
 * com.github.maskedkunisquat.mediatracker.MediaTrackerApplication]`.onCreate` is the one place that
 * calls [configure] in production, using `BuildConfig.DEBUG` (visible there, not in `shared/`) to
 * pick [LogLevel.DEBUG] for a debug build or reaffirm [LogLevel.WARN] for a release build.
 *
 * ### What a release build emits (and does not)
 * A release build's `BuildConfig.DEBUG` is `false`, so [minLevel] is (explicitly) [LogLevel.WARN]:
 * only [LogLevel.WARN]/[LogLevel.ERROR] calls ever reach `android.util.Log` (i.e. logcat) --
 * [LogLevel.DEBUG]/[LogLevel.INFO] calls are filtered out *before* their lazy `message` lambda is
 * even invoked (see [log]), not merely hidden after being formatted. At every level, the identifier
 * rule in [Logger]'s KDoc still applies -- release or debug, no adoption site ever logs library
 * content, so raising the threshold is a volume control, not the privacy mechanism itself.
 *
 * ### No crash-reporting service, ever
 * [platformLogger]'s two implementations are the only things this object ever writes to, and both
 * are purely local (logcat / stdout) -- neither transmits anything off-device. This is a deliberate,
 * permanent choice, not a gap to fill later: AGENTS.md §1's local-first/no-cloud premise means a
 * crash-reporting SDK (Firebase Crashlytics, Sentry, etc.) is explicitly out of scope for this app,
 * since it would ship exception context -- and via it, tag/message content -- off the user's device.
 */
public object AppLogger : Logger {
    private var minLevel: LogLevel = LogLevel.WARN
    private var delegate: Logger = platformLogger()

    /**
     * Sets the effective verbosity threshold (and, for tests, swaps the underlying sink). Intended
     * to be called exactly once per process, as early as possible -- see this object's KDoc for the
     * one production call site. Calling it again (as tests do, to install a [RecordingLogger]) simply
     * replaces the previous configuration; there is no stacking/nesting.
     *
     * @param minLevel The minimum [LogLevel] that will actually reach [delegate]; anything below it
     *   is dropped without evaluating its message lambda.
     * @param delegate Where accepted log calls are routed. Defaults to a fresh [platformLogger] (the
     *   real android.util.Log/stdout sink) -- tests override this with a [RecordingLogger].
     */
    public fun configure(minLevel: LogLevel, delegate: Logger = platformLogger()) {
        this.minLevel = minLevel
        this.delegate = delegate
    }

    override fun log(level: LogLevel, tag: String, throwable: Throwable?, message: () -> String) {
        if (level < minLevel) return
        delegate.log(level, tag, throwable, message)
    }
}
