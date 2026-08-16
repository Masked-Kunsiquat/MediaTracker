package com.hub.media.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.error

/**
 * Wraps a [Migration.migrate] body with logging (ROADMAP Task 15): before this, a migration failure
 * (e.g. `execSQL` throwing mid-`ALTER TABLE`/rebuild) propagated as a bare exception straight out of
 * Room's database-open path with nothing recorded anywhere -- undiagnosable outside a debugger, and
 * on a release build, effectively undiagnosable at all (the app simply fails to open its database).
 * Deliberately rethrows [e] unchanged after logging -- a migration failure must still fail the
 * database open (silently swallowing it and continuing would risk running against a half-migrated
 * schema, exactly the kind of silent corruption AGENTS.md §1 rules out); this only makes the failure
 * diagnosable. Logs the schema-version transition only (see [Logger][com.hub.media.core.util.Logger]'s
 * identifier rule) -- never any row data a migration happened to be touching when it failed.
 */
private inline fun loggedMigration(
    fromVersion: Int,
    toVersion: Int,
    body: () -> Unit,
) {
    try {
        body()
    } catch (e: Exception) {
        AppLogger.error("Migration_${fromVersion}_$toVersion", e) {
            "schema migration $fromVersion -> $toVersion failed"
        }
        throw e
    }
}

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
public val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) =
            loggedMigration(1, 2) {
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
                        "(`id`, `mediaId`, `timestampStart`, `timestampEnd`, `durationSeconds`, " +
                        "`startUnit`, `endUnit`, `deltaPages`, `notes`) " +
                        "SELECT `id`, `mediaId`, `timestampStart`, `timestampEnd`, `durationSeconds`, " +
                        "`startUnit`, `endUnit`, `deltaPages`, `notes` " +
                        "FROM `reading_sessions`",
                )
                connection.execSQL("DROP TABLE `reading_sessions`")
                connection.execSQL("ALTER TABLE `reading_sessions_new` RENAME TO `reading_sessions`")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_sessions_mediaId` ON `reading_sessions` (`mediaId`)",
                )
            }
    }

/**
 * Schema v2 -> v3 (ROADMAP Task 6 Phase C): adds
 * [com.hub.media.core.database.entities.BookDetailsEntity.status] and
 * [com.hub.media.core.database.entities.BookDetailsEntity.finishedAt] to `book_details` — see
 * those properties' KDoc for the full column-level rationale.
 *
 * ### Why `ALTER TABLE ... ADD COLUMN`, not a table rebuild
 * Unlike [MIGRATION_1_2] (which had to *relax* an existing `NOT NULL` constraint — something
 * SQLite cannot do via `ALTER TABLE`), this migration only *adds* columns, and SQLite's
 * `ALTER TABLE ADD COLUMN` supports both an addition with a constant `DEFAULT` (even when the new
 * column is itself `NOT NULL`, as long as every existing row gets a real, non-null value from that
 * default) and a nullable addition with no default (existing rows simply get `NULL`). Both of this
 * migration's two new columns fit one of those two supported shapes directly, so no
 * create-copy-drop-rename rebuild is needed — verified against the Room-generated
 * `shared/schemas/.../3.json`, which records exactly these two `ALTER TABLE` statements as
 * `book_details`'s only schema delta from v2.
 *
 * ### `status`: `NOT NULL DEFAULT 'TO_READ'`, then derived to `'READING'` where sessions exist
 * Every pre-existing row needs a real, non-null [com.hub.media.core.database.entities.ReadingStatus]
 * the moment this column exists — there is no "unknown" value in that enum to fall back on (unlike
 * schema v2's nullable `durationSeconds`, this concept has no legitimate "we don't know" state; the
 * enum's own semantics only distinguish never-started, in-progress, finished, and abandoned, not
 * "not yet tracked". `'TO_READ'` is the honest default for a book with zero corroborating
 * evidence: nothing in the pre-v3 schema records whether a book was ever opened.
 *
 * But a book that already has one or more `reading_sessions` rows is demonstrably NOT still
 * "to read" — someone has logged time against it, whether or not it's finished. Leaving every
 * pre-existing row at the blanket `'TO_READ'` default would be a strictly worse migration outcome
 * than the one available for free from data already in the database: a second statement
 * (`UPDATE ... WHERE EXISTS (SELECT 1 FROM reading_sessions ...)`) promotes exactly those rows to
 * `'READING'` — not `'FINISHED'`, since a session existing says only "started," never "done" (there
 * is no "book completed" signal anywhere in the pre-v3 schema to derive [FINISHED][com.hub.media.core.database.entities.ReadingStatus.FINISHED]
 * from), and not [DNF][com.hub.media.core.database.entities.ReadingStatus.DNF] for the same reason
 * (abandonment is a deliberate user decision this migration has no signal for). A book with zero
 * sessions keeps the blanket `'TO_READ'` default from the `ALTER TABLE` itself — no second
 * statement needed for that case, since `ADD COLUMN ... DEFAULT` already back-filled it.
 *
 * ### `finishedAt`: plain nullable `INTEGER`, no default, no derivation
 * Every pre-existing row becomes `NULL` — including the rows just promoted to `'READING'` above.
 * This is safe (not lossy) specifically *because* of the `status` derivation rule immediately
 * above: no pre-existing row can ever end up `'FINISHED'` by this migration (only `'READING'` or
 * `'TO_READ'`), so there is no row for which `finishedAt` "should" have a real backfilled value but
 * doesn't. A future user-driven status change to `'FINISHED'`
 * ([com.hub.media.features.books.data.BookRepository.updateReadingStatus] /
 * [com.hub.media.features.books.data.BookRepository.updateBookMetadata]) is what populates this
 * column going forward — see its KDoc.
 *
 * See `MigrationTest` (jvmTest) for a test that seeds a v2 database with a book that has a
 * `reading_sessions` row and one that doesn't, runs this migration, and asserts the former lands on
 * `'READING'` and the latter on `'TO_READ'`, both with `finishedAt` null and every pre-existing
 * column value intact.
 */
public val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) =
            loggedMigration(2, 3) {
                connection.execSQL(
                    "ALTER TABLE `book_details` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'TO_READ'",
                )
                connection.execSQL(
                    "ALTER TABLE `book_details` ADD COLUMN `finishedAt` INTEGER DEFAULT NULL",
                )
                connection.execSQL(
                    "UPDATE `book_details` SET `status` = 'READING' " +
                        "WHERE EXISTS (SELECT 1 FROM `reading_sessions` WHERE `reading_sessions`.`mediaId` = `book_details`.`mediaId`)",
                )
            }
    }

/**
 * Schema v3 -> v4 (ROADMAP Task 7 Phase A): bundles two independent schema changes into one
 * migration rather than two, since both land in the same release —
 * [com.hub.media.core.database.entities.BookDetailsEntity.trackingMode] (see
 * [com.hub.media.core.database.entities.TrackingMode]'s KDoc) and the new `app_settings` key-value
 * table (see [com.hub.media.core.database.entities.AppSettingEntity]'s KDoc).
 *
 * ### `ALTER TABLE ... ADD COLUMN`, not a table rebuild — same reasoning as [MIGRATION_2_3]
 * `trackingMode` is added `NOT NULL` with a constant `DEFAULT`, which SQLite's `ALTER TABLE ADD
 * COLUMN` supports directly (verified against the Room-generated `shared/schemas/.../4.json`,
 * which records exactly this shape as `book_details`'s only column-level delta from v3) — no
 * create-copy-drop-rename rebuild needed, exactly as [MIGRATION_2_3] didn't need one for `status`/
 * `finishedAt`.
 *
 * ### `trackingMode`: `NOT NULL DEFAULT 'PAGES'`, then derived to `'PERCENT'` where `totalPages IS NULL`
 * **The derivation rule is chosen to exactly reproduce the app's pre-v4 *inferred* behavior, not to
 * introduce a new one.** Before this column existed, every place on the Book Detail screen that
 * needed to distinguish page-based from percent-based tracking (progress formatting, and the
 * manual/pending-session dialogs' derived-`deltaPages` behavior from ROADMAP Task 6 Phase B) used
 * `totalPages != null` as that signal. If this migration derived `trackingMode` any other way, an
 * existing book could silently flip which mode its own history is displayed/edited under the moment
 * the user upgrades — the exact invisible-flip problem this whole phase exists to fix, just moved
 * from "editing total pages" to "upgrading the app." So: `ADD COLUMN ... DEFAULT 'PAGES'`
 * back-fills every existing row to `'PAGES'` for free, then a second statement
 * (`UPDATE ... WHERE totalPages IS NULL`) demotes exactly the rows that were being treated as
 * percent-based under the old inference to `'PERCENT'` — a row with a non-null `totalPages` needs
 * no second statement, it already landed on the correct value from the column default alone. This
 * mirrors [MIGRATION_2_3]'s two-statement shape (`ALTER ... DEFAULT` + a targeted `UPDATE` for the
 * subset that needs a different value) exactly.
 *
 * ### Ingestion default going forward (not this migration's concern, documented for completeness)
 * Freshly-ingested books apply the identical rule at insert time — see
 * [com.hub.media.features.books.data.BookRepository.addBook]'s KDoc — so the same "known page count
 * -> PAGES, otherwise -> PERCENT" logic governs both a pre-v4 row's one-time migration outcome and
 * every new row's initial value, without being duplicated as separate reasoning in two places.
 *
 * ### `app_settings`: a brand-new table, created empty
 * Unlike `book_details`'s column additions, `app_settings` did not exist in any prior schema
 * version, so there is no existing data to preserve or derive into it — this is a plain `CREATE
 * TABLE`, matching the Room-generated `shared/schemas/.../4.json` shape for this table exactly
 * (`key TEXT NOT NULL PRIMARY KEY`, `value TEXT NOT NULL`, no indices/foreign keys). See
 * [com.hub.media.core.database.entities.AppSettingEntity]'s KDoc for why a key-value shape was
 * chosen over a typed single-row table, and
 * [com.hub.media.features.settings.data.SettingsRepository] for the typed accessors built on top of
 * it. No setting has defined semantics yet (ROADMAP Task 7 Phase B is the first consumer) — this
 * migration only needs to make the table exist and be writable.
 *
 * See `MigrationTest` (jvmTest) for a test that seeds a v3 database with a book that has a
 * `totalPages` value and one that doesn't, runs this migration, and asserts the former lands on
 * `trackingMode = 'PAGES'` and the latter on `'PERCENT'`, with every pre-existing column intact and
 * `app_settings` present and insertable.
 */
public val MIGRATION_3_4: Migration =
    object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) =
            loggedMigration(3, 4) {
                connection.execSQL(
                    "ALTER TABLE `book_details` ADD COLUMN `trackingMode` TEXT NOT NULL DEFAULT 'PAGES'",
                )
                connection.execSQL(
                    "UPDATE `book_details` SET `trackingMode` = 'PERCENT' WHERE `totalPages` IS NULL",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_settings` (" +
                        "`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
                )
            }
    }

