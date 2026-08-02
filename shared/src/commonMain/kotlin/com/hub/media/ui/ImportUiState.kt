package com.hub.media.ui

import com.hub.media.features.portability.domain.ImportSummary

/** UI state for the Settings screen's data-import action (ROADMAP Task 8 Phase B). */
public sealed class ImportUiState {

    /** No import has been requested yet, or [ImportViewModel.reset] was called. */
    public data object Idle : ImportUiState()

    /** An import is in flight; further calls to [ImportViewModel.importData] are ignored. */
    public data object Loading : ImportUiState()

    /**
     * The import ran to completion (which may still include per-row rejections -- see
     * [ImportSummary.rejections]). The app layer is expected to show [summary] in full, not just a
     * generic "done" message.
     */
    public data class Success(val summary: ImportSummary) : ImportUiState()

    /**
     * The import was refused outright before any write was attempted (a structural file problem
     * such as a bad header, an unterminated quote, or an unsupported schema version), or the
     * all-or-nothing write transaction itself failed. Either way, nothing was written.
     *
     * @property message A user-facing/diagnostic description of the failure.
     */
    public data class Error(val message: String) : ImportUiState()
}
