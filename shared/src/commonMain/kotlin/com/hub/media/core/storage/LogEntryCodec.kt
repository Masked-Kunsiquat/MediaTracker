package com.hub.media.core.storage

import com.hub.media.core.util.LogLevel

/**
 * Hand-rolled delimited serialization for [LogEntry] (ROADMAP Task 15 Phase B) -- no new
 * dependency (AGENTS.md section 5), in the same spirit as this codebase's existing hand-rolled
 * RFC 4180 CSV escaping (`CsvReader`/`CsvWriter`, ROADMAP Task 8), though not the same format: log
 * messages are frequently multi-line stack traces plus arbitrary free text, and every record must
 * also be independently recoverable line-by-line after a crash mid-write (see "Malformed-line
 * tolerance" below) -- CSV's quote-doubling rules don't fit that second requirement as directly as
 * a purpose-built delimiter/escape scheme does.
 *
 * ### Format
 * One entry per physical line, terminated by a real newline (`\n`): `seq` DELIM
 * `timestampMillis` DELIM `level` DELIM `tag` DELIM `message`, using [FIELD_DELIMITER] (the
 * Unicode `U+0001` "start of heading" control character) to separate the five fields.
 * `seq`/`timestampMillis`/`level` never need escaping (a decimal number or an enum name can never
 * contain the delimiter or a newline); [LogEntry.tag] and [LogEntry.message] are escaped via
 * [escapeField] before being placed in a record.
 *
 * ### Escaping
 * Within an escaped field, exactly four characters are backslash-escaped: `\` becomes `\\`,
 * [FIELD_DELIMITER] becomes `\d`, a real newline becomes the literal two-char sequence `\n`, and a
 * real carriage return becomes the literal two-char sequence `\r`. Escaping the delimiter *and*
 * every literal newline is what makes line-splitting safe: after escaping, a raw (unescaped)
 * newline byte in the file can only ever be a genuine record boundary, never an embedded newline
 * from a multi-line stack trace -- so [decodeLogEntries] can split the whole file on newlines first
 * and decode each resulting line independently, with no need to track quote/escape state across a
 * line boundary. A tab (or any other character outside this four-character escape set, including
 * arbitrary Unicode) passes through completely unmodified -- there is nothing to escape it against.
 *
 * ### Malformed-line tolerance
 * [LogFileStore.flush] performs a true append (see that class's "A real append, not a
 * read-modify-write" section), so a process death mid-write can only ever damage the tail of the
 * file -- the records already on disk are never rewritten and so are never put back at risk. The
 * decoder does not rely on that guarantee, though: every damaged line, wherever it falls and
 * whatever produced it, is handled the same way: [decodeLogEntry]
 * returns `null` (never throws) for any line that doesn't split into exactly five fields, whose
 * `seq`/`timestampMillis` aren't parseable as [Long], whose `level` isn't a valid [LogLevel] name,
 * or whose escaping is malformed (a trailing unescaped `\`, or an escape code other than the four
 * above). [decodeLogEntries] skips such lines via `mapNotNull` rather than aborting the whole read,
 * so one damaged record never hides every entry that came before or after it in the same file.
 */
// Written as a Unicode escape, never as the literal character. A raw U+0001 byte sitting in a
// source file is invisible in every editor and diff, survives neither a careless copy-paste nor
// any tool that normalizes or strips control characters, and if it were ever mangled it would
// silently change this format's on-disk encoding -- making every previously written log file
// undecodable, with nothing in the diff to show why. The escape is unambiguous to a reader, a
// reviewer, and a merge tool alike.
internal const val FIELD_DELIMITER: Char = '\u0001'
private const val FIELD_COUNT = 5

/** Encodes [entry] as one delimited line, without a trailing newline -- see this file's KDoc. */
internal fun encodeLogEntry(entry: LogEntry): String =
    buildString {
        append(entry.seq)
        append(FIELD_DELIMITER)
        append(entry.timestampMillis)
        append(FIELD_DELIMITER)
        append(entry.level.name)
        append(FIELD_DELIMITER)
        append(escapeField(entry.tag))
        append(FIELD_DELIMITER)
        append(escapeField(entry.message))
    }

/** Encodes [entries] as UTF-8 bytes, one newline-terminated line per entry, in list order. */
internal fun encodeLogEntries(entries: List<LogEntry>): ByteArray =
    entries.joinToString(separator = "") { encodeLogEntry(it) + "\n" }.encodeToByteArray()

/**
 * Decodes every well-formed line in [bytes] (see this file's KDoc "Malformed-line tolerance"),
 * skipping malformed or blank lines rather than failing the whole read.
 */
internal fun decodeLogEntries(bytes: ByteArray): List<LogEntry> =
    bytes.decodeToString().split("\n").mapNotNull { line ->
        if (line.isEmpty()) null else decodeLogEntry(line)
    }

/** Decodes a single line produced by [encodeLogEntry], or `null` if it is malformed. */
internal fun decodeLogEntry(line: String): LogEntry? {
    val fields = line.split(FIELD_DELIMITER)
    if (fields.size != FIELD_COUNT) return null
    val seq = fields[0].toLongOrNull() ?: return null
    val timestampMillis = fields[1].toLongOrNull() ?: return null
    val level = runCatching { LogLevel.valueOf(fields[2]) }.getOrNull() ?: return null
    val tag = unescapeField(fields[3]) ?: return null
    val message = unescapeField(fields[4]) ?: return null
    return LogEntry(seq, timestampMillis, level, tag, message)
}

private fun escapeField(value: String): String =
    buildString {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                FIELD_DELIMITER -> append("\\d")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

/**
 * Reverses [escapeField]. Returns `null` on malformed escaping (a trailing backslash, or an
 * unrecognized escape code) so the caller can skip the whole record rather than silently produce a
 * corrupted string.
 */
private fun unescapeField(value: String): String? {
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c != '\\') {
            out.append(c)
            i += 1
            continue
        }
        if (i + 1 >= value.length) return null // trailing backslash: malformed
        when (value[i + 1]) {
            '\\' -> out.append('\\')
            'd' -> out.append(FIELD_DELIMITER)
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            else -> return null // unrecognized escape code: malformed
        }
        i += 2
    }
    return out.toString()
}
