package com.hub.media.core.util

/**
 * In-memory [Logger] for tests (ROADMAP Task 15): records every call instead of writing anywhere,
 * so a test can assert *that* a failure was logged -- at what level, under what tag, with what
 * [Throwable] attached, and (critically) with what message text -- instead of an adoption site's
 * logging being eyeballed by reading production code. This is what makes the ROADMAP Task 15
 * adoption sites' "does not log user content" half of the requirement testable at all: a test can
 * assert an [Entry.message] never contains a book title/author/note, not just that *some* log call
 * happened.
 *
 * Deliberately evaluates [Logger.log]'s `message` lambda immediately, regardless of any [LogLevel]
 * threshold -- unlike [AppLogger], which is what actually gates evaluation in production (see its
 * KDoc). A test using this class wants the real text to assert against, not a silently suppressed
 * no-op; per-adoption-site tests inject [RecordingLogger] directly (bypassing [AppLogger]'s
 * threshold entirely) rather than reconfiguring the shared [AppLogger] singleton, so no two tests can
 * interfere with each other's threshold.
 */
public class RecordingLogger : Logger {
    /** One recorded [Logger.log] call. */
    public data class Entry(
        public val level: LogLevel,
        public val tag: String,
        public val message: String,
        public val throwable: Throwable?,
    )

    private val _entries = mutableListOf<Entry>()

    /** Every call recorded so far, in call order. */
    public val entries: List<Entry> get() = _entries

    override fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: () -> String,
    ) {
        _entries += Entry(level, tag, message(), throwable)
    }
}
