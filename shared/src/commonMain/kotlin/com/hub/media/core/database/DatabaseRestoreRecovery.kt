package com.hub.media.core.database

/**
 * Outcome of the most recent restore attempt (ROADMAP Task 8 Phase C), persisted as a small
 * plain-text marker file next to the live database by
 * [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase.commit] and consumed
 * exactly once via [consumeRestoreMarker] -- see that function's KDoc for why this exists at all:
 * the app process is killed and restarted immediately after a restore attempt (see
 * `DefaultRestoreDatabaseUseCase`'s class KDoc), so there is no live UI left to show a result
 * synchronously; this marker is how the *next* process launch learns what happened and tells the
 * user.
 */
public sealed class RestoreMarker {
    /** The swap completed successfully; the live database now holds the restored content. */
    public data object Success : RestoreMarker()

    /**
     * The swap failed. Per [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase]'s
     * ordering guarantee, the live database was never left missing: either it was never touched, or
     * a failed final step was rolled back -- so this always means "restore didn't happen," never
     * "data was lost."
     *
     * @property message A user-facing/diagnostic description of the failure.
     */
    public data class Failure(
        public val message: String,
    ) : RestoreMarker()
}

private const val RESTORE_MARKER_SUFFIX = ".restore-result"
private const val RESTORE_BACKUP_SUFFIX = ".pre-restore-bak"
private const val FAILURE_MARKER_PREFIX = "FAILURE:"
private const val SUCCESS_MARKER = "SUCCESS"

/** Path of the restore-result marker file for the live database at [liveDatabaseFilePath]. */
internal fun restoreMarkerPath(liveDatabaseFilePath: String): String = "$liveDatabaseFilePath$RESTORE_MARKER_SUFFIX"

/**
 * Path of the one-generation-deep safety-net backup [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase.commit]
 * renames the pre-restore live database to before swapping the validated replacement into place.
 * Deliberately a fixed name (not timestamped): each restore attempt only ever needs the *most
 * recent* pre-restore state as a safety net, so a new attempt's backup simply replaces the
 * previous one rather than accumulating one backup file per restore forever.
 */
internal fun preRestoreBackupPath(liveDatabaseFilePath: String): String = "$liveDatabaseFilePath$RESTORE_BACKUP_SUFFIX"

/**
 * Writes [marker] to the restore-result marker file for [liveDatabaseFilePath], overwriting any
 * previous marker. Called exactly once, as the last step of
 * [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase.commit] regardless of
 * whether the swap succeeded or failed.
 */
internal suspend fun writeRestoreMarker(
    liveDatabaseFilePath: String,
    marker: RestoreMarker,
) {
    val content =
        when (marker) {
            RestoreMarker.Success -> SUCCESS_MARKER
            is RestoreMarker.Failure -> "$FAILURE_MARKER_PREFIX${marker.message}"
        }
    writeFileBytes(restoreMarkerPath(liveDatabaseFilePath), content.encodeToByteArray())
}

/**
 * Reads and deletes the restore-result marker for [liveDatabaseFilePath], if one exists. Intended
 * to be called exactly once per process launch (from
 * [com.hub.media.ui.createAppContainer]/`AppContainer` construction), so the outcome of a restore
 * that happened just before the process was killed and relaunched (see
 * `DefaultRestoreDatabaseUseCase`'s class KDoc for why a full restart follows every restore
 * attempt) is surfaced to the user exactly once, not on every subsequent launch.
 *
 * @return The parsed [RestoreMarker], or `null` if no restore was attempted since the marker was
 *   last consumed (the overwhelmingly common case on every ordinary launch).
 */
public suspend fun consumeRestoreMarker(liveDatabaseFilePath: String): RestoreMarker? {
    val path = restoreMarkerPath(liveDatabaseFilePath)
    val bytes = readFileBytes(path) ?: return null
    deleteFileIfExists(path)
    val text = bytes.decodeToString()
    return when {
        text == SUCCESS_MARKER -> RestoreMarker.Success
        text.startsWith(FAILURE_MARKER_PREFIX) -> RestoreMarker.Failure(text.removePrefix(FAILURE_MARKER_PREFIX))
        else -> null
    }
}

