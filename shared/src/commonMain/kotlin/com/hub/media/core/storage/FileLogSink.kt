package com.hub.media.core.storage

import com.hub.media.core.util.Logger
import com.hub.media.core.util.LogLevel

/**
 * [Logger] adapter writing into a [LogFileStore] (ROADMAP Task 15 Phase B). Installed as (part of)
 * `AppLogger`'s delegate at process start -- see
 * [com.hub.media.core.util.withPlatformLogger]/`MediaTrackerApplication.onCreate` -- so it receives
 * every call that already passed [com.hub.media.core.util.AppLogger]'s verbosity threshold,
 * alongside the pre-existing [com.hub.media.core.util.platformLogger] sink (logcat/stdout), never
 * in place of it.
 *
 * ### Throwable handling
 * A [LogEntry] has no `Throwable` field (see that class's KDoc) -- this is the one place a
 * [Throwable] gets folded into plain text, via [Throwable.stackTraceToString], before ever
 * reaching [LogFileStore.append]. This happens unconditionally when [throwable] is non-null, since
 * exception text from this codebase's own network/DB/file-I/O layers never embeds book content
 * (the same premise [com.hub.media.core.util.Logger]'s KDoc already documents for every sink).
 *
 * ### Never a new source of failure
 * Every step here (evaluating [message], formatting the throwable, handing off to
 * [LogFileStore.append]) is wrapped in a single broad `catch` -- matching
 * `PlatformLogger.android.kt`'s `AndroidLogger` rationale exactly: a logging call must never
 * itself become a new source of failure for whatever business logic happened to be logging a
 * WARN/ERROR about something else entirely.
 */
public class FileLogSink(private val store: LogFileStore) : Logger {
    override fun log(level: LogLevel, tag: String, throwable: Throwable?, message: () -> String) {
        try {
            val text = if (throwable != null) {
                "${message()}\n${throwable.stackTraceToString()}"
            } else {
                message()
            }
            store.append(level, tag, text)
        } catch (_: Throwable) {
            // See this class's KDoc "Never a new source of failure".
        }
    }
}
