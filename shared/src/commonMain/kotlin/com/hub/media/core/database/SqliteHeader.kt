package com.hub.media.core.database

/**
 * Bytes 0-15 of every valid SQLite database file: the literal ASCII string `"SQLite format 3"`
 * followed by a `NUL` terminator (https://www.sqlite.org/fileformat.html#the_database_header) --
 * stable since SQLite 3.0 and never changed since.
 */
private val SQLITE_MAGIC: ByteArray =
    byteArrayOf(
        0x53,
        0x51,
        0x4c,
        0x69,
        0x74,
        0x65,
        0x20,
        0x66,
        0x6f,
        0x72,
        0x6d,
        0x61,
        0x74,
        0x20,
        0x33,
        0x00,
    )

/**
 * SQLite's fixed database header size in bytes. Every valid SQLite file is at least this long
 * (the minimum page size is 512 bytes, far larger than the 100-byte header itself), so a file
 * shorter than this can never be a real SQLite database. Callers reading a file's header (e.g.
 * `readFileHeaderBytes` in `DatabaseFileOps.kt`) should request at least this many bytes.
 */
public const val SQLITE_HEADER_SIZE: Int = 100

/**
 * Byte offset of the 4-byte, big-endian "user version number" field within the 100-byte header --
 * the value `PRAGMA user_version` reads/writes, which Room uses as its own schema version (see
 * [AppDatabase]'s `version` / [APP_DATABASE_VERSION]).
 */
private const val USER_VERSION_OFFSET = 60

/**
 * Result of successfully parsing a candidate database file's header via [parseSqliteHeader].
 *
 * @property userVersion The schema version Room stamped into the file via `PRAGMA user_version`
 *   at the time it was written (or `0` for a SQLite file Room has never touched).
 */
public data class SqliteHeaderInfo(
    public val userVersion: Int,
)

/**
 * Parses the first [SQLITE_HEADER_SIZE] bytes of a candidate `.sqlite`/`.db` file (ROADMAP Task 8
 * Phase C restore validation): confirms the 16-byte magic string every valid SQLite database file
 * starts with, then reads `user_version` directly from its fixed offset -- both positions are
 * defined by SQLite's on-disk file format, not by anything Room-specific.
 *
 * ### Why this reads raw bytes instead of opening the file with a SQLite driver
 * A restore candidate must be rejected *before* anything -- Room, `BundledSQLiteDriver`, even a
 * plain `sqlite3_open` -- ever touches it: a file that isn't SQLite at all could make an SQLite
 * driver behave unpredictably, and a file from a *newer* schema than this build understands must
 * be refused with a clear message rather than letting Room fail obscurely mid-open (this task's
 * explicit brief). Both checks this function performs -- the magic string and `user_version` --
 * live at fixed byte offsets defined by the file format itself, so parsing them directly needs no
 * database connection, no native library call, and no I/O beyond reading 100 bytes. This also
 * makes the function pure Kotlin, dependency-free, and trivially unit-testable with hand-built
 * byte arrays (see `SqliteHeaderTest`, `commonTest` -- no Room-touching-test exclusion needed).
 *
 * @return `null` if [bytes] is shorter than [SQLITE_HEADER_SIZE] or doesn't start with the SQLite
 *   magic string (not a SQLite database at all); otherwise the parsed [SqliteHeaderInfo].
 */
public fun parseSqliteHeader(bytes: ByteArray): SqliteHeaderInfo? {
    if (bytes.size < SQLITE_HEADER_SIZE) return null
    for (i in SQLITE_MAGIC.indices) {
        if (bytes[i] != SQLITE_MAGIC[i]) return null
    }
    val userVersion =
        ((bytes[USER_VERSION_OFFSET].toInt() and 0xFF) shl 24) or
            ((bytes[USER_VERSION_OFFSET + 1].toInt() and 0xFF) shl 16) or
            ((bytes[USER_VERSION_OFFSET + 2].toInt() and 0xFF) shl 8) or
            (bytes[USER_VERSION_OFFSET + 3].toInt() and 0xFF)
    return SqliteHeaderInfo(userVersion)
}
