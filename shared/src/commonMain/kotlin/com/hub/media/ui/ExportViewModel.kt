package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.ExportUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Settings screen's data-export action (ROADMAP Task 8 Phase A), following
 * [AddBookViewModel]'s exact `Idle`/`Loading`/`Success`/`Error` [StateFlow] shape rather than
 * inventing a new convention for "an async operation the UI observes progress/success/failure
 * on." A separate ViewModel from [SettingsViewModel] (rather than folding export state into it)
 * because the two model fundamentally different things: [SettingsViewModel]'s state is a
 * continuously reactive *preference* with no loading/error concept, while export is a one-shot
 * *action* with exactly the request/result shape [AddBookViewModel] already models. Both
 * ViewModels are created side by side by the Settings screen's route composable.
 *
 * @param exportDataUseCase Generates both CSV documents from a single consistent database
 *   snapshot. Typed as the narrow [ExportUseCase] interface (rather than the concrete
 *   [com.hub.media.features.portability.domain.ExportDataUseCase]) so tests can hand-roll a fake
 *   with no Room dependency (AGENTS.md §5 "No Unnecessary Dependencies" -- no mocking library).
 */
public class ExportViewModel(
    private val exportDataUseCase: ExportUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    public val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    /**
     * Generates both CSV documents. If an export is already in flight ([ExportUiState.Loading]),
     * this call is silently ignored -- guards against a double-tap firing two concurrent database
     * reads. Callers must [reset] (or wait for the in-flight export to finish) before a retry is
     * accepted.
     */
    public fun exportData() {
        if (_uiState.value is ExportUiState.Loading) return

        _uiState.value = ExportUiState.Loading
        viewModelScope.launch {
            _uiState.value =
                when (val result = exportDataUseCase.execute()) {
                    is Resource.Success -> ExportUiState.Success(result.data)
                    is Resource.Error -> ExportUiState.Error(result.message)
                }
        }
    }

    /**
     * Resets state back to [ExportUiState.Idle], e.g. after the app layer has finished writing
     * (or failed to write) a [ExportUiState.Success]'s files, or after an [ExportUiState.Error]
     * has been shown to the user.
     */
    public fun reset() {
        _uiState.value = ExportUiState.Idle
    }
}
