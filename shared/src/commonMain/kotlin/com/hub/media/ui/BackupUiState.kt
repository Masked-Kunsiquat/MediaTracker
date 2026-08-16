package com.hub.media.ui

import com.hub.media.features.portability.domain.BackupResult

/** UI state for the Settings screen's `.sqlite` backup action (ROADMAP Task 8 Phase C). */
public sealed class BackupUiState {
    /** No backup has been requested yet, or [BackupViewModel.reset] was called. */
    public data object Idle : BackupUiState()

    /** A backup is in flight; further calls to [BackupViewModel.backupData] are ignored. */
    public data object Loading : BackupUiState()

    /**
     * A complete, self-contained snapshot of the live database was staged successfully. The app
     * layer is expected to copy [result]'s staged file into the user-chosen SAF destination, then
     * delete the staged file and call [BackupViewModel.reset] regardless of whether that copy
     * succeeded -- generation succeeding here says nothing about whether the write-to-disk step
     * also succeeds.
     */
    public data class Success(
        val result: BackupResult,
    ) : BackupUiState()

    /**
     * Backup failed before any snapshot was produced.
     *
     * @property message A user-facing/diagnostic description of the failure.
     */
    public data class Error(
        val message: String,
    ) : BackupUiState()
}