/**
 * Closes the one narrow window [DefaultRestoreDatabaseUseCase.commit]'s swap sequence cannot make
 * atomic: between renaming the live database aside (to [preRestoreBackupPath]) and renaming the
 * validated replacement into its place, there is a brief instant where no file exists at
 * [liveDatabaseFilePath] at all. A process death in that exact instant (the only realistic trigger:
 * a same-directory file rename that already completed one step) would otherwise make Room create a
 * brand-new, empty database on the next launch -- silently presenting as "your whole library is
 * gone" even though nothing was actually lost (the pre-restore data sits safely at
 * [preRestoreBackupPath]).
 *
 * Called once at the very start of [com.hub.media.ui.createAppContainer], *before* Room ever opens
 * [liveDatabaseFilePath]: if the live file is missing but a pre-restore backup exists, the backup
 * (and its `-wal`/`-shm` siblings, if any survived alongside it) is moved back into place.
 * On every ordinary launch (the live file already exists) this is a single fast existence check
 * and a no-op.
 *
 * ### Ordering: sidecars first, main file last
 * [fileExists] on [liveDatabaseFilePath] doubles as this function's own "already healed" sentinel
 * (the early-return above). That sentinel is only trustworthy if it can't go true before the *set*
 * of files it stands for is complete -- so the `-wal`/`-shm` siblings are moved back **before** the
 * main database file, which is renamed **last**. If a process death interrupts this function
 * between the two steps, the live file is still missing, so the next launch's self-heal call sees
 * the same "not yet healed" state and simply finishes the job (moving whichever backup sidecars are
 * still present, then the main file) -- idempotent regardless of exactly where it was interrupted.
 * The previous ordering (main file first, sidecars last) had the opposite failure mode: a death
 * right after the main-file rename left the live file present -- satisfying the sentinel -- while
 * its `-wal` stayed stranded at [backupPath]`-wal`. Room would then open the live file *without*
 * the WAL holding its most recent commits, silently losing them, with an orphaned `-wal` left
 * behind at the backup path.
 *
 * ### The main-file rename only runs if both sidecar renames actually succeeded
 * A process death is not the only way step order can matter: a sidecar rename can also simply
 * *fail* (return `false`) while the process keeps running -- e.g. another handle on the file
 * momentarily blocking a rename, which real filesystems do surface as ordinary rename failures, not
 * just as theoretical crash windows. If the main-file rename ran unconditionally afterward anyway,
 * a failed sidecar move would silently reproduce the exact same danger as the old ordering: the live
 * file present (sentinel satisfied) with its WAL still stranded at the backup path, forever
 * unreachable once this function's own early-return stops looking for it. So the main-file rename is
 * gated on both sidecar renames having succeeded (or not having been needed in the first place); if
 * either failed, this call leaves the live file still missing -- the exact "not yet healed" state the
 * early-return above knows how to retry -- rather than ever presenting a half-healed database as
 * whole.
 */
public suspend fun selfHealDatabaseIfNeeded(liveDatabaseFilePath: String) {
    val backupPath = preRestoreBackupPath(liveDatabaseFilePath)
    if (fileExists(liveDatabaseFilePath) || !fileExists(backupPath)) return

    val backupWal = "$backupPath-wal"
    val backupShm = "$backupPath-shm"
    val walRestored = !fileExists(backupWal) || renameFile(backupWal, "$liveDatabaseFilePath-wal")
    val shmRestored = !fileExists(backupShm) || renameFile(backupShm, "$liveDatabaseFilePath-shm")
    if (walRestored && shmRestored) {
        renameFile(backupPath, liveDatabaseFilePath)
    }
}
