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
 * @property busyKeys The `(mediaId, seasonNumber)` pairs currently being acted on. A **set** rather
 *   than one slot: rows act independently, so two can legitimately be in flight, and a single slot
 *   meant the first to finish cleared the other's spinner while its write was still running.
 * @property errorMessage The last failure, or `null`. Cleared by the next action.
 */
public data class MismatchReviewUiState(
    public val rows: List<MismatchReviewRow> = emptyList(),
    public val busyKeys: Set<Pair<String, Int>> = emptySet(),
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
        // Driven by the store rather than read once. A one-shot read goes stale in a way the user
        // sees: Settings shows "2 shows disagree", they reconcile one on the review screen, come
        // back, and the sentence still claims two. Both this screen and that row hold their own
        // instance of this ViewModel, and neither is recomposed by the other's writes -- so the
        // signal has to come from the thing they share, which is the stored findings.
        viewModelScope.launch {
            reconcile.observeFindings().collect {
                loadRows()
            }
        }
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
        viewModelScope.launch { loadRows() }
    }

    /**
     * The read itself.
     *
     * Guarded the way [BackfillViewModel]'s `init` read is, and for the reason that one records: an
     * unguarded suspend database read inside a `launch` with no caller sends anything it throws
     * straight into `viewModelScope`, where an uncaught exception takes the scope down — a crash on
     * opening a screen, from a read whose only job is to fill a list. It matters more here than
     * there, because this one runs inside a `collect` that would otherwise die with it and leave the
     * screen permanently unresponsive to further changes.
     */
    private suspend fun loadRows() {
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

    /**
     * Creates the episodes [row] is missing, then reloads.
     *
     * A second tap while one is in flight is ignored: the underlying insert decides what is missing
     * inside its own transaction and so cannot double-create, but letting the button re-fire would
     * still show two spinners for one action.
     */
    public fun addMissingEpisodes(row: MismatchReviewRow) {
        val key = row.mediaId to row.seasonNumber
        if (key in _uiState.value.busyKeys) return

        _uiState.value =
            _uiState.value.copy(busyKeys = _uiState.value.busyKeys + key, errorMessage = null)
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
                // Only this row's key, so a second action still in flight keeps its own spinner. The
                // list reload is not triggered here: a successful action writes to the store, and
                // observeFindings() is what notices -- which also covers a write made anywhere else.
                _uiState.value = _uiState.value.copy(busyKeys = _uiState.value.busyKeys - key)
            }
        }
    }
}
