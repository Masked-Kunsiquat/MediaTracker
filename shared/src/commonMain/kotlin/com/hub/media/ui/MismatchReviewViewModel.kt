package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.features.media.domain.MismatchReviewRow
import com.hub.media.features.media.domain.ReconcileMismatchesUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MismatchReviewViewModel"

/**
 * What the reconciliation screen is showing (#123).
 *
 * @property rows Every disagreement still worth reporting, in stored order.
 * @property busyKey The `(mediaId, seasonNumber)` currently being acted on, or `null`. Held per row
 *   rather than as one screen-wide flag so tapping one season disables that row's button and leaves
 *   the others usable — the alternative locks the whole list on a write that touches one season.
 * @property errorMessage The last failure, or `null`. Cleared by the next action.
 */
public data class MismatchReviewUiState(
    public val rows: List<MismatchReviewRow> = emptyList(),
    public val busyKey: Pair<String, Int>? = null,
    public val errorMessage: String? = null,
    public val isLoading: Boolean = true,
)

/**
 * Drives the reconciliation screen (#123).
 *
 * ### Why this is not [BackfillViewModel]
 * That one is generic over a long-running, resumable *pass* and is instantiated twice already. This
 * drives a short list of one-shot actions, has no progress, nothing to resume and nothing to cancel.
 * Folding it in would mean a third payload type on a class whose whole point is that the two passes
 * share one lifecycle — see [com.hub.media.features.media.domain.BackfillRun] on when sharing is
 * worth it, and this is the other side of that line.
 *
 * ### The list is re-read after every action, not patched in place
 * Acting on a season changes what the stored findings are, and re-reading is the only way the screen
 * and the store cannot drift. It also picks up the orphan-dropping
 * [ReconcileMismatchesUseCase.review] does, which a local removal would not.
 */
public class MismatchReviewViewModel(
    private val reconcile: ReconcileMismatchesUseCase,
    private val logger: Logger = AppLogger,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MismatchReviewUiState())
    public val uiState: StateFlow<MismatchReviewUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Reloads the findings.
     *
     * Guarded the way [BackfillViewModel]'s `init` read is, and for the reason that one records: an
     * unguarded suspend database read in a `launch` with no caller sends anything it throws straight
     * into `viewModelScope`, where an uncaught exception takes the scope down — a crash on opening a
     * screen, from a read whose only job is to fill a list.
     */
    public fun refresh() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(rows = reconcile.review(), isLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(TAG, e) { "Failed to read recorded episode-count disagreements" }
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Could not read the recorded differences.",
                    )
            }
        }
    }

    /**
     * Creates the episodes [row] is missing, then reloads.
     *
     * A second tap while one is in flight is ignored: the underlying insert decides what is missing
     * inside its own transaction and so cannot double-create, but letting the button re-fire would
     * still show two spinners for one action.
     */
    public fun addMissingEpisodes(row: MismatchReviewRow) {
        val key = row.mediaId to row.seasonNumber
        if (_uiState.value.busyKey == key) return

        _uiState.value = _uiState.value.copy(busyKey = key, errorMessage = null)
        viewModelScope.launch {
            try {
                when (val result = reconcile.addMissingEpisodes(row)) {
                    is Resource.Error ->
                        _uiState.value = _uiState.value.copy(errorMessage = result.message)
                    is Resource.Success -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(TAG, e) { "Failed to add missing episodes" }
                _uiState.value = _uiState.value.copy(errorMessage = "Could not add the missing episodes.")
            } finally {
                // Cleared before the reload so the row is never left disabled by a failure, and the
                // reload happens either way -- a failed action may still have changed the store.
                _uiState.value = _uiState.value.copy(busyKey = null)
            }
            refresh()
        }
    }
}
