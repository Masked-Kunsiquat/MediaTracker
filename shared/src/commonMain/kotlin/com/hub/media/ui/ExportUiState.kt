package com.hub.media.ui

import com.hub.media.features.portability.domain.ExportArchiveFile

/** UI state for the Settings screen's data-export action (ROADMAP Task 8 Phase A). */
public sealed class ExportUiState {
    /** No export has been requested yet, or [ExportViewModel.reset] was called. */
    public data object Idle : ExportUiState()

    /** An export is in flight; further calls to [ExportViewModel.exportData] are ignored. */
    public data object Loading : ExportUiState()

    /** The archive is built; the screen opens the save dialog for it. */
    public data class Success(
        val file: ExportArchiveFile,
    ) : ExportUiState()

    /**
     * The save dialog is open for [file] (#196). Held in the ViewModel, which survives a rotation
     * inside the dialog, so the result still finds the bytes and a recreated screen -- which opens the
     * dialog on [Success] -- finds nothing to open a second time. The #162 pattern.
     */
    public data class AwaitingDestination(
        val file: ExportArchiveFile,
    ) : ExportUiState()

    /**
     * Export failed before any file was produced (e.g. a database read failure).
     *
     * @property message A user-facing/diagnostic description of the failure.
     */
    public data class Error(
        val message: String,
    ) : ExportUiState()
}
