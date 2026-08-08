package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.features.books.domain.BulkBackfillUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Settings screen's bulk cover/author backfill action (ROADMAP Task 14 Phase A).
 *
 * ### Why this isn't shaped like [ExportViewModel]/[ImportViewModel]
 * Export/import are one-shot: fire a request, get exactly one terminal result. A backfill is a
 * long-running, resumable, cancellable pass over the whole library that reports progress as it
 * goes, so [uiState] is [BackfillUiState] instead of the `Idle`/`Loading`/`Success`/`Error` shape
 * those two share -- see that sealed class's KDoc.
 *
 * ### Resumability across a fresh Settings-screen visit
 * On construction, this ViewModel checks [BulkBackfillUseCase.peekProgress] for resume state left
 * over from a previous session (a run this app process never finished, whether because the user
 * cancelled it, the quota paused it, or the process died mid-run — [BulkBackfillUseCase]'s
 * persisted [com.hub.media.features.settings.data.BulkBackfillState] survives all three). If found,
 * [uiState] starts at [BackfillUiState.Stopped] instead of [BackfillUiState.Idle], so the Settings
 * screen can offer "Resume backfill (168 remaining)" the moment it's opened rather than only after
 * the user has already tapped Start once this session.
 *
 * @param bulkBackfillUseCase Runs (or resumes) one backfill pass and reports progress via a
 *   callback.
 */
public class BackfillViewModel(
    private val bulkBackfillUseCase: BulkBackfillUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackfillUiState>(BackfillUiState.Idle)
    public val uiState: StateFlow<BackfillUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            bulkBackfillUseCase.peekProgress()?.let { progress ->
                _uiState.value = BackfillUiState.Stopped(progress)
            }
        }
    }

    /**
     * Starts (or resumes) a backfill run. If a run is already in flight ([BackfillUiState.Running]),
     * this call is silently ignored -- guards against a double-tap firing two concurrent passes
     * over the same pending list. [BulkBackfillUseCase.execute] itself decides whether this is a
     * fresh scan or a continuation of previously-persisted resume state; this ViewModel doesn't
     * need to know which.
     */
    public fun start() {
        if (_uiState.value is BackfillUiState.Running) return

        _uiState.value = BackfillUiState.Running(progress = null)
        job = viewModelScope.launch {
            try {
                val finalProgress = bulkBackfillUseCase.execute { progress ->
                    _uiState.value = BackfillUiState.Running(progress)
                }
                _uiState.value = BackfillUiState.Stopped(finalProgress)
            } catch (e: CancellationException) {
                // cancel() below cancels this exact job. The last progress snapshot this run
                // reported is still the correct "where things stand" state -- BulkBackfillUseCase
                // checkpoints resume state after every book, so whatever was last reported here is
                // also what a future start() call will resume from. Without this catch, uiState
                // would be stuck at Running forever (the Stopped assignment above never runs once
                // this coroutine is cancelled), which would leave the UI showing a progress
                // indicator for a run that has, in fact, stopped.
                (_uiState.value as? BackfillUiState.Running)?.progress?.let { lastProgress ->
                    _uiState.value = BackfillUiState.Stopped(lastProgress)
                }
                throw e
            }
        }
    }

    /**
     * Cancels the in-flight run, if any. A no-op if nothing is running. Per [start]'s KDoc, the UI
     * settles on [BackfillUiState.Stopped] with the last progress reported before cancellation,
     * from which a later [start] call resumes.
     */
    public fun cancel() {
        job?.cancel()
    }
}
