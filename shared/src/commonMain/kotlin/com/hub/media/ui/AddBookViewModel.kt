package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.features.books.domain.BookIngestionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the "add book by ISBN" screen.
 *
 * @param addBookByIsbnUseCase Runs the ISBN ingestion workflow. Typed as the narrow
 *   [BookIngestionUseCase] interface (rather than the concrete
 *   [com.hub.media.features.books.domain.AddBookByIsbnUseCase]) so tests can hand-roll a fake
 *   with no Ktor/Room/disk dependencies (AGENTS.md §5 "No Unnecessary Dependencies" — no mocking
 *   library).
 */
public class AddBookViewModel(
    private val addBookByIsbnUseCase: BookIngestionUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AddBookUiState>(AddBookUiState.Idle)
    public val uiState: StateFlow<AddBookUiState> = _uiState.asStateFlow()

    /**
     * Runs ingestion for [isbn]. If a submission is already in flight
     * ([AddBookUiState.Loading]), this call is silently ignored — guards against a double-tap
     * firing two concurrent inserts. Callers must [reset] (or wait for the in-flight submission
     * to finish) before a retry is accepted.
     */
    public fun addBook(isbn: String) {
        if (_uiState.value is AddBookUiState.Loading) return

        _uiState.value = AddBookUiState.Loading
        viewModelScope.launch {
            _uiState.value = when (val result = addBookByIsbnUseCase.execute(isbn)) {
                is Resource.Success -> AddBookUiState.Success(result.data)
                is Resource.Error -> AddBookUiState.Error(result.message)
            }
        }
    }

    /** Resets state back to [AddBookUiState.Idle], e.g. after the UI has consumed a terminal state. */
    public fun reset() {
        _uiState.value = AddBookUiState.Idle
    }
}
