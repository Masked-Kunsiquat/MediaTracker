package com.hub.media.core.util

import kotlin.concurrent.Volatile

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
 * ROADMAP Task 15 Phase B2 adds a second, user-adjustable source for this same threshold -- the
 * persisted log-verbosity setting (see
 * [com.hub.media.features.settings.data.observeLogVerbosity]) -- applied via [AppLogger.setMinLevel]
 * rather than a separate filter, since a level [AppLogger] already dropped never reaches any sink
 * for that filter to see. [AppLogger.setMinLevel]'s KDoc spells out exactly which of the two
 * (build-type default vs. persisted setting) wins and why.
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
 *   is reading**. A call site must never interpolate a title/author/note into the lazy `message`
 *   string.
 *
 * **The `throwable` parameter is only as safe as the URLs the failing layer builds, and that is a
 * per-call-site judgement rather than a blanket permission.** This rule used to say a [Throwable]'s
 * `message`/stack trace could always be passed through, "since exception text from this codebase's
 * own network/DB/file-I/O layers never embeds book content". That was true for as long as every URL
 * the app constructed was keyed by ISBN. ROADMAP Task 9 Phase B1 broke it: a *search* query is a
 * title or an author name, it travels in the query string, and Ktor embeds the full URL in its
 * exception messages (`Request timeout has expired [url=...?q=the+bell+jar...]`), so passing the
 * exception through would write library content to the log file. Before adding a `throwable`
 * argument at a new site, ask what goes into that layer's URLs, paths or SQL; if any of it is user
 * content, log the exception's type instead --
 * [com.hub.media.features.books.network.OpenLibrarySearchClient] is the worked example.
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
    public fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    )
}

/** [Logger.log] at [LogLevel.DEBUG]. See [Logger.log]. */
public fun Logger.debug(
    tag: String,
    throwable: Throwable? = null,
    message: () -> String,
): Unit = log(LogLevel.DEBUG, tag, throwable, message)

/** [Logger.log] at [LogLevel.INFO]. See [Logger.log]. */
public fun Logger.info(
    tag: String,
    throwable: Throwable? = null,
    message: () -> String,
): Unit = log(LogLevel.INFO, tag, throwable, message)

/** [Logger.log] at [LogLevel.WARN]. See [Logger.log]. */
public fun Logger.warn(
    tag: String,
    throwable: Throwable? = null,
    message: () -> String,
): Unit = log(LogLevel.WARN, tag, throwable, message)

/** [Logger.log] at [LogLevel.ERROR]. See [Logger.log]. */
public fun Logger.error(
    tag: String,
    throwable: Throwable? = null,
    message: () -> String,
): Unit = log(LogLevel.ERROR, tag, throwable, message)

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
    // @Volatile on both: these are written from one thread and read from every other. [setMinLevel]
    // is driven by a Flow collector on a background dispatcher (see MediaTrackerApplication), while
    // [log] is called from arbitrary business-logic threads, so without a visibility guarantee a
    // caller could keep observing a stale threshold indefinitely -- the user changes "Log detail"
    // in Settings and nothing appears to happen. Volatile is sufficient here and a lock is not:
    // both fields are single reference assignments, never a read-modify-write, so there is no
    // compound operation to make atomic. `kotlin.concurrent.Volatile` rather than the JVM
    // annotation, since this is commonMain.
    @Volatile
    private var minLevel: LogLevel = LogLevel.WARN

    @Volatile
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
    public fun configure(
        minLevel: LogLevel,
        delegate: Logger = platformLogger(),
    ) {
        this.minLevel = minLevel
        this.delegate = delegate
    }

    /**
     * Updates only the effective verbosity threshold, leaving [delegate] untouched -- unlike
     * [configure], which always installs a fresh delegate alongside the new level. This is the
     * seam ROADMAP Task 15 Phase B2's user-adjustable verbosity setting is built on.
     *
     * ### The two-stage bootstrap this exists to support
     * `MediaTrackerApplication.onCreate` still makes exactly one [configure] call, synchronously,
     * with the `BuildConfig.DEBUG`-derived level this object's class KDoc describes -- that has to
     * stay a synchronous call, because a [LogLevel] is needed immediately (logging can happen
     * before any suspend function ever runs), while the persisted setting can only be read
     * asynchronously (it lives in Room, behind [com.hub.media.features.settings.data.SettingsRepository]).
     * Once [com.hub.media.features.settings.data.observeLogVerbosity]'s `Flow` delivers its first
     * value -- which happens as soon as collection starts, whether that value is a choice the user
     * actually made or the wrapper's own [LogLevel.WARN] fallback for "never set" -- the app module
     * is expected to call this function on every emission from a process-scoped coroutine, for the
     * lifetime of the process.
     *
     * ### Which wins: an explicit user choice, but only an explicit one
     * The app module wires `persisted ?: buildTypeDefault` -- see
     * [com.hub.media.features.settings.data.observeLogVerbosityOrNull], which deliberately keeps
     * "never chosen" (`null`) distinguishable from "chose [LogLevel.WARN]".
     *
     * An explicit choice always wins, and that is the entire point of Phase B2: picking
     * [LogLevel.DEBUG] on a *release* build to diagnose a problem is something a filter applied at
     * the sink could never deliver, because a release build's bootstrap [LogLevel.WARN] would
     * already have dropped that call here, lambda unevaluated (see [log]), before any sink existed
     * to filter it.
     *
     * No choice, though, must leave the bootstrap alone rather than replace it with the setting's
     * own default. Collapsing the two would mean **every debug build fell silent moments after
     * startup** -- the persisted default ([LogLevel.WARN]) overwriting the debug bootstrap's
     * [LogLevel.DEBUG] and discarding exactly the `DEBUG`/`INFO` output a debug build exists to
     * produce, for the sole reason that nobody had opened a Settings screen. The build-type
     * default would then be dead code in the one build type it was written for.
     *
     * @param minLevel The new effective threshold -- see [configure]'s `minLevel` parameter.
     */
    public fun setMinLevel(minLevel: LogLevel) {
        this.minLevel = minLevel
    }

    override fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: () -> String,
    ) {
        if (level < minLevel) return
        delegate.log(level, tag, throwable, message)
    }
}
