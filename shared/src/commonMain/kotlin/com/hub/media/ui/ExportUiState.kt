package com.hub.media.ui

import com.hub.media.features.portability.domain.CsvExportBundle

/** UI state for the Settings screen's data-export action (ROADMAP Task 8 Phase A). */
public sealed class ExportUiState {
    /** No export has been requested yet, or [ExportViewModel.reset] was called. */
    public data object Idle : ExportUiState()

    /** An export is in flight; further calls to [ExportViewModel.exportData] are ignored. */
    public data object Loading : ExportUiState()

    /**
     * The CSV documents were generated successfully. The app layer is expected to write
     * [bundle]'s two documents to files (SAF `ACTION_CREATE_DOCUMENT`) and then call
     * [ExportViewModel.reset] once that follow-up write completes (success or failure) --
     * generation succeeding here says nothing about whether the write-to-disk step also
     * succeeds.
     *
     * @property bundle The generated `library_export.csv` and `reading_logs_export.csv` text.
     */
    public data class Success(
        val bundle: CsvExportBundle,
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
