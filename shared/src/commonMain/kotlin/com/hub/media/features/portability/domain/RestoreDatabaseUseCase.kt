package com.hub.media.features.portability.domain

import com.hub.media.core.database.APP_DATABASE_VERSION
import com.hub.media.core.database.RestoreMarker
import com.hub.media.core.database.SQLITE_HEADER_SIZE
import com.hub.media.core.database.deleteFileIfExists
import com.hub.media.core.database.fileExists
import com.hub.media.core.database.parseSqliteHeader
import com.hub.media.core.database.preRestoreBackupPath
import com.hub.media.core.database.readFileHeaderBytes
import com.hub.media.core.database.renameFile
import com.hub.media.core.database.writeRestoreMarker
import com.hub.media.core.util.Resource

/**
 * Abstraction over "validate, then swap in, a whole-database restore" so
 * [com.hub.media.ui.RestoreViewModel] can depend on a narrow contract instead of the concrete
 * [DefaultRestoreDatabaseUseCase] -- mirrors [DatabaseBackupUseCase]'s exact reason for existing.
 */
public interface RestoreDatabaseUseCase {
    /** See [DefaultRestoreDatabaseUseCase.stage]. */
    public suspend fun stage(incomingFilePath: String): Resource<StagedRestoreInfo>

    /** See [DefaultRestoreDatabaseUseCase.commit]. */
    public suspend fun commit(staged: StagedRestoreInfo): Resource<Unit>
}

/**
 * The outcome of a successful [DefaultRestoreDatabaseUseCase.stage] call: a candidate file that
 * passed header/version validation and is ready to be swapped in via
 * [DefaultRestoreDatabaseUseCase.commit].
 *
 * @property stagedFilePath Absolute path of the already-copied, already-validated candidate file
 *   (the app layer's own private copy of whatever the user picked via SAF -- see
 *   [DefaultRestoreDatabaseUseCase]'s class KDoc for why staging happens before this use case is
 *   even called).
 * @property schemaVersionFound The candidate's own `PRAGMA user_version`, parsed directly from its
 *   header.
 * @property isOlderSchemaVersion Whether [schemaVersionFound] is older than
 *   [com.hub.media.core.database.APP_DATABASE_VERSION] -- true for a legitimate older backup that
 *   Room will migrate forward on next open; false when it exactly matches the current version.
 *   [stage] already rejects anything *newer*, so those two cases are the only ones a caller ever
 *   sees here.
 */
public data class StagedRestoreInfo(
    public val stagedFilePath: String,
    public val schemaVersionFound: Int,
    public val isOlderSchemaVersion: Boolean,
)

