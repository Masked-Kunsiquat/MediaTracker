package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.features.portability.domain.ImportUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Settings screen's data-import action (ROADMAP Task 8 Phase B), following
 * [ExportViewModel]'s exact `Idle`/`Loading`/`Success`/`Error` [StateFlow] shape.
 *
 * @param importDataUseCase Runs the whole import pipeline from already-read file contents (the
 *   app layer owns reading the user-picked file(s) via SAF -- this ViewModel never touches
 *   `Uri`/`ContentResolver`, matching [ExportViewModel]'s split). Typed as the narrow
 *   [ImportUseCase] interface so tests can hand-roll a fake with no Room dependency (AGENTS.md §5).
 */
public class ImportViewModel(
    private val importDataUseCase: ImportUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    public val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    /**
     * Runs an import against already-read CSV text. If an import is already in flight
     * ([ImportUiState.Loading]), this call is silently ignored -- guards against a double-tap
     * firing two concurrent imports. Callers must [reset] (or wait for the in-flight import to
     * finish) before a retry is accepted.
     *
     * @param libraryCsv `library_export.csv` text, or `null` if the user didn't provide one.
     * @param readingLogsCsv `reading_logs_export.csv` text, or `null` if the user didn't provide
     *   one.
     * @param duplicatePolicy The user's chosen [DuplicatePolicy] for this import.
     */
    public fun importData(libraryCsv: String?, readingLogsCsv: String?, duplicatePolicy: DuplicatePolicy) {
        if (_uiState.value is ImportUiState.Loading) return

        _uiState.value = ImportUiState.Loading
        viewModelScope.launch {
            _uiState.value = when (val result = importDataUseCase.execute(libraryCsv, readingLogsCsv, duplicatePolicy)) {
                is Resource.Success -> ImportUiState.Success(result.data)
                is Resource.Error -> ImportUiState.Error(result.message)
            }
        }
    }

    /**
     * Runs a Goodreads import (ROADMAP Task 8 Phase D) against already-read
     * `goodreads_library_export.csv` text -- the app layer owns reading the user-picked file via
     * SAF, same split as [importData]. Shares [uiState]/the double-tap guard with [importData]
     * rather than a separate state machine: it is the same underlying pipeline
     * ([com.hub.media.features.portability.domain.ImportDataUseCase.executeGoodreads] reuses
     * [com.hub.media.features.portability.domain.ImportDataUseCase.execute]'s duplicate-matching
     * and write path almost entirely, see that method's KDoc), just fed from a different file
     * format -- so a Goodreads import in flight blocks a concurrent CSV import and vice versa,
     * which is the correct behavior for two paths that both write to the same library.
     *
     * @param goodreadsCsv `goodreads_library_export.csv` text.
     * @param duplicatePolicy The user's chosen [DuplicatePolicy] for this import.
     */
    public fun importGoodreads(goodreadsCsv: String, duplicatePolicy: DuplicatePolicy) {
        if (_uiState.value is ImportUiState.Loading) return

        _uiState.value = ImportUiState.Loading
        viewModelScope.launch {
            _uiState.value = when (val result = importDataUseCase.executeGoodreads(goodreadsCsv, duplicatePolicy)) {
                is Resource.Success -> ImportUiState.Success(result.data)
                is Resource.Error -> ImportUiState.Error(result.message)
            }
        }
    }

    /** Resets state back to [ImportUiState.Idle], e.g. after a result has been shown to the user. */
    public fun reset() {
        _uiState.value = ImportUiState.Idle
    }
}
