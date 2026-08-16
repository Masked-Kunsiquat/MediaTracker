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
import com.hub.media.core.database.validateStagedDatabaseIntegrity
import com.hub.media.core.database.writeRestoreMarker
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.core.util.info
import com.hub.media.core.util.warn

/** [Logger] tag for every log call [DefaultRestoreDatabaseUseCase] makes. */
private const val TAG = "RestoreDatabaseUseCase"

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
 * ### Validation ([stage]) -- two passes, cheap rejection first
 * **Pass 1** reads only the candidate's first [com.hub.media.core.database.SQLITE_HEADER_SIZE]
 * bytes (not the whole file) and parses them via [com.hub.media.core.database.parseSqliteHeader] --
 * no SQLite driver, no Room, no database connection at all:
 * - Not a SQLite file (bad magic bytes) -> refused with a clear message; the candidate file is
 *   deleted (nothing else touched).
 * - `user_version` newer than [com.hub.media.core.database.APP_DATABASE_VERSION] -> refused with a
 *   message telling the user to update the app, rather than letting Room fail obscurely trying to
 *   open a schema it has never seen (this task's explicit brief). Candidate deleted.
 *
 * **Pass 2**, reached only once pass 1 passes, opens the candidate **read-only** with a real
 * [androidx.sqlite.driver.bundled.BundledSQLiteDriver] connection (see
 * [com.hub.media.core.database.validateStagedDatabaseIntegrity]) and runs SQLite's own `PRAGMA
 * integrity_check`, then confirms the tables that schema version defines are actually present. A
 * 100-byte header alone cannot tell a genuinely intact database apart from one truncated mid-write,
 * corrupted in transit, or -- despite starting with the same standard SQLite magic string --
 * belonging to a completely different program; for an operation this destructive (AGENTS.md §1: no
 * cloud copy to fall back on), that bar was too low. Either failure mode is refused with a clear
 * message and the candidate is deleted, exactly like pass 1's rejections. Opening the connection
 * read-only matters too: this pass only ever *validates* the candidate, so it must never itself be
 * able to modify the file or spawn `-wal`/`-shm` sidecars next to it -- that would be a side effect
 * of merely picking a file to look at, before the user has even confirmed anything destructive.
 *
 * `user_version` older than or equal to the current version, having passed both passes -> accepted.
 * An **older** version is legitimate and deliberately allowed through: [com.hub.media.core.database.buildAppDatabase]
 * registers every tested [androidx.room.migration.Migration] the normal app-startup path already
 * uses, so the very next time the swapped-in file is opened by the ordinary Room builder, Room
 * runs that same migration chain automatically -- there is no separate "restore migration" path
 * to build or trust. `DatabaseBackupRestoreRoundTripTest` (`jvmTest`) proves this concretely: it
 * builds a real v2-schema file, "restores" it via [commit], reopens it through
 * [com.hub.media.core.database.buildAppDatabase] exactly like a normal app launch would, and
 * asserts it ends up on the current schema version with its pre-migration data intact. Pass 2's
 * expected-table check is chosen **from the candidate's own reported `user_version`**, not a single
 * fixed set: a candidate older than the version `app_settings` was added at (v4) is only ever held
 * to the tables present since schema v1, so this legitimate older-backup path is never itself
 * rejected as "not a MediaTracker library" -- but a candidate that itself *claims* `user_version`
 * 4 or newer is held to that later table set too, so a v4 (or newer) file genuinely missing
 * `app_settings` -- corrupt, hand-crafted, or otherwise not what it claims to be -- is correctly
 * refused rather than silently waved through.
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
 * death mid-sequence is a single native syscall's duration, not this whole function's.
 *
 * **The main `.db` file moves first; its `-wal`/`-shm` sidecars move only afterward** -- the mirror
 * image of [com.hub.media.core.database.selfHealDatabaseIfNeeded]'s "sidecars first, main file
 * last" ordering, and for the same reason: whichever rename happens *last* is the one whose success
 * this function's own error messages get to rely on.
 *
 * 1. **Rename the live `.db` file to [com.hub.media.core.database.preRestoreBackupPath]** (a fixed,
 *    single-generation-deep name -- each restore attempt replaces the previous attempt's safety net
 *    rather than accumulating one backup per restore forever). *Death here*: either this rename
 *    completed or it didn't (a single atomic filesystem call) -- if it didn't, the live file and its
 *    sidecars are exactly as they were, so the "nothing was changed" error message below is true.
 * 2. **Move the live database's own `-wal`/`-shm` sidecars aside** (if `AppContainer.close()`
 *    hadn't already made SQLite checkpoint-and-delete them, which it typically does as the last
 *    connection to a WAL-mode database closes) to `<backup>-wal`/`<backup>-shm`, so they travel
 *    with the safety-net backup rather than being orphaned next to the newly-restored main file.
 *    Only reached once step 1 has either succeeded or determined there was no live file to move,
 *    which is what makes the rollback below able to put the *whole* pre-restore state (main file
 *    **and** the WAL frames holding its most recent commits) back together, not just the main file.
 *    Step 3 is only ever attempted if *both* sidecar renames here succeeded (or weren't needed) --
 *    a sidecar rename can fail on a live process too, not only via a crash (e.g. another handle
 *    transiently blocking it), and letting step 3 proceed anyway would swap in the new database
 *    while silently leaving the old one's most recent commits stranded outside the safety-net
 *    backup. If either sidecar rename fails, [commit] rolls back **whichever of the two sidecar
 *    renames actually succeeded** (a rename can fail for the `-wal` and land for the `-shm`, or vice
 *    versa -- rolling back only "step 1" and ignoring a sidecar that did move would strand it at the
 *    backup path while reporting "nothing was changed") and then step 1 itself, same
 *    sidecars-first-then-main-file ordering step 3's own failure uses below, and returns an error
 *    naming the WAL/SHM move specifically -- nothing beyond that point is ever attempted.
 * 3. **Rename the staged, already-validated candidate into the live path.** *Death here* (the one
 *    genuinely dangerous window: the live path is briefly missing entirely between steps 1 and 3) --
 *    would otherwise make Room silently create a fresh, empty database on the next launch. This is
 *    exactly what [com.hub.media.core.database.selfHealDatabaseIfNeeded] exists to close: called
 *    once at the very start of every [com.hub.media.ui.createAppContainer], before Room ever opens
 *    anything, it notices "live file missing, backup file present" and moves the backup's sidecars
 *    back first, then its main file -- so it can never leave the live `.db` present without the WAL
 *    that belongs next to it.
 * 4. **Write the restore-result marker** ([com.hub.media.core.database.writeRestoreMarker]) --
 *    `SUCCESS` or a `FAILURE:<reason>` -- as the unconditional last step, regardless of whether
 *    steps 1-3 succeeded. *Death here*: the swap itself already fully happened (or was already
 *    rolled back on failure -- see below); only the *user-visible feedback* about it is lost, not
 *    any data. The next launch simply shows no restore-outcome message.
 *
 * If step 1's rename fails outright, [commit] returns [Resource.Error] immediately -- nothing else
 * is attempted (step 2 is never reached), so the live file *and* its sidecars are still exactly what
 * they were, and the "Nothing was changed" message is literally true. If step 3's rename fails
 * *after* steps 1-2 already succeeded, [commit] immediately attempts to rename the safety-net
 * backup's sidecars back into place, then its main file, before returning [Resource.Error] --
 * restoring the *complete* pre-restore state (not just the main file) rather than leaving the user
 * with a database missing whatever commits were sitting only in its WAL. Only if that rollback
 * attempt *also* fails (disk full, permission revoked mid-operation -- not expected in practice,
 * since the identical rename direction just succeeded moments earlier) does [commit] surface an
 * error naming [com.hub.media.core.database.preRestoreBackupPath] explicitly, so the file (and its
 * `-wal`/`-shm` siblings, if present alongside it) can be recovered by hand if the automatic path is
 * ever exhausted.
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
 * ### Logging (ROADMAP Task 15)
 * Every rejection/failure branch below now also logs (via [logger], defaulting to [AppLogger]) --
 * this is the exact gap the ROADMAP called out: `Resource.Error` already carried an optional `cause`
 * [Throwable] (see [Resource.Error.cause]), but nothing ever read it once it left this class --
 * [com.hub.media.ui.RestoreViewModel] discards it outright (`RestoreUiState.Error(result.message)`),
 * and [commit] is called directly from the app layer with no ViewModel step to inspect it either.
 * The `cause` field itself is unchanged (still there for any future caller that wants it); logging
 * makes the failure diagnosable today, from the device's own logs, regardless of whether any caller
 * ever reads `cause`. Every logged message is either already shown to the user verbatim (the
 * `Resource.Error.message` text) or a fixed diagnostic string plus a file path also already
 * user-visible in that same message -- never book/library content (see [Logger]'s identifier rule).
 *
 * @param liveDatabaseFilePath The live database's on-disk path (from
 *   [com.hub.media.core.database.DatabaseFactory.databaseFilePath]).
 * @param logger Where every rejection/failure below is recorded. Defaults to [AppLogger].
 */
public class DefaultRestoreDatabaseUseCase(
    private val liveDatabaseFilePath: String,
    private val logger: Logger = AppLogger,
) : RestoreDatabaseUseCase {
    override suspend fun stage(incomingFilePath: String): Resource<StagedRestoreInfo> {
        val header = readFileHeaderBytes(incomingFilePath, SQLITE_HEADER_SIZE)
        val info = header?.let(::parseSqliteHeader)
        if (info == null) {
            deleteFileIfExists(incomingFilePath)
            logger.warn(TAG) { "restore candidate rejected: not a SQLite database" }
            return Resource.Error(
                "This doesn't look like a MediaTracker backup file (not a SQLite database). " +
                    "Nothing was changed.",
            )
        }
        if (info.userVersion > APP_DATABASE_VERSION) {
            deleteFileIfExists(incomingFilePath)
            logger.warn(TAG) {
                "restore candidate rejected: schema version ${info.userVersion} is newer than " +
                    "supported version $APP_DATABASE_VERSION"
            }
            return Resource.Error(
                "This backup was made with a newer version of MediaTracker (database version " +
                    "${info.userVersion}) than this app understands (version $APP_DATABASE_VERSION). " +
                    "Update the app before restoring it. Nothing was changed.",
            )
        }
        // validateStagedDatabaseIntegrity already logs the failure reason itself (its own tag,
        // "StagedDatabaseValidation") -- not duplicated here.
        val integrityFailure = validateStagedDatabaseIntegrity(incomingFilePath, info.userVersion, logger)
        if (integrityFailure != null) {
            deleteFileIfExists(incomingFilePath)
            return Resource.Error(
                "This file isn't a valid, intact MediaTracker backup ($integrityFailure). " +
                    "Nothing was changed.",
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
        val marker =
            when (result) {
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
            // Main file first: while nothing but this rename has happened, the "Nothing was
            // changed" message below is unconditionally true. Sidecars deliberately are NOT moved
            // yet -- see the class KDoc's "main file first" section for why moving them before this
            // point (the previous, buggy ordering) made that same message a lie.
            val liveExisted = fileExists(liveDatabaseFilePath)
            if (liveExisted && !renameFile(liveDatabaseFilePath, backupPath)) {
                logger.error(TAG) { "restore aborted: could not move the current database aside" }
                return Resource.Error(
                    "Restore aborted: could not move the current database aside. Nothing was changed.",
                )
            }

            // Only now -- main file already safely at backupPath (or never existed) -- carry its
            // own -wal/-shm sidecars (if AppContainer.close() didn't already checkpoint them away)
            // along with it, so a rollback below can always put the whole pre-restore state back
            // together rather than just the main file.
            val walMoved =
                if (fileExists(walPath)) {
                    renameFile(walPath, backupWalPath)
                } else {
                    deleteFileIfExists(backupWalPath)
                    true
                }
            val shmMoved =
                if (fileExists(shmPath)) {
                    renameFile(shmPath, backupShmPath)
                } else {
                    deleteFileIfExists(backupShmPath)
                    true
                }
            if (!walMoved || !shmMoved) {
                // A sidecar rename can fail on a live, non-crashed process too (e.g. another handle
                // transiently blocking the rename) -- if the staged file were swapped in anyway, the
                // live database would be missing whatever WAL frames failed to travel with it, and
                // the safety-net backup would be silently incomplete. Undo step 1 (if it happened)
                // before reporting, so this failure mode gets the same honest "nothing was changed"
                // guarantee as an outright failure in step 1 itself.
                //
                // Sidecars first, main file last -- the same ordering the activation-failure
                // rollback below uses (and selfHealDatabaseIfNeeded's own ordering). Critically,
                // walMoved/shmMoved can disagree (one sidecar can succeed while the other fails):
                // only reverse the ones that actually moved, so a sidecar whose rename never ran
                // is never touched, and one that DID land at backupWalPath/backupShmPath is never
                // left stranded there with the main file already back at its live path -- which is
                // exactly what "Nothing was changed" must mean to be true.
                val walRolledBack = !walMoved || renameFile(backupWalPath, walPath)
                val shmRolledBack = !shmMoved || renameFile(backupShmPath, shmPath)
                val mainRolledBack = !liveExisted || renameFile(backupPath, liveDatabaseFilePath)
                val rolledBack = walRolledBack && shmRolledBack && mainRolledBack
                logger.error(TAG) {
                    "restore aborted while moving WAL/SHM sidecars aside; automatic rollback " +
                        if (rolledBack) "succeeded" else "FAILED -- pre-restore backup left at $backupPath"
                }
                return Resource.Error(
                    if (rolledBack) {
                        "Restore aborted: could not move the current database's WAL/SHM files aside. " +
                            "Nothing was changed."
                    } else {
                        "Restore aborted while moving the current database's WAL/SHM files aside, and " +
                            "the automatic rollback also failed. Your original database was saved at: " +
                            "$backupPath -- please contact support before doing anything else."
                    },
                )
            }

            if (!renameFile(staged.stagedFilePath, liveDatabaseFilePath)) {
                // The point of no return would otherwise be crossed silently -- roll back so the
                // user is never left without a database at all. Sidecars first, main file last --
                // mirroring selfHealDatabaseIfNeeded's own ordering -- so that if this rollback
                // itself is interrupted, the next launch's self-heal can still finish the job
                // correctly rather than seeing a live file already present and stopping short of
                // restoring its WAL.
                val sidecarsRolledBack =
                    (!fileExists(backupWalPath) || renameFile(backupWalPath, walPath)) &&
                        (!fileExists(backupShmPath) || renameFile(backupShmPath, shmPath))
                val mainRolledBack = !liveExisted || renameFile(backupPath, liveDatabaseFilePath)
                val rolledBack = sidecarsRolledBack && mainRolledBack
                logger.error(TAG) {
                    "restore aborted while activating the staged candidate; automatic rollback " +
                        if (rolledBack) "succeeded" else "FAILED -- pre-restore backup left at $backupPath"
                }
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

            // The most consequential thing this app can do -- it replaces the whole database -- and
            // until now only its *failures* were recorded, so a successful restore left no trace
            // that the library had been swapped out at all. ROADMAP Task 15 Phase C listed restore
            // completion in scope; adding import and export and not this was an oversight.
            logger.info(TAG) { "Restore completed: database replaced from a staged backup" }
            Resource.Success(Unit)
        } catch (e: Exception) {
            logger.error(TAG, e) { "restore failed with an unexpected exception during the swap" }
            Resource.Error("Restore failed: ${e.message ?: "Unknown error"}", e)
        }
    }
}