/**
 * End-to-end ".sqlite restore" workflow (ROADMAP Task 8 Phase C) -- the single most dangerous
 * action in this app (AGENTS.md §1): a bad restore doesn't corrupt one book, it replaces the whole
 * library, with no cloud copy to fall back on. Every design choice below favors "refuse loudly and
 * leave the original intact" over "guess and proceed."
 *
 * ### Two phases, deliberately split
 * [stage] is **non-destructive**: it only ever reads the candidate file and (on success) reports
 * what it found. It never touches the live database. This is what runs the moment a file is picked,
 * *before* the user is shown the destructive confirmation dialog -- so that dialog only ever
 * appears for a file already known to be restorable, and a bad file never gets anywhere near "are
 * you sure?" UI. [commit] is the **destructive** phase, run only after the user's explicit
 * confirmation, and only ever touches files, never database rows -- it does not open the swapped-in
 * file with Room at all; the next ordinary app startup does that (see below).
 *
 * ### Validation ([stage])
 * Reads only the candidate's first [com.hub.media.core.database.SQLITE_HEADER_SIZE] bytes (not the
 * whole file) and parses them via [com.hub.media.core.database.parseSqliteHeader] -- no SQLite
 * driver, no Room, no database connection at all:
 * - Not a SQLite file (bad magic bytes) -> refused with a clear message; the candidate file is
 *   deleted (nothing else touched).
 * - `user_version` newer than [com.hub.media.core.database.APP_DATABASE_VERSION] -> refused with a
 *   message telling the user to update the app, rather than letting Room fail obscurely trying to
 *   open a schema it has never seen (this task's explicit brief). Candidate deleted.
 * - `user_version` older than or equal to the current version -> accepted. An **older** version is
 *   legitimate and deliberately allowed through: [com.hub.media.core.database.buildAppDatabase]
 *   registers every tested [androidx.room.migration.Migration] the normal app-startup path already
 *   uses, so the very next time the swapped-in file is opened by the ordinary Room builder, Room
 *   runs that same migration chain automatically -- there is no separate "restore migration" path
 *   to build or trust. `DatabaseBackupRestoreRoundTripTest` (`jvmTest`) proves this concretely: it
 *   builds a real v2-schema file, "restores" it via [commit], reopens it through
 *   [com.hub.media.core.database.buildAppDatabase] exactly like a normal app launch would, and
 *   asserts it ends up on the current schema version with its pre-migration data intact.
 *
 * ### Why staging happens *before* this use case is even called
 * The candidate arrives via SAF (`ActivityResultContracts.OpenDocument`), which only ever hands the
 * app module a `Uri`. Per AGENTS.md §6, only the app module touches `Uri`/`ContentResolver` -- so
 * the app layer streams the picked document's bytes into a private temp file (its own cache
 * directory; no shared-layer involvement needed for that, since it's a plain local file, not a
 * `Uri`) *before* calling [stage] with that temp file's path. This satisfies the task brief's
 * "copy the incoming file to a temp location, validate it there" literally: the app-layer copy *is*
 * the temp location, and [stage] validates that exact file rather than an in-memory copy of
 * whatever bytes happened to be read from the `Uri` stream.
 *
 * ### The swap ([commit]) -- exact sequence, and what a process death does at each step
 * At the point [commit] is called, the app layer has already closed
 * [com.hub.media.ui.AppContainer] (its database connection and HTTP client) -- see
 * [com.hub.media.ui.AppContainer]'s "Ownership" section and the Settings route composable for
 * where that happens. [commit] itself only ever renames/deletes files; every step below is a single
 * filesystem call with **no** intervening suspension point, so the *practical* window for a process
 * death mid-sequence is a single native syscall's duration, not this whole function's:
 *
 * 1. **Move the live database's own `-wal`/`-shm` sidecars aside** (if `AppContainer.close()`
 *    hadn't already made SQLite checkpoint-and-delete them, which it typically does as the last
 *    connection to a WAL-mode database closes) to `<backup>-wal`/`<backup>-shm`, so they travel
 *    with the safety-net backup below rather than being orphaned next to a completely different
 *    file. *Death here*: the live `.db` file itself is untouched; worst case a stray `-wal`/`-shm`
 *    ends up under the wrong name, and the next app launch just doesn't find a sidecar to recover
 *    (SQLite tolerates a missing/mismatched `-wal` file fine) -- no data loss beyond, at most, a few
 *    of the very last WAL frames from *before* this restore was even initiated.
 * 2. **Rename the live `.db` file to [com.hub.media.core.database.preRestoreBackupPath]** (a fixed,
 *    single-generation-deep name -- each restore attempt replaces the previous attempt's safety net
 *    rather than accumulating one backup per restore forever). *Death here*: either this rename
 *    completed or it didn't (a single atomic filesystem call) -- if it didn't, the live file is
 *    exactly as it was; if it did, the live path is briefly empty (see step 3's death case).
 * 3. **Rename the staged, already-validated candidate into the live path.** *Death here* (the one
 *    genuinely dangerous window: live rename succeeded in step 2, this one hasn't happened yet) --
 *    the live path is briefly missing entirely, which would otherwise make Room silently create a
 *    fresh, empty database on the next launch. This is exactly what
 *    [com.hub.media.core.database.selfHealDatabaseIfNeeded] exists to close: called once at the
 *    very start of every [com.hub.media.ui.createAppContainer], before Room ever opens anything, it
 *    notices "live file missing, backup file present" and moves the backup back into place first.
 * 4. **Write the restore-result marker** ([com.hub.media.core.database.writeRestoreMarker]) --
 *    `SUCCESS` or a `FAILURE:<reason>` -- as the unconditional last step, regardless of whether
 *    steps 1-3 succeeded. *Death here*: the swap itself already fully happened (or was already
 *    rolled back on failure -- see below); only the *user-visible feedback* about it is lost, not
 *    any data. The next launch simply shows no restore-outcome message.
 *
 * If step 2's rename fails outright, [commit] returns [Resource.Error] immediately -- nothing else
 * is attempted, the live file (never touched) is still exactly what it was. If step 3's rename
 * fails *after* step 2 already succeeded, [commit] immediately attempts to rename the safety-net
 * backup back into the live path before returning [Resource.Error] -- restoring the pre-restore
 * state rather than leaving the user with no database at all. Only if that rollback attempt *also*
 * fails (disk full, permission revoked mid-operation -- not expected in practice, since the
 * identical rename direction just succeeded moments earlier) does [commit] surface an error naming
 * [com.hub.media.core.database.preRestoreBackupPath] explicitly, so the file can be recovered by
 * hand if the automatic path is ever exhausted.
 *
 * ### Why a full process restart follows every [commit] call, success or failure
 * [com.hub.media.ui.AppContainer] is closed *before* [commit] runs (see above), and Room database
 * instances/DAOs/repositories are not designed to be re-pointed at a different underlying file
 * after construction -- every ViewModel already alive in the process holds direct references
 * captured from the now-closed container, and their `Flow` collectors would simply stop emitting,
 * not transparently pick up the new file. Rebuilding a fresh [com.hub.media.ui.AppContainer] in
 * place and hoping every already-created ViewModel/Activity/Compose recomposition scope notices is
 * exactly the "half-live container" AGENTS.md §1 warns against. A full process kill-and-relaunch
 * (the app layer's job, not this use case's -- see the Settings route composable) guarantees a
 * completely fresh [com.hub.media.ui.AppContainer], built by the exact same
 * [com.hub.media.ui.createAppContainer] path every ordinary cold start already uses, pointed at
 * whatever [commit] left at the live path -- the restored database on success, or the untouched/
 * rolled-back original on failure. This is deliberately unconditional (not skipped on failure):
 * skipping it would mean continuing to run against an already-closed [com.hub.media.ui.AppContainer]
 * (guaranteed broken, not just possibly stale).
 *
 * @param liveDatabaseFilePath The live database's on-disk path (from
 *   [com.hub.media.core.database.DatabaseFactory.databaseFilePath]).
 */
