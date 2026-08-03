package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.DatabaseBackupUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Settings screen's `.sqlite` backup action (ROADMAP Task 8 Phase C), following
 * [ExportViewModel]'s exact `Idle`/`Loading`/`Success`/`Error` [StateFlow] shape -- backup, like
 * export, is a one-shot, non-destructive action with no confirmation needed (unlike restore, which
 * has its own [RestoreViewModel] specifically because it needs a very different, destructive-action
 * shaped state machine -- see that class's KDoc).
 *
 * @param backupDatabaseUseCase Produces a complete, internally consistent database snapshot. Typed
 *   as the narrow [DatabaseBackupUseCase] interface (rather than the concrete
 *   [com.hub.media.features.portability.domain.DefaultDatabaseBackupUseCase]) so tests can
 *   hand-roll a fake with no Room dependency (AGENTS.md §5).
 */
public class BackupViewModel(
    private val backupDatabaseUseCase: DatabaseBackupUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    public val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /**
     * Generates a fresh backup snapshot. If a backup is already in flight
     * ([BackupUiState.Loading]), this call is silently ignored -- guards against a double-tap
     * firing two concurrent `VACUUM INTO` runs.
     */
    public fun backupData() {
        if (_uiState.value is BackupUiState.Loading) return

        _uiState.value = BackupUiState.Loading
        viewModelScope.launch {
            _uiState.value = when (val result = backupDatabaseUseCase.execute()) {
                is Resource.Success -> BackupUiState.Success(result.data)
                is Resource.Error -> BackupUiState.Error(result.message)
            }
        }
    }

    /**
     * Resets state back to [BackupUiState.Idle], e.g. after the app layer has finished writing (or
     * failed to write) a [BackupUiState.Success]'s staged file to its SAF destination.
     */
    public fun reset() {
        _uiState.value = BackupUiState.Idle
    }
}