/**
 * Schema v4 -> v5 (ROADMAP Task 9 Phase A): adds
 * [com.hub.media.core.database.entities.BookDetailsEntity.authors] to `book_details` — see that
 * property's KDoc for the full denormalized-single-column-vs-authors-table rationale and the
 * `AUTHOR_SEPARATOR` encoding decision.
 *
 * ### `ALTER TABLE ... ADD COLUMN`, not a table rebuild — same reasoning as [MIGRATION_2_3]/[MIGRATION_3_4]
 * `authors` is a plain nullable `TEXT` column with no `DEFAULT` clause, which SQLite's `ALTER TABLE
 * ADD COLUMN` supports directly (verified against the Room-generated `shared/schemas/.../5.json`,
 * which records exactly this shape as `book_details`'s only delta from v4) — no
 * create-copy-drop-rename rebuild needed.
 *
 * ### Every pre-existing row becomes `NULL` — honest, not lossy
 * Unlike `status`/`trackingMode` (schema v3/v4), there is no pre-v5 signal anywhere in this
 * database that this migration could derive an author from — no prior column, table, or provider
 * response was ever persisted. Leaving every pre-existing row's `authors` at `NULL` ("unknown") is
 * therefore the only honest outcome, exactly matching [com.hub.media.core.database.entities.BookDetailsEntity.authors]'
 * own `null` = "no author on record" convention — this is expected, not a data-loss bug: most
 * pre-v5 books will show no author until re-fetched or hand-edited, which is stated up front rather
 * than papered over with a fabricated placeholder.
 *
 * ### Ingestion going forward (not this migration's concern, documented for completeness)
 * Freshly-ingested books apply [com.hub.media.core.database.entities.joinAuthors] to
 * [com.hub.media.features.books.network.BookMetadata.authors] at insert time — see
 * [com.hub.media.features.books.data.BookRepository.addBook]'s KDoc — so only pre-v5 rows are ever
 * affected by this migration's `NULL` backfill.
 *
 * See `MigrationTest` (jvmTest) for a test that seeds a v4 database with existing `book_details`
 * rows, runs this migration, and asserts every pre-existing column survives untouched with the new
 * `authors` column landing `NULL`, plus a "new capability" test proving the relaxed schema accepts
 * a real `authors` value going forward.
 */
public val MIGRATION_4_5: Migration =
    object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) =
            loggedMigration(4, 5) {
                connection.execSQL(
                    "ALTER TABLE `book_details` ADD COLUMN `authors` TEXT DEFAULT NULL",
                )
            }
    }
