package com.hub.media.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Schema v1 -> v2 (ROADMAP Task 5 pre-phase): makes [com.hub.media.core.database.entities.ReadingSessionEntity.durationSeconds]
 * nullable (`null` = duration unknown, distinct from a legitimate `0`-second session — see that
 * entity's KDoc). Room v1 froze this column `NOT NULL` (AGENTS.md §8), and SQLite cannot `ALTER
 * COLUMN` to relax a `NOT NULL` constraint in place, so this performs the standard SQLite
 * "rebuild the table" migration:
 *
 * 1. Create a new `reading_sessions_new` table with the exact v2 shape (verified against the
 *    Room-exported `shared/schemas/.../2.json`, not hand-derived).
 * 2. Copy every existing row across unchanged (`durationSeconds` was `NOT NULL` in v1, so every
 *    v1 row already has a real value — nothing becomes `null` as a side effect of this migration
 *    itself; rows only ever get a `null` duration afterward, via a manual entry that omits it).
 * 3. Drop the old table and rename the new one into its place.
 * 4. Re-create the `mediaId` index, since it is not carried over automatically by step 3 (the
 *    index was defined against the old table's name/definition, not against `reading_sessions_new`).
 *
 * All other tables/columns/indices/foreign keys are untouched — this migration only ever touches
 * `reading_sessions`.
 *
 * No rows are dropped by this migration: the `INSERT INTO ... SELECT` copies every row from the
 * old table before it is dropped, in the same transaction Room already wraps each [Migration] in
 * (see [Migration]'s KDoc), so a real on-device v1 database with existing reading sessions keeps
 * every one of them after upgrading. See `MigrationTest` (jvmTest) for a test that inserts v1 rows
 * (including a real duration and a `0`-duration edge case), runs this migration, and asserts both
 * rows still exist afterward with their values intact.
 */
public val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `reading_sessions_new` (" +
                "`id` TEXT NOT NULL, " +
                "`mediaId` TEXT NOT NULL, " +
                "`timestampStart` INTEGER NOT NULL, " +
                "`timestampEnd` INTEGER NOT NULL, " +
                "`durationSeconds` INTEGER, " +
                "`startUnit` REAL NOT NULL, " +
                "`endUnit` REAL NOT NULL, " +
                "`deltaPages` INTEGER, " +
                "`notes` TEXT, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`mediaId`) REFERENCES `media_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "INSERT INTO `reading_sessions_new` " +
                "(`id`, `mediaId`, `timestampStart`, `timestampEnd`, `durationSeconds`, `startUnit`, `endUnit`, `deltaPages`, `notes`) " +
                "SELECT `id`, `mediaId`, `timestampStart`, `timestampEnd`, `durationSeconds`, `startUnit`, `endUnit`, `deltaPages`, `notes` " +
                "FROM `reading_sessions`",
        )
        connection.execSQL("DROP TABLE `reading_sessions`")
        connection.execSQL("ALTER TABLE `reading_sessions_new` RENAME TO `reading_sessions`")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reading_sessions_mediaId` ON `reading_sessions` (`mediaId`)",
        )
    }
}
