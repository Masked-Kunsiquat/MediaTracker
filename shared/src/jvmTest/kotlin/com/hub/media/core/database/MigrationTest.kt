package com.hub.media.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.RecordingLogger
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
 * - `MIGRATION_4_5` (ROADMAP Task 9 Phase A): the schema v4 -> v5 `ALTER TABLE` that adds
 *   [com.hub.media.core.database.entities.BookDetailsEntity.authors] — see that migration's KDoc
 *   (`Migrations.kt`) for the full rationale (denormalized column, `AUTHOR_SEPARATOR`, and why
 *   every pre-existing row honestly lands `NULL` rather than a derived/fabricated value).
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
    val helper: MigrationTestHelper =
        MigrationTestHelper(
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
                    "(id, mediaId, timestampStart, timestampEnd, durationSeconds, " +
                    "startUnit, endUnit, deltaPages, notes) " +
                    "VALUES ('session-real', 'media-1', 1700000000000, 1700000600000, 600, 10.0, 25.0, 15, 'Good progress')",
            )
            // A legitimate 0-second session -- must remain distinguishable from "unknown" (null)
            // after the migration; see ReadingSessionEntity's KDoc.
            db.execSQL(
                "INSERT INTO reading_sessions " +
                    "(id, mediaId, timestampStart, timestampEnd, durationSeconds, " +
                    "startUnit, endUnit, deltaPages, notes) " +
                    "VALUES ('session-zero', 'media-1', 1700000700000, 1700000700000, 0, 10.0, 10.0, 0, NULL)",
            )
        }

        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { db ->
            val rows = mutableMapOf<String, LongArray>()
            val durations = mutableMapOf<String, Long?>()
            db
                .prepare(
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
                    "(id, mediaId, timestampStart, timestampEnd, durationSeconds, " +
                    "startUnit, endUnit, deltaPages, notes) " +
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
            db
                .prepare(
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
                    "(id, mediaId, timestampStart, timestampEnd, durationSeconds, " +
                    "startUnit, endUnit, deltaPages, notes) " +
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
            db
                .prepare(
                    "SELECT mediaId, status, finishedAt, isbn, format, totalPages FROM book_details ORDER BY mediaId",
                ).use { stmt ->
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
            db
                .prepare(
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

    // ==========================================================================================
    // MIGRATION_4_5 (ROADMAP Task 9 Phase A): adds `book_details.authors`. See that migration's
    // KDoc (`Migrations.kt`) for the full `ALTER TABLE`/denormalized-column rationale.
    // ==========================================================================================

    /**
     * The core deliverable (task requirement): a v4 database seeded with an existing `book_details`
     * row must, after migrating to v5, land `authors` `NULL` (no pre-v5 signal could ever justify a
     * fabricated value — see `MIGRATION_4_5`'s KDoc) while every pre-existing `book_details` column
     * (`isbn`/`format`/`totalPages`/`status`/`finishedAt`/`trackingMode`) and the seeded
     * `media_items` row survive completely untouched.
     *
     * ### Kill-test actually run (per task instructions), then reverted -- three breaks, all failed
     * All three were executed for real (`./gradlew :shared:jvmTest --tests
     * com.hub.media.core.database.MigrationTest`), not just reasoned about, then reverted:
     * 1. `ALTER TABLE book_details ADD COLUMN authors TEXT NOT NULL DEFAULT ''` (wrong column
     *    *shape*, not just a wrong value: `NOT NULL` + a non-null default instead of a plain
     *    nullable column) made **every** `migrate4To5*` test fail with `IllegalStateException`
     *    before any of their own assertions ran at all -- Room's `runMigrationsAndValidate`
     *    compares the migrated database's live schema against the exported `5.json` and refused
     *    the mismatch outright.
     * 2. `ALTER TABLE book_details ADD COLUMN authors TEXT DEFAULT ''` (right shape -- still a
     *    plain nullable column, so schema validation passes -- but the *wrong value*: an empty-
     *    string default instead of `NULL`) passed schema validation and let
     *    [migrate4To5_preservesExistingRows_authorsColumnLandsNull] actually run, where it failed
     *    exactly as expected: `assertTrue(stmt.isNull(0), ...)` failed because the migrated row
     *    read back `""`, not `null`. This is the one break that could only be caught by asserting
     *    on the migrated *data*, not the schema shape.
     * 3. Deleting `MIGRATION_4_5`'s `ALTER TABLE` statement entirely (a no-op migration claiming to
     *    reach v5 without adding the column) reproduced break 1's failure mode: `IllegalStateException`
     *    from schema validation, before any assertion ran, on all three `migrate4To5*` tests.
     *
     * All three were reverted immediately after confirming the failure, restoring `MIGRATION_4_5` to
     * its committed shape (verified green again afterward).
     */
    @Test
    fun migrate4To5_preservesExistingRows_authorsColumnLandsNull() {
        helper.createDatabase(4).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-1', 'BOOK', 'Test Book', 2020, 9.99, 1700000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO book_details (mediaId, isbn, format, totalPages, status, finishedAt, trackingMode) " +
                    "VALUES ('media-1', '9780000000000', 'PHYSICAL', 300, 'READING', NULL, 'PAGES')",
            )
        }

        helper.runMigrationsAndValidate(5, listOf(MIGRATION_4_5)).use { db ->
            db
                .prepare(
                    "SELECT authors, isbn, format, totalPages, status, finishedAt, trackingMode " +
                        "FROM book_details WHERE mediaId = 'media-1'",
                ).use { stmt ->
                    assertTrue(stmt.step(), "the pre-existing book_details row must survive")
                    assertTrue(
                        stmt.isNull(0),
                        "authors must land NULL for a pre-existing row -- no signal to derive it from",
                    )
                    assertEquals("9780000000000", stmt.getText(1))
                    assertEquals("PHYSICAL", stmt.getText(2))
                    assertEquals(300, stmt.getInt(3))
                    assertEquals("READING", stmt.getText(4))
                    assertTrue(stmt.isNull(5))
                    assertEquals("PAGES", stmt.getText(6))
                }
        }
    }

    /**
     * Proves the migration actually relaxed the schema to accept a real `authors` value going
     * forward (not just that it left v4 data alone) — mirroring
     * [migrate3To4_newColumnAcceptsExplicitTrackingModeIndependentOfTotalPages]'s "new capability"
     * shape. Uses a semicolon-joined value (`"; "`, [com.hub.media.core.database.entities.BookDetailsEntity.AUTHOR_SEPARATOR])
     * to confirm the column stores the encoding this phase actually writes, not just any string.
     */
    @Test
    fun migrate4To5_newColumnAcceptsMultiAuthorValue() {
        helper.createDatabase(4).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-1', 'BOOK', 'Test Book', 2020, 9.99, 1700000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO book_details (mediaId, isbn, format, totalPages, status, finishedAt, trackingMode) " +
                    "VALUES ('media-1', '9780000000000', 'PHYSICAL', 300, 'TO_READ', NULL, 'PAGES')",
            )
        }

        helper.runMigrationsAndValidate(5, listOf(MIGRATION_4_5)).use { db ->
            db.execSQL(
                "UPDATE book_details SET authors = 'Ann Sample Author; B. Other Author' WHERE mediaId = 'media-1'",
            )
            db.prepare("SELECT authors FROM book_details WHERE mediaId = 'media-1'").use { stmt ->
                assertTrue(stmt.step())
                assertEquals("Ann Sample Author; B. Other Author", stmt.getText(0))
            }
        }
    }

    /** A book with zero rows around it still gets no error, same shape as [migrate3To4_emptyDatabase_validatesCleanly]. */
    @Test
    fun migrate4To5_emptyDatabase_validatesCleanly() {
        helper.createDatabase(4).use { }

        helper.runMigrationsAndValidate(5, listOf(MIGRATION_4_5)).use { db ->
            db.prepare("SELECT COUNT(*) FROM book_details").use { stmt ->
                assertTrue(stmt.step())
                assertEquals(0, stmt.getInt(0))
            }
        }
    }

    // ==========================================================================================
    // MIGRATION_5_6 (ROADMAP Task 13 Phase A): purely additive -- creates `movie_details`,
    // `tv_details`, `episodes`, and `watch_logs` (plus three indices), altering zero existing
    // tables or columns. See that migration's KDoc (`Migrations.kt`) for the full `CREATE TABLE`/
    // index rationale, and [com.hub.media.core.database.entities.MovieDetailsEntity]/
    // [com.hub.media.core.database.entities.TVDetailsEntity]/
    // [com.hub.media.core.database.entities.EpisodeEntity]/
    // [com.hub.media.core.database.entities.WatchLogEntity] for the new entities' column shapes.
    // ==========================================================================================

    /**
     * The core deliverable for a *purely additive* migration (task requirement): a v5 database
     * seeded with one real row in every pre-existing table this migration claims not to touch
     * (`media_items`, `book_details`, `reading_sessions`, `external_identifiers`) must, after
     * migrating to v6, still have every one of those rows with every column value byte-identical
     * -- not just present, but unchanged. This is the important assertion for this migration:
     * unlike `MIGRATION_1_2` through `MIGRATION_4_5` (each of which altered some existing table),
     * `MIGRATION_5_6` has no `ALTER TABLE`/derivation logic to test at all, so the only way it
     * could misbehave is by damaging data it was never supposed to touch (e.g. a copy/paste
     * mistake in one of its four `CREATE TABLE` statements accidentally colliding with, or
     * otherwise corrupting, an existing table).
     *
     * This test is not vacuous: temporarily replacing `MIGRATION_5_6`'s first statement with
     * `connection.execSQL("DELETE FROM book_details")` (a deliberate "quietly wipe a pre-existing
     * table this migration shouldn't touch at all" break) makes this test's `book_details`
     * `stmt.step()` assertion fail directly (no row left to step to), while
     * `runMigrationsAndValidate`'s own schema validation stays green throughout (the table's
     * *shape* never changed, only its contents) -- proving this test catches a class of bug schema
     * validation alone would silently miss. Verified by hand while writing this test, then
     * reverted.
     */
    @Test
    fun migrate5To6_preservesExistingRows_valuesIntact() {
        helper.createDatabase(5).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-1', 'BOOK', 'Test Book', 2020, 9.99, 1700000000000, 'hash-abc')",
            )
            db.execSQL(
                "INSERT INTO book_details " +
                    "(mediaId, isbn, format, totalPages, status, finishedAt, trackingMode, authors) " +
                    "VALUES ('media-1', '9780000000000', 'PHYSICAL', 300, 'FINISHED', 1700005000000, 'PAGES', " +
                    "'Ann Sample Author; B. Other Author')",
            )
            db.execSQL(
                "INSERT INTO reading_sessions " +
                    "(id, mediaId, timestampStart, timestampEnd, durationSeconds, " +
                    "startUnit, endUnit, deltaPages, notes) " +
                    "VALUES ('session-1', 'media-1', 1700000000000, 1700000600000, 600, 10.0, 25.0, 15, 'Good progress')",
            )
            db.execSQL(
                "INSERT INTO external_identifiers (mediaId, provider, externalId) " +
                    "VALUES ('media-1', 'ISBN', '9780000000000')",
            )
        }

        helper.runMigrationsAndValidate(6, listOf(MIGRATION_5_6)).use { db ->
            db
                .prepare(
                    "SELECT id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash " +
                        "FROM media_items WHERE id = 'media-1'",
                ).use { stmt ->
                    assertTrue(stmt.step(), "the pre-existing media_items row must survive")
                    assertEquals("media-1", stmt.getText(0))
                    assertEquals("BOOK", stmt.getText(1))
                    assertEquals("Test Book", stmt.getText(2))
                    assertEquals(2020, stmt.getInt(3))
                    assertEquals(9.99, stmt.getDouble(4))
                    assertEquals(1700000000000L, stmt.getLong(5))
                    assertEquals("hash-abc", stmt.getText(6))
                }

            db
                .prepare(
                    "SELECT isbn, format, totalPages, status, finishedAt, trackingMode, authors " +
                        "FROM book_details WHERE mediaId = 'media-1'",
                ).use { stmt ->
                    assertTrue(stmt.step(), "the pre-existing book_details row must survive")
                    assertEquals("9780000000000", stmt.getText(0))
                    assertEquals("PHYSICAL", stmt.getText(1))
                    assertEquals(300, stmt.getInt(2))
                    assertEquals("FINISHED", stmt.getText(3))
                    assertEquals(1700005000000L, stmt.getLong(4))
                    assertEquals("PAGES", stmt.getText(5))
                    assertEquals("Ann Sample Author; B. Other Author", stmt.getText(6))
                }

            db
                .prepare(
                    "SELECT timestampStart, timestampEnd, durationSeconds, startUnit, endUnit, deltaPages, notes " +
                        "FROM reading_sessions WHERE id = 'session-1'",
                ).use { stmt ->
                    assertTrue(stmt.step(), "the pre-existing reading_sessions row must survive")
                    assertEquals(1700000000000L, stmt.getLong(0))
                    assertEquals(1700000600000L, stmt.getLong(1))
                    assertEquals(600L, stmt.getLong(2))
                    assertEquals(10.0, stmt.getDouble(3))
                    assertEquals(25.0, stmt.getDouble(4))
                    assertEquals(15, stmt.getInt(5))
                    assertEquals("Good progress", stmt.getText(6))
                }

            db
                .prepare(
                    "SELECT provider, externalId FROM external_identifiers WHERE mediaId = 'media-1'",
                ).use { stmt ->
                    assertTrue(stmt.step(), "the pre-existing external_identifiers row must survive")
                    assertEquals("ISBN", stmt.getText(0))
                    assertEquals("9780000000000", stmt.getText(1))
                }
        }
    }

    /**
     * The other core deliverable (task requirement): the four brand-new tables must exist and be
     * writable after migrating, exercising every column with a real value -- including the
     * `episodes` -> `watch_logs` relationship (a watch log referencing a specific episode, not
     * just a bare mediaId).
     */
    @Test
    fun migrate5To6_newTablesAcceptRows_withRealValues() {
        helper.createDatabase(5).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-movie', 'MOVIE', 'Test Movie', 1999, 14.99, 1700000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-tv', 'TV_SHOW', 'Test Show', 2015, NULL, 1700000000000, NULL)",
            )
        }

        helper.runMigrationsAndValidate(6, listOf(MIGRATION_5_6)).use { db ->
            db.execSQL(
                "INSERT INTO movie_details (mediaId, runtimeMinutes, status, watchedAt) " +
                    "VALUES ('media-movie', 148, 'WATCHED', 1700100000000)",
            )
            db
                .prepare(
                    "SELECT runtimeMinutes, status, watchedAt FROM movie_details WHERE mediaId = 'media-movie'",
                ).use { stmt ->
                    assertTrue(stmt.step(), "the inserted movie_details row must be queryable back")
                    assertEquals(148, stmt.getInt(0))
                    assertEquals("WATCHED", stmt.getText(1))
                    assertEquals(1700100000000L, stmt.getLong(2))
                }

            db.execSQL(
                "INSERT INTO tv_details (mediaId, totalSeasons, status) VALUES ('media-tv', 5, 'WATCHING')",
            )
            db.prepare("SELECT totalSeasons, status FROM tv_details WHERE mediaId = 'media-tv'").use { stmt ->
                assertTrue(stmt.step(), "the inserted tv_details row must be queryable back")
                assertEquals(5, stmt.getInt(0))
                assertEquals("WATCHING", stmt.getText(1))
            }

            db.execSQL(
                "INSERT INTO episodes (id, mediaId, seasonNumber, episodeNumber, title, airDate, watchedAt) " +
                    "VALUES ('episode-1', 'media-tv', 1, 1, 'Pilot', 1420000000000, 1700200000000)",
            )
            db
                .prepare(
                    "SELECT seasonNumber, episodeNumber, title, airDate, watchedAt " +
                        "FROM episodes WHERE id = 'episode-1'",
                ).use { stmt ->
                    assertTrue(stmt.step(), "the inserted episodes row must be queryable back")
                    assertEquals(1, stmt.getInt(0))
                    assertEquals(1, stmt.getInt(1))
                    assertEquals("Pilot", stmt.getText(2))
                    assertEquals(1420000000000L, stmt.getLong(3))
                    assertEquals(1700200000000L, stmt.getLong(4))
                }

            db.execSQL(
                "INSERT INTO watch_logs (id, mediaId, episodeId, watchedAt, durationSeconds) " +
                    "VALUES ('log-1', 'media-tv', 'episode-1', 1700200000000, 1800)",
            )
            db
                .prepare(
                    "SELECT mediaId, episodeId, watchedAt, durationSeconds FROM watch_logs WHERE id = 'log-1'",
                ).use { stmt ->
                    assertTrue(stmt.step(), "the inserted watch_logs row must be queryable back")
                    assertEquals("media-tv", stmt.getText(0))
                    assertEquals("episode-1", stmt.getText(1))
                    assertEquals(1700200000000L, stmt.getLong(2))
                    assertEquals(1800L, stmt.getLong(3))
                }
        }
    }

    /**
     * Proves every nullable column across all four new tables (`movie_details.runtimeMinutes`/
     * `watchedAt`, `tv_details.totalSeasons`, `episodes.title`/`airDate`/`watchedAt`,
     * `watch_logs.episodeId`/`durationSeconds`) really is nullable, not merely absent from
     * [migrate5To6_newTablesAcceptRows_withRealValues]'s happy-path insert. This project treats
     * `null` as "unknown" and is careful to keep it distinct from `0`/`""` (see
     * [com.hub.media.core.database.entities.MovieDetailsEntity.runtimeMinutes]'s KDoc for the
     * canonical statement of that rule, echoed on every one of these columns) -- a stray `NOT
     * NULL` on any of them would silently corrupt a future stat that sums runtimes/durations, by
     * forcing "unknown" to be recorded as some placeholder value instead of excluded.
     */
    @Test
    fun migrate5To6_newTablesAcceptNulls_inEveryNullableColumn() {
        helper.createDatabase(5).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-movie-null', 'MOVIE', 'Unwatched Movie', NULL, NULL, 1700000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-tv-null', 'TV_SHOW', 'Unstarted Show', NULL, NULL, 1700000000000, NULL)",
            )
        }

        helper.runMigrationsAndValidate(6, listOf(MIGRATION_5_6)).use { db ->
            // status is NOT NULL on both movie_details and tv_details -- only the columns the task
            // calls out (runtimeMinutes/watchedAt/totalSeasons/title/airDate/episodeId/
            // durationSeconds) are exercised as NULL here.
            db.execSQL(
                "INSERT INTO movie_details (mediaId, runtimeMinutes, status, watchedAt) " +
                    "VALUES ('media-movie-null', NULL, 'WATCHLIST', NULL)",
            )
            db
                .prepare("SELECT runtimeMinutes, watchedAt FROM movie_details WHERE mediaId = 'media-movie-null'")
                .use { stmt ->
                    assertTrue(stmt.step())
                    assertTrue(stmt.isNull(0), "runtimeMinutes must accept NULL")
                    assertTrue(stmt.isNull(1), "watchedAt must accept NULL")
                }

            db.execSQL(
                "INSERT INTO tv_details (mediaId, totalSeasons, status) VALUES ('media-tv-null', NULL, 'WATCHLIST')",
            )
            db.prepare("SELECT totalSeasons FROM tv_details WHERE mediaId = 'media-tv-null'").use { stmt ->
                assertTrue(stmt.step())
                assertTrue(stmt.isNull(0), "totalSeasons must accept NULL")
            }

            db.execSQL(
                "INSERT INTO episodes (id, mediaId, seasonNumber, episodeNumber, title, airDate, watchedAt) " +
                    "VALUES ('episode-null', 'media-tv-null', 1, 1, NULL, NULL, NULL)",
            )
            db.prepare("SELECT title, airDate, watchedAt FROM episodes WHERE id = 'episode-null'").use { stmt ->
                assertTrue(stmt.step())
                assertTrue(stmt.isNull(0), "title must accept NULL (the quick-fill default)")
                assertTrue(stmt.isNull(1), "airDate must accept NULL")
                assertTrue(stmt.isNull(2), "watchedAt must accept NULL (an unwatched episode)")
            }

            // A movie watch log: no episode to point at, and an unknown duration.
            db.execSQL(
                "INSERT INTO watch_logs (id, mediaId, episodeId, watchedAt, durationSeconds) " +
                    "VALUES ('log-null', 'media-movie-null', NULL, 1700200000000, NULL)",
            )
            db.prepare("SELECT episodeId, durationSeconds FROM watch_logs WHERE id = 'log-null'").use { stmt ->
                assertTrue(stmt.step())
                assertTrue(stmt.isNull(0), "episodeId must accept NULL (a film watch, not an episode)")
                assertTrue(stmt.isNull(1), "durationSeconds must accept NULL")
            }
        }
    }

    /**
     * The `episodes` unique index (`index_episodes_mediaId_seasonNumber_episodeNumber`) must
     * actually be enforced, not merely present in `sqlite_master` -- see
     * [com.hub.media.core.database.entities.EpisodeEntity]'s KDoc for why duplicate (mediaId,
     * seasonNumber, episodeNumber) rows would corrupt quick-fill (a season-count correction would
     * silently duplicate every already-generated episode instead of erroring). Asserted by
     * provoking the actual failure, not just checking that one insert succeeds.
     */
    @Test
    fun migrate5To6_episodesUniqueIndex_rejectsDuplicateSeasonAndEpisodeNumber() {
        helper.createDatabase(5).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-tv', 'TV_SHOW', 'Test Show', 2015, NULL, 1700000000000, NULL)",
            )
        }

        helper.runMigrationsAndValidate(6, listOf(MIGRATION_5_6)).use { db ->
            db.execSQL(
                "INSERT INTO episodes (id, mediaId, seasonNumber, episodeNumber, title, airDate, watchedAt) " +
                    "VALUES ('episode-1', 'media-tv', 1, 1, 'Pilot', NULL, NULL)",
            )

            val exception =
                assertFailsWith<SQLiteException> {
                    db.execSQL(
                        "INSERT INTO episodes (id, mediaId, seasonNumber, episodeNumber, title, airDate, watchedAt) " +
                            "VALUES ('episode-2', 'media-tv', 1, 1, 'Duplicate Pilot', NULL, NULL)",
                    )
                }
            assertTrue(
                exception.message.orEmpty().contains("UNIQUE", ignoreCase = true),
                "the failure must be the unique-index violation, not some other error: ${exception.message}",
            )

            db.prepare("SELECT COUNT(*) FROM episodes WHERE mediaId = 'media-tv'").use { stmt ->
                assertTrue(stmt.step())
                assertEquals(1, stmt.getInt(0), "the rejected duplicate must not have been inserted")
            }
        }
    }

    /**
     * `ON DELETE CASCADE` works for both new relationships -- `episodes` -> `media_items` and
     * `watch_logs` -> `episodes` -- when SQLite foreign key enforcement is turned on for the
     * connection.
     *
     * ### Why this test enables `PRAGMA foreign_keys = ON` itself
     * SQLite ships with foreign key enforcement off by default per connection, and
     * [MigrationTestHelper] opens a bare one via [BundledSQLiteDriver] without turning it on. So
     * this pragma is required **here**, in the migration harness, or the cascade silently would not
     * fire and the test would prove nothing.
     *
     * **This is a fact about the migration helper, not about the app.** A database built the normal
     * way does enforce these constraints, which is why
     * `BookDetailsDaoTest.cascadeDelete_removesBookDetailsWhenMediaItemDeleted` passes: it deletes a
     * `media_items` row through the DAO and asserts the `book_details` row goes with it, against a
     * database from the ordinary builder. That test would fail outright if cascades were inert at
     * runtime.
     *
     * Worth stating plainly because the opposite conclusion is easy to reach from reading Room's
     * sources alone -- `RoomConnectionManager.configureConnection` sets `journal_mode`,
     * `synchronous`, `busy_timeout`, `temp_store` and `recursive_triggers` with no `foreign_keys`
     * among them -- and concluding from that absence that the app orphans rows on delete. It does
     * not. The pragma below closes a gap in **this harness**; it is not evidence of one in
     * production.
     *
     * What this test therefore proves is that `MIGRATION_5_6`'s DDL declares the cascades
     * correctly. That the app exercises them is proved by the DAO test named above.
     */
    @Test
    fun migrate5To6_cascadeDelete_removesEpisodesAndWatchLogs() {
        helper.createDatabase(5).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-tv', 'TV_SHOW', 'Test Show', 2015, NULL, 1700000000000, NULL)",
            )
        }

        helper.runMigrationsAndValidate(6, listOf(MIGRATION_5_6)).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")

            db.execSQL(
                "INSERT INTO episodes (id, mediaId, seasonNumber, episodeNumber, title, airDate, watchedAt) " +
                    "VALUES ('episode-1', 'media-tv', 1, 1, 'Pilot', NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO episodes (id, mediaId, seasonNumber, episodeNumber, title, airDate, watchedAt) " +
                    "VALUES ('episode-2', 'media-tv', 1, 2, 'Second Episode', NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO watch_logs (id, mediaId, episodeId, watchedAt, durationSeconds) " +
                    "VALUES ('log-1', 'media-tv', 'episode-1', 1700200000000, 1800)",
            )
            db.execSQL(
                "INSERT INTO watch_logs (id, mediaId, episodeId, watchedAt, durationSeconds) " +
                    "VALUES ('log-2', 'media-tv', 'episode-2', 1700200100000, 1200)",
            )

            // Deleting one episode must cascade to only the watch_logs row pointing at it.
            db.execSQL("DELETE FROM episodes WHERE id = 'episode-1'")
            db.prepare("SELECT id FROM watch_logs ORDER BY id").use { stmt ->
                val remaining = mutableListOf<String>()
                while (stmt.step()) remaining.add(stmt.getText(0))
                assertEquals(
                    listOf("log-2"),
                    remaining,
                    "deleting episode-1 must cascade-delete log-1 (which pointed at it) but leave log-2 alone",
                )
            }

            // Deleting the media_items row must cascade to every remaining episode and watch log.
            db.execSQL("DELETE FROM media_items WHERE id = 'media-tv'")
            db.prepare("SELECT COUNT(*) FROM episodes").use { stmt ->
                assertTrue(stmt.step())
                assertEquals(0, stmt.getInt(0), "deleting media-tv must cascade-delete its remaining episodes")
            }
            db.prepare("SELECT COUNT(*) FROM watch_logs").use { stmt ->
                assertTrue(stmt.step())
                assertEquals(0, stmt.getInt(0), "deleting media-tv must cascade-delete its remaining watch_logs")
            }
        }
    }

    /**
     * An empty v5 database still migrates cleanly and leaves the four new tables present but
     * empty, same shape as [migrate4To5_emptyDatabase_validatesCleanly].
     */
    @Test
    fun migrate5To6_emptyDatabase_validatesCleanly() {
        helper.createDatabase(5).use { }

        helper.runMigrationsAndValidate(6, listOf(MIGRATION_5_6)).use { db ->
            listOf("movie_details", "tv_details", "episodes", "watch_logs").forEach { table ->
                db.prepare("SELECT COUNT(*) FROM $table").use { stmt ->
                    assertTrue(stmt.step())
                    assertEquals(
                        0,
                        stmt.getInt(0),
                        "$table must exist and be empty after migrating from an empty v5 database",
                    )
                }
            }
        }
    }

    // ==========================================================================================
    // ROADMAP Task 15: a migration failure must now be logged before it propagates.
    // ==========================================================================================

    /**
     * Forces [MIGRATION_1_2] to genuinely fail: called directly (bypassing [helper]/`AppDatabase`
     * entirely) against a bare, freshly-opened SQLite file with none of this app's tables in it.
     * The migration's first statement (`CREATE TABLE reading_sessions_new ...`) succeeds regardless
     * (SQLite does not check a `FOREIGN KEY` target exists at `CREATE TABLE` time), but its second
     * statement -- `INSERT INTO reading_sessions_new ... SELECT ... FROM reading_sessions` -- fails
     * with "no such table: reading_sessions", deterministically and without needing
     * [MigrationTestHelper]/schema validation at all.
     *
     * Before ROADMAP Task 15, this exact exception propagated with nothing logged anywhere.
     * `loggedMigration` (`Migrations.kt`) must now record it at ERROR under a
     * `Migration_<from>_<to>`-shaped tag before rethrowing it unchanged -- proven here by swapping
     * [AppLogger]'s delegate for a [RecordingLogger] for the duration of this one test, then
     * restoring [AppLogger] to its default configuration afterward so no other test in this suite
     * (or run after it in the same JVM process) observes a leaked delegate/threshold.
     */
    @Test
    fun migrate1To2_missingSourceTable_logsErrorBeforeRethrowing() {
        val recorder = RecordingLogger()
        AppLogger.configure(minLevel = LogLevel.DEBUG, delegate = recorder)
        try {
            val bareDbPath = testDbDir.resolve("bare-for-migration-failure.db")
            BundledSQLiteDriver().open(bareDbPath.toString()).use { connection ->
                assertFailsWith<SQLiteException> { MIGRATION_1_2.migrate(connection) }
            }

            val errorEntries = recorder.entries.filter { it.level == LogLevel.ERROR }
            assertTrue(errorEntries.isNotEmpty(), "a migration failure must be logged at ERROR")
            assertTrue(errorEntries.any { it.tag == "Migration_1_2" })
            assertTrue(errorEntries.all { it.throwable != null }, "the underlying exception must be attached")
            // The identifier rule (Logger's KDoc): this message can only ever be the fixed
            // "schema migration N -> M failed" text -- there is no row/book data in scope at this
            // catch site for it to leak even if it wanted to.
            assertEquals("schema migration 1 -> 2 failed", errorEntries.single().message)
        } finally {
            // Restore AppLogger's default configuration -- this test is the only one in the suite
            // that reconfigures the process-wide singleton, and it must not leak into any test that
            // runs after it (in this file or, since JVM tests in one module share a process, any
            // other jvmTest suite run in the same invocation).
            AppLogger.configure(minLevel = LogLevel.WARN)
        }
    }
}