public class DefaultRestoreDatabaseUseCase(
    private val liveDatabaseFilePath: String,
) : RestoreDatabaseUseCase {

    override suspend fun stage(incomingFilePath: String): Resource<StagedRestoreInfo> {
        val header = readFileHeaderBytes(incomingFilePath, SQLITE_HEADER_SIZE)
        val info = header?.let(::parseSqliteHeader)
        if (info == null) {
            deleteFileIfExists(incomingFilePath)
            return Resource.Error(
                "This doesn't look like a MediaTracker backup file (not a SQLite database). " +
                    "Nothing was changed.",
            )
        }
        if (info.userVersion > APP_DATABASE_VERSION) {
            deleteFileIfExists(incomingFilePath)
            return Resource.Error(
                "This backup was made with a newer version of MediaTracker (database version " +
                    "${info.userVersion}) than this app understands (version $APP_DATABASE_VERSION). " +
                    "Update the app before restoring it. Nothing was changed.",
            )
        }
        return Resource.Success(
            StagedRestoreInfo(
                stagedFilePath = incomingFilePath,
                schemaVersionFound = info.userVersion,
                isOlderSchemaVersion = info.userVersion < APP_DATABASE_VERSION,
            ),
        )
    }

    override suspend fun commit(staged: StagedRestoreInfo): Resource<Unit> {
        val result = swap(staged)
        val marker = when (result) {
            is Resource.Success -> RestoreMarker.Success
            is Resource.Error -> RestoreMarker.Failure(result.message)
        }
        writeRestoreMarker(liveDatabaseFilePath, marker)
        return result
    }

    private suspend fun swap(staged: StagedRestoreInfo): Resource<Unit> {
        val walPath = "$liveDatabaseFilePath-wal"
        val shmPath = "$liveDatabaseFilePath-shm"
        val backupPath = preRestoreBackupPath(liveDatabaseFilePath)
        val backupWalPath = "$backupPath-wal"
        val backupShmPath = "$backupPath-shm"

        return try {
            // Carry the live db's own sidecars (if AppContainer.close() didn't already checkpoint
            // them away) along with the safety-net backup rather than leaving them orphaned next
            // to the newly-restored main file.
            if (fileExists(walPath)) renameFile(walPath, backupWalPath) else deleteFileIfExists(backupWalPath)
            if (fileExists(shmPath)) renameFile(shmPath, backupShmPath) else deleteFileIfExists(backupShmPath)

            val liveExisted = fileExists(liveDatabaseFilePath)
            if (liveExisted && !renameFile(liveDatabaseFilePath, backupPath)) {
                return Resource.Error(
                    "Restore aborted: could not move the current database aside. Nothing was changed.",
                )
            }

            if (!renameFile(staged.stagedFilePath, liveDatabaseFilePath)) {
                // The point of no return would otherwise be crossed silently -- roll back so the
                // user is never left without a database at all.
                val rolledBack = !liveExisted || renameFile(backupPath, liveDatabaseFilePath)
                return Resource.Error(
                    if (rolledBack) {
                        "Restore failed while activating the new database. Your original library was " +
                            "not affected."
                    } else {
                        "Restore failed while activating the new database, and the automatic rollback " +
                            "also failed. Your original database was saved at: $backupPath -- " +
                            "please contact support before doing anything else."
                    },
                )
            }

            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error("Restore failed: ${e.message ?: "Unknown error"}", e)
        }
    }
}
