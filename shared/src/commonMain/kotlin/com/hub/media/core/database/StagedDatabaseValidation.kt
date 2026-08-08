package com.hub.media.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [Logger] tag for every log call [validateStagedDatabaseIntegrity] makes. */
private const val TAG = "StagedDatabaseValidation"

/**
 * Table names present in **every** schema version this app has ever shipped (v1 through
 * [APP_DATABASE_VERSION], confirmed against `shared/schemas/.../1.json` through `.../4.json`) --
 * deliberately excludes [com.hub.media.core.database.entities.AppSettingEntity]'s `app_settings`
 * table, which [MIGRATION_3_4] only introduced at v4. A legitimate older backup is explicitly meant
 * to pass [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase.stage] (Room's
 * normal migration chain upgrades it on next open, see that class's KDoc) -- checking for a table a
 * later migration added would wrongly reject exactly the older-backup case this feature is built to
 * support.
 *
 * Used for any candidate reporting a `user_version` older than the version `app_settings` was
 * introduced at ([APP_SETTINGS_INTRODUCED_AT_VERSION]) -- see [requiredTableNamesFor], which picks
 * between this set and [REQUIRED_TABLE_NAMES_WITH_APP_SETTINGS] based on the candidate's own
 * reported version, rather than always using this smaller set for every candidate regardless of
 * version (which would let a v4 candidate genuinely missing `app_settings` pass undetected).
 */
private val REQUIRED_TABLE_NAMES = listOf("media_items", "book_details", "external_identifiers", "reading_sessions")

/** The `user_version` [MIGRATION_3_4] introduced `app_settings` at -- see [requiredTableNamesFor]. */
private const val APP_SETTINGS_INTRODUCED_AT_VERSION = 4

/**
 * [REQUIRED_TABLE_NAMES] plus `app_settings`, for any candidate reporting a `user_version` at or
 * after [APP_SETTINGS_INTRODUCED_AT_VERSION] -- see [requiredTableNamesFor].
 */
private val REQUIRED_TABLE_NAMES_WITH_APP_SETTINGS = REQUIRED_TABLE_NAMES + "app_settings"

/**
 * Picks the table set [validateStagedDatabaseIntegrity] requires, based on the candidate's own
 * [schemaVersion] rather than always using the pre-`app_settings` set for every candidate: a
 * candidate reporting `user_version` [APP_SETTINGS_INTRODUCED_AT_VERSION] or newer genuinely ought
 * to have `app_settings` (its own schema says so), so one that's missing it despite claiming that
 * version is a corrupt or hand-crafted file, not a legitimate older backup, and must be rejected --
 * not silently waved through the way always excluding `app_settings` from the check would.
 */
private fun requiredTableNamesFor(schemaVersion: Int): List<String> =
    if (schemaVersion >= APP_SETTINGS_INTRODUCED_AT_VERSION) {
        REQUIRED_TABLE_NAMES_WITH_APP_SETTINGS
    } else {
        REQUIRED_TABLE_NAMES
    }

