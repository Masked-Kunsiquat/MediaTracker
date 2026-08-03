package com.hub.media.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Table names present in **every** schema version this app has ever shipped (v1 through
 * [APP_DATABASE_VERSION], confirmed against `shared/schemas/.../1.json` through `.../4.json`) --
 * deliberately excludes [com.hub.media.core.database.entities.AppSettingEntity]'s `app_settings`
 * table, which [MIGRATION_3_4] only introduced at v4. A legitimate older backup is explicitly meant
 * to pass [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase.stage] (Room's
 * normal migration chain upgrades it on next open, see that class's KDoc) -- checking for a table a
 * later migration added would wrongly reject exactly the older-backup case this feature is built to
 * support.
 */
private val REQUIRED_TABLE_NAMES = listOf("media_items", "book_details", "external_identifiers", "reading_sessions")

/**
 * Opens the candidate file at [path] with a real SQLite connection and runs the checks a 100-byte
 * header parse ([parseSqliteHeader]) cannot: whether the file is *actually* an intact SQLite
 * database, and whether it is recognizably *this app's* database rather than some other program's.
 *
 * Called by [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase.stage] only
 * after the header check already passed (magic bytes present, `user_version` not newer than this
 * build) -- so this never runs against something that isn't SQLite at all or is unambiguously from
 * a future app version, and the header check keeps doing the cheap job of producing those two
 * specific, actionable messages.
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
 * 2. **Expected-table check** -- queries `sqlite_master` for every name in [REQUIRED_TABLE_NAMES]
 *    (the tables present since this app's very first shipped schema). A structurally-valid SQLite
 *    file that simply isn't a MediaTracker database at all (some other app's file, or a hand-crafted
 *    file that happens to pass `integrity_check`) is refused here rather than being swapped in as an
 *    empty-looking, wrongly-shaped "library."
 *
 * Runs on [Dispatchers.IO]: like the raw file reads in `DatabaseFileOps`, opening a real SQLite
 * connection and running these queries is blocking I/O, never the caller's own dispatcher.
 *
 * @return `null` if the file passed both checks; otherwise a user-facing description of which check
 *   failed and why, suitable for embedding directly in [com.hub.media.core.util.Resource.Error].
 */
internal suspend fun validateStagedDatabaseIntegrity(path: String): String? = withContext(Dispatchers.IO) {
    try {
        var failureReason: String? = null
        BundledSQLiteDriver().open(path).use { connection ->
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
                val missing = REQUIRED_TABLE_NAMES.filterNot { it in foundTables }
                if (missing.isNotEmpty()) {
                    failureReason = "this doesn't look like a MediaTracker library (missing table" +
                        (if (missing.size > 1) "s" else "") + ": ${missing.joinToString(", ")})"
                }
            }
        }
        failureReason
    } catch (e: Exception) {
        "the file could not be opened as a SQLite database (${e.message ?: e::class.simpleName})"
    }
}
