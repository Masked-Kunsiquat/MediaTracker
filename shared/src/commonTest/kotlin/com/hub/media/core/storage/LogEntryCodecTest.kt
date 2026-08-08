package com.hub.media.core.storage

import com.hub.media.core.util.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Tests for [LogEntryCodec.kt]'s hand-rolled delimited format (ROADMAP Task 15 Phase B) --
 * specifically the two invariants its KDoc claims:
 *  1. Round-trip fidelity through [encodeLogEntry]/[decodeLogEntry], including adversarial content
 *     a real log message/tag can contain (stack traces, raw delimiter/escape characters, unicode).
 *  2. Malformed-line tolerance: [decodeLogEntry] returns `null` (never throws) for a damaged line,
 *     and [decodeLogEntries] skips such a line without hiding the well-formed records around it.
 */
class LogEntryCodecTest {

    private fun roundTrip(message: String, tag: String = "Tag"): LogEntry {
        val original = LogEntry(
            seq = 1L,
            timestampMillis = 1_700_000_000_000L,
            level = LogLevel.INFO,
            tag = tag,
            message = message,
        )
        val encoded = encodeLogEntry(original)
        return decodeLogEntry(encoded) ?: error("expected a successful decode of: $encoded")
    }

    // --- Adversarial round-trip content (requirement 1) -------------------------------------

    @Test
    fun codecRoundTrip_embeddedNewlinesInMessage_preservedExactly() {
        val message = "first line\nsecond line\nthird line (simulated multi-line stack trace)"
        assertEquals(message, roundTrip(message).message)
    }

    @Test
    fun codecRoundTrip_carriageReturnInMessage_preservedExactly() {
        val message = "before\rafter"
        assertEquals(message, roundTrip(message).message)
    }

    @Test
    fun codecRoundTrip_tabCharacterInMessage_passesThroughUnescaped() {
        // Tab is outside the four-character escape set (\, DELIMITER, \n, \r) -- this test
        // documents that it survives unmodified rather than being (incorrectly) escaped.
        val message = "col1\tcol2\tcol3"
        assertEquals(message, roundTrip(message).message)
    }

    @Test
    fun codecRoundTrip_literalBackslashInMessage_preservedExactly() {
        val message = """a single backslash right here: \ (and it keeps going)"""
        assertEquals(message, roundTrip(message).message)
    }

    @Test
    fun codecRoundTrip_backslashAtEndOfStringInMessage_preservedExactly() {
        val message = "message ends with a trailing backslash\\"
        assertEquals(message, roundTrip(message).message)
    }

    @Test
    fun codecRoundTrip_rawFieldDelimiterCharacterInMessage_preservedExactly() {
        val message = "before${FIELD_DELIMITER}after"
        assertEquals(message, roundTrip(message).message)
    }

    @Test
    fun codecRoundTrip_rawFieldDelimiterCharacterInTag_preservedExactly() {
        val tag = "tag${FIELD_DELIMITER}withEmbeddedDelimiter"
        val decoded = roundTrip(message = "irrelevant", tag = tag)
        assertEquals(tag, decoded.tag)
    }

    @Test
    fun codecRoundTrip_emptyMessage_preservedAsEmptyString() {
        assertEquals("", roundTrip("").message)
    }

    @Test
    fun codecRoundTrip_emptyTag_preservedAsEmptyString() {
        val decoded = roundTrip(message = "msg", tag = "")
        assertEquals("", decoded.tag)
    }

    @Test
    fun codecRoundTrip_unicodeEmojiAndCjkCharacters_preservedExactly() {
        val message = "emoji 😀🔥, CJK 日本語, accented café"
        assertEquals(message, roundTrip(message).message)
    }

    @Test
    fun codecRoundTrip_literalBackslashNSequence_notConfusedWithARealNewline() {
        // A message containing the literal TWO-character text "\n" (a backslash followed by the
        // letter n) -- not an actual newline byte. escapeField's real-newline case ('\n' -> the
        // literal two-char sequence "\n") produces byte-identical *output* to escaping a literal
        // backslash-then-n input ('\\' -> "\\\\", then 'n' passes through -> "\\\\n"), so this
        // guards specifically against the decoder conflating the two on the way back.
        val message = """diagnostic text containing the literal sequence \n, not a real newline"""
        val decoded = roundTrip(message)
        assertEquals(message, decoded.message)
        assertFalse(decoded.message.contains('\n'), "must not have become a real newline character")
    }

    @Test
    fun codecRoundTrip_literalBackslashDSequence_notConfusedWithFieldDelimiterEscape() {
        // Same hazard as above, for the delimiter escape code ('d') instead of the newline one.
        val message = """diagnostic text containing the literal sequence \d, not a real delimiter"""
        val decoded = roundTrip(message)
        assertEquals(message, decoded.message)
        assertFalse(
            decoded.message.contains(FIELD_DELIMITER),
            "must not have become a real FIELD_DELIMITER character",
        )
    }