/**
 * Opens the candidate file at [path] with a real, **read-only** SQLite connection and runs the
 * checks a 100-byte header parse ([parseSqliteHeader]) cannot: whether the file is *actually* an
 * intact SQLite database, and whether it is recognizably *this app's* database rather than some
 * other program's.
 *
 * Called by [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase.stage] only
 * after the header check already passed (magic bytes present, `user_version` not newer than this
 * build) -- so this never runs against something that isn't SQLite at all or is unambiguously from
 * a future app version, and the header check keeps doing the cheap job of producing those two
 * specific, actionable messages. [schemaVersion] is that same already-parsed `user_version` --
 * passed in rather than re-read here, since [stage] already has it -- and picks which table set
 * [requiredTableNamesFor] requires (see below).
 *
 * ### Why the header check alone is not enough (this task's whole reason for existing)
 * A file's first 100 bytes being well-formed says nothing about the other pages: a file truncated
 * partway through a write, corrupted in transit, or simply someone else's unrelated SQLite database
 * (a browser's cookie store, another app's cache -- anything starting with the same standard
 * `"SQLite format 3\0"` magic string) can pass the header check and still be catastrophically wrong
 * to swap in as this app's entire library, with no cloud copy behind it to recover from (AGENTS.md
 * §1). This function closes that gap with two real checks:
 *
 * 1. **`PRAGMA integrity_check`** -- SQLite's own, most thorough structural check (deliberately
 *    `integrity_check`, not the faster `quick_check`: this runs once, on a personal-sized library, at
 *    the single point where accepting a bad file destroys the live one -- AGENTS.md §1 puts user
 *    data safety ahead of shaving a likely-sub-second check). Anything other than the single row
 *    `"ok"` -- or the connection/query throwing outright, which is exactly what a truncated file
 *    does (SQLite reports "database disk image is malformed" rather than silently returning
 *    partial data) -- is treated as corrupt.
 * 2. **Expected-table check** -- queries `sqlite_master` for every name [requiredTableNamesFor]
 *    returns for [schemaVersion] (the tables present since this app's very first shipped schema,
 *    plus `app_settings` once the candidate itself claims a `user_version` new enough to have it).
 *    A structurally-valid SQLite file that simply isn't a MediaTracker database at all (some other
 *    app's file, or a hand-crafted file that happens to pass `integrity_check`) is refused here
 *    rather than being swapped in as an empty-looking, wrongly-shaped "library" -- and so is a file
 *    claiming a `user_version` whose tables don't actually match that claim.
 *
 * ### Opened read-only -- this is validation, not a write
 * [BundledSQLiteDriver.open] is called with [SQLITE_OPEN_READONLY] rather than its default flags.
 * Without it, merely *validating* a candidate could itself modify the file on disk and spawn
 * `-wal`/`-shm` sidecars next to it -- surprising and unwanted side effects for a function whose
 * entire job, at this point in [stage], is to look without touching (the destructive work is
 * [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase.commit]'s job alone, and
 * only after explicit user confirmation).
 *
 * Runs on [Dispatchers.IO]: like the raw file reads in `DatabaseFileOps`, opening a real SQLite
 * connection and running these queries is blocking I/O, never the caller's own dispatcher.
 *
 * @param schemaVersion The candidate's own `PRAGMA user_version`, as already parsed by
 *   [parseSqliteHeader] in [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase.stage].
 * @param logger Where a failed validation is recorded (ROADMAP Task 15), including the underlying
 *   [Throwable] when the candidate couldn't even be opened/queried -- the caller's returned `String`
 *   only ever carries `e.message`/the exception's class name, not the full exception this attaches.
 *   Defaults to [AppLogger].
 * @return `null` if the file passed both checks; otherwise a user-facing description of which check
 *   failed and why, suitable for embedding directly in [com.hub.media.core.util.Resource.Error].
 */
internal suspend fun validateStagedDatabaseIntegrity(
    path: String,
    schemaVersion: Int,
    logger: Logger = AppLogger,
): String? =
    withContext(Dispatchers.IO) {
        try {
            var failureReason: String? = null
            BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY).use { connection ->
                connection.prepare("PRAGMA integrity_check").use { statement ->
                    failureReason = if (statement.step()) {
                        val verdict = statement.getText(0)
                        if (verdict != "ok") "failed SQLite's integrity check: $verdict" else null
                    } else {
                        "SQLite's integrity check produced no result"
                    }
                }
                if (failureReason == null) {
                    val foundTables = mutableSetOf<String>()
                    connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { statement ->
                        while (statement.step()) foundTables += statement.getText(0)
                    }
                    val missing = requiredTableNamesFor(schemaVersion).filterNot { it in foundTables }
                    if (missing.isNotEmpty()) {
                        failureReason = "this doesn't look like a MediaTracker library (missing table" +
                            (if (missing.size > 1) "s" else "") + ": ${missing.joinToString(", ")})"
                    }
                }
            }
            // Logged here (not just returned as a String) so the failure is diagnosable from the
            // device's own logs even though the caller only ever surfaces this string in a
            // Resource.Error -- see DefaultRestoreDatabaseUseCase's "Logging" KDoc section. The
            // message is a fixed structural description (integrity-check verdict, missing table
            // names) -- schema-level facts, never book/library content.
            failureReason?.let { reason -> logger.warn(TAG) { "staged database failed validation: $reason" } }
            failureReason
        } catch (e: Exception) {
            logger.warn(TAG, e) { "staged database validation threw while opening/querying the candidate" }
            "the file could not be opened as a SQLite database (${e.message ?: e::class.simpleName})"
        }
    }
