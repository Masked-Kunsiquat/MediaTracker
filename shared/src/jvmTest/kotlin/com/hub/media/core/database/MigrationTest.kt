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
 * Validates `MIGRATION_1_2` (`Migrations.kt`) — the schema v1 -> v2 table rebuild that makes
 * [com.hub.media.core.database.entities.ReadingSessionEntity.durationSeconds] nullable (ROADMAP
 * Task 5 pre-phase, see that entity's KDoc). Per AGENTS.md §8, a schema-version bump on a
 * previously-tagged schema requires a tested [androidx.room.migration.Migration] — this is that
 * test.
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
                    assertEquals(if (id == "session-real") 10.0 else 10.0, stmt.getDouble(2), "startUnit for $id")
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
}
