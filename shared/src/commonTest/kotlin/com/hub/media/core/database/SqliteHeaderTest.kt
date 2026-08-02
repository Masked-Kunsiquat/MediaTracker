package com.hub.media.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests [parseSqliteHeader] against hand-built byte arrays -- pure Kotlin, no Room/SQLite
 * dependency at all, so this runs on every test variant (unlike the Room-touching restore/backup
 * integration tests in `jvmTest`). Covers ROADMAP Task 8 Phase C's restore validation deliverables:
 * header rejection for a non-SQLite file, and correct `user_version` extraction for the newer/older
 * version-check rules `RestoreDatabaseUseCase` builds on top of this.
 */
class SqliteHeaderTest {

    /** Builds a syntactically valid 100-byte SQLite header with [userVersion] at its fixed offset. */
    private fun validHeader(userVersion: Int): ByteArray {
        val bytes = ByteArray(SQLITE_HEADER_SIZE)
        val magic = "SQLite format 3".encodeToByteArray()
        magic.copyInto(bytes)
        bytes[15] = 0 // NUL terminator
        bytes[60] = ((userVersion ushr 24) and 0xFF).toByte()
        bytes[61] = ((userVersion ushr 16) and 0xFF).toByte()
        bytes[62] = ((userVersion ushr 8) and 0xFF).toByte()
        bytes[63] = (userVersion and 0xFF).toByte()
        return bytes
    }

    @Test
    fun parseSqliteHeader_validHeader_parsesUserVersion() {
        val info = parseSqliteHeader(validHeader(userVersion = 4))
        assertEquals(SqliteHeaderInfo(userVersion = 4), info)
    }

    @Test
    fun parseSqliteHeader_validHeader_zeroUserVersion() {
        // A SQLite file Room has never touched (user_version defaults to 0) must still parse
        // cleanly as a real database, just an unmigrated one -- the version-newer-than-current
        // check downstream is what decides whether that's acceptable, not this function.
        val info = parseSqliteHeader(validHeader(userVersion = 0))
        assertEquals(SqliteHeaderInfo(userVersion = 0), info)
    }

    @Test
    fun parseSqliteHeader_largeUserVersion_parsesCorrectly() {
        // A value using the high bit of the 32-bit field, to catch a signed/unsigned shift bug.
        val info = parseSqliteHeader(validHeader(userVersion = 300))
        assertEquals(SqliteHeaderInfo(userVersion = 300), info)
    }

    @Test
    fun parseSqliteHeader_notASqliteFile_returnsNull() {
        // A plausible "wrong file" mistake: picking a CSV export instead of a database backup.
        val bytes = "media_id,type,title\n1,BOOK,Test\n".encodeToByteArray()
        assertNull(parseSqliteHeader(bytes))
    }

    @Test
    fun parseSqliteHeader_emptyFile_returnsNull() {
        assertNull(parseSqliteHeader(ByteArray(0)))
    }

    @Test
    fun parseSqliteHeader_tooShortToContainHeader_returnsNull() {
        assertNull(parseSqliteHeader(ByteArray(50)))
    }

    @Test
    fun parseSqliteHeader_almostCorrectMagic_returnsNull() {
        // One byte off from the real magic string -- must not fuzzy-match.
        val bytes = validHeader(userVersion = 1)
        bytes[14] = 'X'.code.toByte() // magic's '3' becomes 'X'
        assertNull(parseSqliteHeader(bytes))
    }

    @Test
    fun parseSqliteHeader_missingNulTerminator_returnsNull() {
        val bytes = validHeader(userVersion = 1)
        bytes[15] = 'Q'.code.toByte() // magic's trailing NUL replaced with a non-NUL byte
        assertNull(parseSqliteHeader(bytes))
    }
}