    // --- Multi-entry round-trip via encodeLogEntries/decodeLogEntries (plural) ---------------

    @Test
    fun encodeLogEntries_multipleEntriesRoundTrip_decodesUnchangedAndInOrder() {
        // The single-entry tests above only exercise encodeLogEntry/decodeLogEntry. The plural
        // encodeLogEntries/decodeLogEntries pair is what LogFileStore actually calls on every
        // flush/read -- this proves it preserves both content and order across several entries,
        // not just a single one.
        val entries = listOf(
            LogEntry(1L, 1_000L, LogLevel.INFO, "T1", "first"),
            LogEntry(2L, 2_000L, LogLevel.WARN, "T2", "second"),
            LogEntry(3L, 3_000L, LogLevel.ERROR, "T3", "third, with a\nnewline\\and a backslash"),
        )

        val decoded = decodeLogEntries(encodeLogEntries(entries))

        assertEquals(entries, decoded)
    }

    // --- Malformed-line tolerance (requirement 2) --------------------------------------------

    @Test
    fun decodeLogEntries_blankLinesBetweenRecords_bothRecordsStillDecode() {
        // decodeLogEntries deliberately treats an empty line as "skip", not "malformed" (see this
        // file's KDoc) -- nothing else in this suite exercises that branch of the mapNotNull.
        val first = LogEntry(1L, 1_000L, LogLevel.INFO, "T", "first, well-formed")
        val second = LogEntry(2L, 2_000L, LogLevel.WARN, "T", "second, well-formed")
        val bytes = (
            "\n" +
                encodeLogEntry(first) + "\n" +
                "\n" +
                encodeLogEntry(second) + "\n" +
                "\n"
            ).encodeToByteArray()

        val decoded = decodeLogEntries(bytes)

        assertEquals(listOf(first, second), decoded)
    }

    @Test
    fun decodeLogEntries_garbageLineInMiddle_doesNotHideEntriesBeforeOrAfterIt() {
        val before = LogEntry(1L, 1_000L, LogLevel.INFO, "T", "first, well-formed")
        val after = LogEntry(2L, 2_000L, LogLevel.WARN, "T", "second, well-formed")
        val bytes = (
            encodeLogEntry(before) + "\n" +
                "this line is not delimited log data at all" + "\n" +
                encodeLogEntry(after) + "\n"
            ).encodeToByteArray()

        val decoded = decodeLogEntries(bytes)

        assertEquals(listOf(before, after), decoded)
    }

    @Test
    fun decodeLogEntries_truncatedFinalLine_skipsItButKeepsEverythingBeforeIt() {
        val complete = LogEntry(1L, 1_000L, LogLevel.INFO, "T", "fully written before the crash")
        // Simulates a crash exactly mid-append (LogFileStore's KDoc "A real append, not a
        // read-modify-write"): the first record landed in full, including its trailing newline;
        // the process died while writing the second record, leaving an incomplete final "line"
        // (fewer than the required 5 fields) with no trailing newline at all.
        val bytes = (
            encodeLogEntry(complete) + "\n" +
                "2${FIELD_DELIMITER}2000${FIELD_DELIMITER}ERROR${FIELD_DELIMITER}T"
            ).encodeToByteArray()

        val decoded = decodeLogEntries(bytes)

        assertEquals(listOf(complete), decoded)
    }

    @Test
    fun decodeLogEntry_badLevelName_returnsNull() {
        val line = "1${FIELD_DELIMITER}1000${FIELD_DELIMITER}NOT_A_REAL_LEVEL${FIELD_DELIMITER}tag${FIELD_DELIMITER}msg"
        assertNull(decodeLogEntry(line))
    }

    @Test
    fun decodeLogEntry_unparseableSeq_returnsNull() {
        val line = "not-a-number${FIELD_DELIMITER}1000${FIELD_DELIMITER}INFO${FIELD_DELIMITER}tag${FIELD_DELIMITER}msg"
        assertNull(decodeLogEntry(line))
    }

    @Test
    fun decodeLogEntry_trailingUnescapedBackslash_returnsNull() {
        val line = "1${FIELD_DELIMITER}1000${FIELD_DELIMITER}INFO${FIELD_DELIMITER}tag${FIELD_DELIMITER}message ending in backslash\\"
        assertNull(decodeLogEntry(line))
    }

    @Test
    fun decodeLogEntry_unrecognizedEscapeCode_returnsNull() {
        val line = "1${FIELD_DELIMITER}1000${FIELD_DELIMITER}INFO${FIELD_DELIMITER}tag${FIELD_DELIMITER}bad escape \\q here"
        assertNull(decodeLogEntry(line))
    }

    @Test
    fun decodeLogEntry_wrongFieldCount_returnsNull() {
        val line = "1${FIELD_DELIMITER}1000${FIELD_DELIMITER}INFO${FIELD_DELIMITER}onlyFourFieldsTotal"
        assertNull(decodeLogEntry(line))
    }
}
