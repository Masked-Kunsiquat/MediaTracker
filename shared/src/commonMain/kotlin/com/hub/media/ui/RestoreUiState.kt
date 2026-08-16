package com.hub.media.ui

import com.hub.media.features.portability.domain.StagedRestoreInfo

/** UI state for the Settings screen's `.sqlite` restore action (ROADMAP Task 8 Phase C). */
public sealed class RestoreUiState {
    /** No restore has been requested yet, or [RestoreViewModel.reset] was called. */
    public data object Idle : RestoreUiState()

    /** The picked file is being copied/validated; this is non-destructive (see [RestoreViewModel]'s KDoc). */
    public data object Validating : RestoreUiState()

    /**
     * The picked file passed validation and is staged, ready to be swapped in. The app layer is
     * expected to show an explicit, unambiguous destructive-action confirmation before calling
     * anything that touches the live database -- this state is deliberately not itself destructive.
     *
     * @property info What was found in the candidate file (its schema version, and whether that's
     *   older than this app's current version -- a legitimate case Room will migrate forward on
     *   next open).
     */
    public data class AwaitingConfirmation(
        val info: StagedRestoreInfo,
    ) : RestoreUiState()

    /**
     * The picked file was refused before anything destructive happened (not a SQLite file, or a
     * schema version newer than this app understands), or the swap itself failed. Either way, per
     * [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase]'s ordering
     * guarantee, the live database was never left missing.
     *
     * @property message A user-facing/diagnostic description of the failure.
     */
    public data class Error(
        val message: String,
    ) : RestoreUiState()
}
