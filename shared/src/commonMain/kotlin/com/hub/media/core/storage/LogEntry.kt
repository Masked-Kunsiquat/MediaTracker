package com.hub.media.core.storage

import com.hub.media.core.util.LogLevel

/**
 * A single persisted log record (ROADMAP Task 15 Phase B — the persistent, user-owned log store).
 *
 * Deliberately does **not** carry a `Throwable` reference: [LogFileStore]/[FileLogSink] fold a
 * [Throwable]'s text (via `Throwable.stackTraceToString()`) into [message] at write time, once,
 * before this class is ever constructed. A [LogEntry] is what actually lives on disk and what the
 * Phase B2 viewer/export path reads back — keeping it a plain, fully-serializable value (no JVM
 * object reference that can't survive a process restart) means the read path never has to decide
 * what to do with a `Throwable` it could never have reconstructed from disk in the first place.
 *
 * @property seq Monotonically increasing, gap-tolerant sequence number, unique within this
 *   store's lifetime (see [LogFileStore]'s KDoc "Sequence numbering" for the full contract). The
 *   Phase B2 viewer's snapshot/refresh boundary is built entirely on this field, never on
 *   [timestampMillis] — see the ROADMAP bullet on why timestamps are unsafe for that purpose
 *   (clock jumps, same-millisecond collisions).
 * @property timestampMillis Wall-clock time this entry was accepted by [LogFileStore.append], in
 *   epoch milliseconds. Display-only; never used for ordering (see [seq]).
 * @property level The [LogLevel] this entry was logged at.
 * @property tag The originating [com.hub.media.core.util.Logger.log] call's tag.
 * @property message The call's message, with any attached `Throwable`'s text already folded in.
 *   Follows the same identifier rule as every other [com.hub.media.core.util.Logger] call site
 *   (see that interface's KDoc) — this class does not and cannot enforce that rule itself, since
 *   it has no domain knowledge of what "book content" looks like; it only ever stores what it was
 *   given.
 */
public data class LogEntry(
    public val seq: Long,
    public val timestampMillis: Long,
    public val level: LogLevel,
    public val tag: String,
    public val message: String,
)
