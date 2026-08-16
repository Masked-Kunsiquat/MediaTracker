package com.hub.media.core.util

/**
 * Fan-out [Logger]: forwards every accepted [log] call to each of [delegates] in turn (ROADMAP
 * Task 15 Phase B). [AppLogger] already applies the verbosity threshold *before* calling its
 * configured delegate (see [AppLogger.log]), so a [CompositeLogger] installed as that delegate only
 * ever receives calls that already passed the threshold -- it is purely about fan-out, not a second
 * filtering layer.
 *
 * [message] is evaluated exactly once, up front, and the resulting text handed to every delegate --
 * not re-invoked per delegate. This preserves [Logger.log]'s "costs nothing unless actually
 * emitted" contract (the lambda was already known to be worth evaluating by the time a
 * [CompositeLogger] is reached) while avoiding calling a possibly-expensive message-building lambda
 * multiple times for no reason.
 *
 * Introduced for Phase B's file-store adoption specifically -- `MediaTrackerApplication.onCreate`
 * composes [platformLogger] (logcat/stdout, the sole Phase A sink) with a new
 * [com.hub.media.core.storage.FileLogSink] via [withPlatformLogger] so both receive every entry,
 * rather than the file sink silently replacing the platform one.
 */
public class CompositeLogger(
    private val delegates: List<Logger>,
) : Logger {
    override fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: () -> String,
    ) {
        val text = message()
        for (delegate in delegates) {
            try {
                delegate.log(level, tag, throwable) { text }
            } catch (_: Throwable) {
                // A logging call must never itself become a new source of failure (see
                // PlatformLogger.android.kt's identical rationale) -- one delegate failing (e.g. a
                // disk error inside FileLogSink, though that class already guards itself) must not
                // prevent the others (e.g. the platform sink) from receiving the entry.
            }
        }
    }
}

/**
 * Convenience composing [this] with the real [platformLogger] sink (ROADMAP Task 15 Phase B).
 * [platformLogger] is `internal` to this module -- deliberately encapsulating the
 * `android.util.Log`/stdout choice entirely (see its own KDoc) -- so this is the one seam callers
 * outside `shared` (namely `MediaTrackerApplication.onCreate`, in the `app` module) compose
 * through instead of constructing a [CompositeLogger] themselves.
 */
public fun Logger.withPlatformLogger(): Logger = CompositeLogger(listOf(platformLogger(), this))
