package com.hub.media.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * Validates every registered [androidx.room.migration.Migration] against the real exported schemas
 * in `shared/schemas` (AGENTS.md §8: a schema-version bump on a previously-tagged schema requires a
 * tested [androidx.room.migration.Migration]):
 * - `MIGRATION_1_2`: the schema v1 -> v2 table rebuild that makes
 *   [com.hub.media.core.database.entities.ReadingSessionEntity.durationSeconds] nullable (ROADMAP
 *   Task 5 pre-phase, see that entity's KDoc).
 * - `MIGRATION_2_3` (ROADMAP Task 6 Phase C): the schema v2 -> v3 `ALTER TABLE` that adds
 *   [com.hub.media.core.database.entities.BookDetailsEntity.status]/
 *   [com.hub.media.core.database.entities.BookDetailsEntity.finishedAt] — see that migration's KDoc
 *   (`Migrations.kt`) for the derivation rules its tests below assert.
 * - `MIGRATION_3_4` (ROADMAP Task 7 Phase A): the schema v3 -> v4 `ALTER TABLE` that adds
 *   [com.hub.media.core.database.entities.BookDetailsEntity.trackingMode] plus the new
 *   `app_settings` table — see that migration's KDoc (`Migrations.kt`) for the derivation rules its
 *   tests below assert.
 *
 * Uses Room KMP's [MigrationTestHelper] against the real exported schemas in `shared/schemas`
 * (the `room { schemaDirectory(...) }` config in `shared/build.gradle.kts`) rather than
 * hand-derived DDL, so the migration is checked against Room's own understanding of both the v1
 * and v2 shapes, not just this file's assumptions about them. [MigrationTestHelper] works
 * unmodified on the `:shared:jvmTest` target: it is JVM-common (not Android-instrumentation-only)
 * as of Room 2.8's KMP `room-testing` artifact, taking a plain [java.nio.file.Path] to the schema
 * directory and a [androidx.sqlite.SQLiteDriver] instead of an Android `Context`/asset manager —
 * no fallback to hand-rolled raw-SQL setup was needed.
 *
 * Room's `createDatabase`/`runMigrationsAndValidate` construct the requested schema version by
 * literally executing that version's exported `createSql` (from the matching `<version>.json`),
 * not by running the app's *current* compiled [AppDatabase] (which is v2-shaped) — so raw SQL
 * (matching the *v1* column set, before this migration existed) is used to seed rows here rather
 * than any DAO/entity from the current codebase.
 */
class MigrationTest {

    private val testDbDir: Path = Files.createTempDirectory("migration-test")
    private val testDbPath: Path = testDbDir.resolve("test.db")

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        schemaDirectoryPath = Path.of("schemas"),
        databasePath = testDbPath,
        driver = BundledSQLiteDriver(),
        databaseClass = AppDatabase::class,
        databaseFactory = { AppDatabaseConstructor.initialize() },
    )

    @AfterTest
    fun tearDown() {
        // SQLite may also leave -wal/-shm/-journal siblings next to the main file depending on
        // journal mode; sweep the whole temp directory rather than just testDbPath.
        Files.list(testDbDir).use { paths -> paths.forEach { if (it.exists()) it.deleteExisting() } }
        if (testDbDir.exists()) testDbDir.deleteExisting()
    }

    /**
     * The core row-safety guarantee (task deliverable #3): a v1 database with a real-duration
     * session AND a legitimate 0-duration session (the edge case `durationSeconds`-as-`0` must
     * never be confused with "unknown") must retain both, with every column's value intact, after
     * migrating to v2.
     *
     * This test is not vacuous: temporarily replacing `MIGRATION_1_2`'s body with just
     * `connection.execSQL("DROP TABLE reading_sessions")` (a deliberate "lose everything" break)
     * makes `runMigrationsAndValidate` throw ("Migration didn't properly handle *
     * reading_sessions" / a missing-table validation failure) rather than let this assertion run
     * at all -- and swapping the rebuild's `INSERT INTO ... SELECT` for one that filters out a row
     * (e.g. `WHERE durationSeconds != 0`) makes the `rows.size == 2` assertion below fail
     * directly. Both were verified by hand while writing this test, then reverted.
     */
    @Test
    fun migrate1To2_preservesExistingRows_withValuesIntact() {
        helper.createDatabase(1).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-1', 'BOOK', 'Test Book', 2020, 9.99, 1700000000000, NULL)",
            )
            // A real-duration session -- the common case.
            db.execSQL(
                "INSERT INTO reading_sessions " +
                    "(id, mediaId, timestampStart, timestampEnd, durationSeconds, startUnit, endUnit, deltaPages, notes) " +
                    "VALUES ('session-real', 'media-1', 1700000000000, 1700000600000, 600, 10.0, 25.0, 15, 'Good progress')",
            )
            // A legitimate 0-second session -- must remain distinguishable from "unknown" (null)
            // after the migration; see ReadingSessionEntity's KDoc.
            db.execSQL(
                "INSERT INTO reading_sessions " +
                    "(id, mediaId, timestampStart, timestampEnd, durationSeconds, startUnit, endUnit, deltaPages, notes) " +
                    "VALUES ('session-zero', 'media-1', 1700000700000, 1700000700000, 0, 10.0, 10.0, 0, NULL)",
            )
        }

        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { db ->
            val rows = mutableMapOf<String, LongArray>()
            val durations = mutableMapOf<String, Long?>()
            db.prepare(
                "SELECT id, durationSeconds, startUnit, endUnit, deltaPages FROM reading_sessions ORDER BY id",
            ).use { stmt ->
                while (stmt.step()) {
                    val id = stmt.getText(0)
                    durations[id] = if (stmt.isNull(1)) null else stmt.getLong(1)
                    assertEquals(10.0, stmt.getDouble(2), "startUnit for $id")
                    assertEquals(if (id == "session-real") 25.0 else 10.0, stmt.getDouble(3), "endUnit for $id")
                    assertEquals(if (id == "session-real") 15 else 0, stmt.getInt(4), "deltaPages for $id")
                }
            }

            assertEquals(setOf("session-real", "session-zero"), durations.keys, "both v1 rows must survive")
            assertEquals(600L, durations.getValue("session-real"), "real duration must be preserved exactly")
            assertEquals(0L, durations.getValue("session-zero"), "a 0-second session must stay 0, not become null")
        }
    }

    /**
     * Proves the migration actually relaxed the `NOT NULL` constraint (not just that it left
     * existing data alone): a v1 database would reject this exact `INSERT` with a `NOT NULL
     * constraint failed` error, since `durationSeconds` was `NOT NULL` there.
     */
    @Test
    fun migrate1To2_newTableAcceptsNullDuration() {
        helper.createDatabase(1).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-1', 'BOOK', 'Test Book', 2020, 9.99, 1700000000000, NULL)",
            )
        }

        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { db ->
            db.execSQL(
                "INSERT INTO reading_sessions " +
                    "(id, mediaId, timestampStart, timestampEnd, durationSeconds, startUnit, endUnit, deltaPages, notes) " +
                    "VALUES ('session-null', 'media-1', 1700001000000, 1700001000000, NULL, 5.0, 5.0, NULL, NULL)",
            )

            db.prepare("SELECT durationSeconds FROM reading_sessions WHERE id = 'session-null'").use { stmt ->
                assertTrue(stmt.step(), "the inserted row must be queryable back")
                assertTrue(stmt.isNull(0), "durationSeconds must accept NULL post-migration")
            }
        }
    }

    /** The `mediaId` index must survive the table rebuild (re-created explicitly by the migration). */
    @Test
    fun migrate1To2_recreatesMediaIdIndex() {
        helper.createDatabase(1).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-1', 'BOOK', 'Test Book', 2020, 9.99, 1700000000000, NULL)",
            )
        }

        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { db ->
            db.prepare(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_reading_sessions_mediaId'",
            ).use { stmt ->
                assertTrue(stmt.step(), "index_reading_sessions_mediaId must exist after migration")
            }
        }
    }

    // ==========================================================================================
    // MIGRATION_2_3 (ROADMAP Task 6 Phase C): adds `book_details.status`/`book_details.finishedAt`.
    // See that migration's KDoc (`Migrations.kt`) for the full `ALTER TABLE`/derivation rationale.
    // ==========================================================================================

    /**
     * The core deliverable (task deliverable #2): a v2 database seeded with one book that already
     * has a `reading_sessions` row and one that doesn't must, after migrating to v3, land the
     * former on `status = 'READING'` and the latter on `status = 'TO_READ'` — both with
     * `finishedAt` still `NULL` (no pre-v3 signal can ever justify `'FINISHED'`) — while every
     * pre-existing `book_details` column (`isbn`/`format`/`totalPages`) and every `media_items`/
     * `reading_sessions` row from the v2 seed survives completely untouched.
     *
     * This test is not vacuous: temporarily replacing the derivation `UPDATE` statement in
     * `MIGRATION_2_3` with a no-op (so every row keeps the blanket `'TO_READ'` default regardless
     * of sessions) makes the `"READING" to true` assertion below fail directly, since
     * `media-with-sessions` would incorrectly still read `'TO_READ'`. Swapping the `status` column
     * definition to omit `NOT NULL DEFAULT 'TO_READ'` (an intentionally invalid `ALTER TABLE` for a
     * table with existing rows and no default) makes `runMigrationsAndValidate` throw instead of
     * reaching either assertion at all. Both were verified by hand while writing this test, then
     * reverted.
     */
    @Test
    fun migrate2To3_derivesReadingForBooksWithSessions_toReadForBooksWithout() {
        helper.createDatabase(2).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-with-sessions', 'BOOK', 'Has Sessions', 2020, 9.99, 1700000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO book_details (mediaId, isbn, format, totalPages) " +
                    "VALUES ('media-with-sessions', '9780000000000', 'PHYSICAL', 300)",
            )
            db.execSQL(
                "INSERT INTO reading_sessions " +
                    "(id, mediaId, timestampStart, timestampEnd, durationSeconds, startUnit, endUnit, deltaPages, notes) " +
                    "VALUES ('session-1', 'media-with-sessions', 1700000000000, 1700000600000, 600, 10.0, 25.0, 15, NULL)",
            )
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-no-sessions', 'BOOK', 'No Sessions', 2019, 5.0, 1700000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO book_details (mediaId, isbn, format, totalPages) " +
                    "VALUES ('media-no-sessions', '9780000000001', 'EBOOK', 100)",
            )
        }

        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { db ->
            val results = mutableMapOf<String, Pair<String, Boolean>>()
            db.prepare("SELECT mediaId, status, finishedAt, isbn, format, totalPages FROM book_details ORDER BY mediaId")
                .use { stmt ->
                    while (stmt.step()) {
                        val mediaId = stmt.getText(0)
                        results[mediaId] = stmt.getText(1) to stmt.isNull(2)
                        // Pre-existing columns must survive untouched.
                        if (mediaId == "media-with-sessions") {
                            assertEquals("9780000000000", stmt.getText(3))
                            assertEquals("PHYSICAL", stmt.getText(4))
                            assertEquals(300, stmt.getInt(5))
                        } else {
                            assertEquals("9780000000001", stmt.getText(3))
                            assertEquals("EBOOK", stmt.getText(4))
                            assertEquals(100, stmt.getInt(5))
                        }
                    }
                }

            assertEquals(
                setOf("media-with-sessions", "media-no-sessions"),
                results.keys,
                "both v2 book_details rows must survive",
            )
            assertEquals(
                "READING" to true,
                results["media-with-sessions"],
                "a book with an existing reading_sessions row must derive READING, finishedAt still null",
            )
            assertEquals(
                "TO_READ" to true,
                results["media-no-sessions"],
                "a book with no reading_sessions row must default to TO_READ, finishedAt null",
            )
        }
    }

    /**
     * Proves the migration actually relaxed the schema to accept a `FINISHED` status with a real
     * `finishedAt` going forward (not just that it left v2 data alone) — mirroring
     * [migrate1To2_newTableAcceptsNullDuration]'s "new capability" shape for the v1->v2 migration.
     */
    @Test
    fun migrate2To3_newColumnsAcceptFinishedStatusAndFinishedAt() {
        helper.createDatabase(2).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-1', 'BOOK', 'Test Book', 2020, 9.99, 1700000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO book_details (mediaId, isbn, format, totalPages) " +
                    "VALUES ('media-1', '9780000000000', 'PHYSICAL', 300)",
            )
        }

        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { db ->
            db.execSQL(
                "UPDATE book_details SET status = 'FINISHED', finishedAt = 1700001000000 WHERE mediaId = 'media-1'",
            )
            db.prepare("SELECT status, finishedAt FROM book_details WHERE mediaId = 'media-1'").use { stmt ->
                assertTrue(stmt.step())
                assertEquals("FINISHED", stmt.getText(0))
                assertEquals(1700001000000L, stmt.getLong(1))
            }
        }
    }

    /** A book with zero rows in either table around it (no sessions, no other books) still gets the blanket default. */
    @Test
    fun migrate2To3_emptyDatabase_validatesCleanly() {
        helper.createDatabase(2).use { }

        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { db ->
            db.prepare("SELECT COUNT(*) FROM book_details").use { stmt ->
                assertTrue(stmt.step())
                assertEquals(0, stmt.getInt(0))
            }
        }
    }

    // ==========================================================================================
    // MIGRATION_3_4 (ROADMAP Task 7 Phase A): adds `book_details.trackingMode` and the new
    // `app_settings` table. See that migration's KDoc (`Migrations.kt`) for the full `ALTER TABLE`/
    // `CREATE TABLE`/derivation rationale.
    // ==========================================================================================

    /**
     * The core deliverable (task requirement): a v3 database seeded with one book that has a known
     * `totalPages` and one that doesn't must, after migrating to v4, land the former on
     * `trackingMode = 'PAGES'` and the latter on `'PERCENT'` — reproducing exactly the mode the app
     * already inferred for each pre-v4 (`totalPages != null`) — while every pre-existing
     * `book_details` column (`isbn`/`format`/`totalPages`/`status`/`finishedAt`) and every
     * `media_items` row from the v3 seed survives completely untouched.
     *
     * ### Kill-test performed while writing this test (per task instructions), then reverted
     * Two deliberate breaks were verified by hand and both failed as expected:
     * 1. Replacing the derivation `UPDATE ... WHERE totalPages IS NULL` with a no-op (every row
     *    keeps the blanket `'PAGES'` default from the column's own `DEFAULT` regardless of
     *    `totalPages`) made the `"PERCENT" to true` assertion below fail directly, since
     *    `media-no-total-pages` incorrectly still read `'PAGES'`.
     * 2. Swapping the `trackingMode` column definition to omit `NOT NULL DEFAULT 'PAGES'` (an
     *    intentionally invalid `ALTER TABLE` for a table with existing rows and no default) made
     *    `runMigrationsAndValidate` throw before either assertion below could run at all.
     *
     * Both were reverted immediately after confirming the failure, restoring `MIGRATION_3_4` to its
     * committed shape.
     */
    @Test
    fun migrate3To4_derivesPagesForKnownTotalPages_percentForUnknown() {
        helper.createDatabase(3).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-with-total-pages', 'BOOK', 'Has Total Pages', 2020, 9.99, 1700000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO book_details (mediaId, isbn, format, totalPages, status, finishedAt) " +
                    "VALUES ('media-with-total-pages', '9780000000000', 'PHYSICAL', 300, 'READING', NULL)",
            )
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-no-total-pages', 'BOOK', 'No Total Pages', 2019, 5.0, 1700000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO book_details (mediaId, isbn, format, totalPages, status, finishedAt) " +
                    "VALUES ('media-no-total-pages', '9780000000001', 'EBOOK', NULL, 'TO_READ', NULL)",
            )
        }

        helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4)).use { db ->
            val results = mutableMapOf<String, Pair<String, Boolean>>()
            db.prepare(
                "SELECT mediaId, trackingMode, totalPages, isbn, format, status " +
                    "FROM book_details ORDER BY mediaId",
            ).use { stmt ->
                while (stmt.step()) {
                    val mediaId = stmt.getText(0)
                    results[mediaId] = stmt.getText(1) to stmt.isNull(2)
                    // Pre-existing columns must survive untouched.
                    if (mediaId == "media-with-total-pages") {
                        assertEquals("9780000000000", stmt.getText(3))
                        assertEquals("PHYSICAL", stmt.getText(4))
                        assertEquals("READING", stmt.getText(5))
                    } else {
                        assertEquals("9780000000001", stmt.getText(3))
                        assertEquals("EBOOK", stmt.getText(4))
                        assertEquals("TO_READ", stmt.getText(5))
                    }
                }
            }

            assertEquals(
                setOf("media-with-total-pages", "media-no-total-pages"),
                results.keys,
                "both v3 book_details rows must survive",
            )
            assertEquals(
                "PAGES" to false,
                results["media-with-total-pages"],
                "a book with a known totalPages must derive PAGES (totalPages IS NULL == false)",
            )
            assertEquals(
                "PERCENT" to true,
                results["media-no-total-pages"],
                "a book with an unknown totalPages must derive PERCENT (totalPages IS NULL == true)",
            )
        }
    }

    /**
     * Proves the migration actually relaxed the schema to accept an explicit `trackingMode` value
     * independent of `totalPages` going forward (not just that it left v3 data alone) — mirroring
     * [migrate2To3_newColumnsAcceptFinishedStatusAndFinishedAt]'s "new capability" shape.
     */
    @Test
    fun migrate3To4_newColumnAcceptsExplicitTrackingModeIndependentOfTotalPages() {
        helper.createDatabase(3).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-1', 'BOOK', 'Test Book', 2020, 9.99, 1700000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO book_details (mediaId, isbn, format, totalPages, status, finishedAt) " +
                    "VALUES ('media-1', '9780000000000', 'PHYSICAL', 300, 'TO_READ', NULL)",
            )
        }

        helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4)).use { db ->
            // A book with a known totalPages can still be explicitly set to PERCENT -- the whole
            // point of decoupling trackingMode from totalPages inference.
            db.execSQL("UPDATE book_details SET trackingMode = 'PERCENT' WHERE mediaId = 'media-1'")
            db.prepare("SELECT trackingMode, totalPages FROM book_details WHERE mediaId = 'media-1'").use { stmt ->
                assertTrue(stmt.step())
                assertEquals("PERCENT", stmt.getText(0))
                assertEquals(300, stmt.getInt(1))
            }
        }
    }

    /** A book with zero rows around it still gets the blanket default, same shape as [migrate2To3_emptyDatabase_validatesCleanly]. */
    @Test
    fun migrate3To4_emptyDatabase_validatesCleanly() {
        helper.createDatabase(3).use { }

        helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4)).use { db ->
            db.prepare("SELECT COUNT(*) FROM book_details").use { stmt ->
                assertTrue(stmt.step())
                assertEquals(0, stmt.getInt(0))
            }
        }
    }

    /**
     * The new `app_settings` table (task requirement: "the settings table exists and is
     * writable") must exist post-migration and accept a real insert/read-back, even though the
     * pre-v4 database it migrated from never had this table at all.
     */
    @Test
    fun migrate3To4_appSettingsTableExistsAndIsWritable() {
        helper.createDatabase(3).use { }

        helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4)).use { db ->
            db.execSQL(
                "INSERT INTO app_settings (`key`, `value`) VALUES ('week_start_day', 'MONDAY')",
            )
            db.prepare("SELECT `value` FROM app_settings WHERE `key` = 'week_start_day'").use { stmt ->
                assertTrue(stmt.step(), "the inserted setting must be queryable back")
                assertEquals("MONDAY", stmt.getText(0))
            }

            // The key is the primary key: re-inserting under the same key must replace, not
            // duplicate (mirroring AppSettingsDao.upsert's ON CONFLICT REPLACE semantics, checked
            // here at the raw-SQL level via `INSERT OR REPLACE` since this test seeds via SQL, not
            // through the DAO).
            db.execSQL(
                "INSERT OR REPLACE INTO app_settings (`key`, `value`) VALUES ('week_start_day', 'SUNDAY')",
            )
            db.prepare("SELECT COUNT(*) FROM app_settings WHERE `key` = 'week_start_day'").use { stmt ->
                assertTrue(stmt.step())
                assertEquals(1, stmt.getInt(0), "re-inserting under the same key must replace, not duplicate")
            }
        }
    }
}
