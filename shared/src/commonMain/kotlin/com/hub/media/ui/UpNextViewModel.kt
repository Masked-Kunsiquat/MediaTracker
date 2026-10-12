package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.dao.UpNextRow
import com.hub.media.core.util.Resource
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.observeIncludeSpecials
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * A just-marked episode, offered back to the user as an Undo (#89). One-shot, with an [id] so the
 * screen acknowledges exactly the event it showed -- the same shape as [DeleteErrorEvent].
 */
public data class MarkedWatchedEvent(
    val id: Long,
    val row: UpNextRow,
)

/**
 * @property items One row per started show, most recently watched first -- see
 *   [com.hub.media.core.database.dao.EpisodeDao.observeUpNext].
 * @property isLoading `true` only before the first emission, so the empty state is not flashed
 *   at a user whose queue simply has not loaded yet.
 */
public data class UpNextUiState(
    val items: List<UpNextRow> = emptyList(),
    val isLoading: Boolean = true,
    val markedEvent: MarkedWatchedEvent? = null,
    val errorMessage: String? = null,
)

/**
 * Drives the "Up next" screen (#89): the next episode of every started show, in one place.
 *
 * Marking an episode watched needs no list bookkeeping here. The query is reactive, so the show's
 * row re-emits with its following episode -- or leaves the list once the user is up to date.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class UpNextViewModel(
    private val tvShowRepository: TVShowRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val markedEvent = MutableStateFlow<MarkedWatchedEvent?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)
    private var eventSeq = 0L

    public val uiState: StateFlow<UpNextUiState> =
        combine(
            settingsRepository.observeIncludeSpecials().flatMapLatest(tvShowRepository::observeUpNext),
            markedEvent,
            errorMessage,
        ) { items, marked, error ->
            UpNextUiState(items = items, isLoading = false, markedEvent = marked, errorMessage = error)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = UpNextUiState(),
        )

    /** Marks [row]'s episode watched and, on success, offers it back as an Undo. */
    public fun markWatched(row: UpNextRow) {
        viewModelScope.launch {
            when (val result = tvShowRepository.setEpisodeWatched(row.episodeId, watched = true)) {
                is Resource.Success -> markedEvent.value = MarkedWatchedEvent(++eventSeq, row)
                is Resource.Error -> errorMessage.value = result.message
            }
        }
    }

    /**
     * Reverses [event]. The episode was unwatched a moment ago -- it could not have been offered
     * otherwise -- so un-marking it restores exactly the state before.
     */
    public fun undo(event: MarkedWatchedEvent) {
        consumeMarked(event.id)
        viewModelScope.launch {
            val result = tvShowRepository.setEpisodeWatched(event.row.episodeId, watched = false)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /** Acknowledges the snackbar for [id] once it is gone, so it is not shown again. */
    public fun consumeMarked(id: Long) {
        if (markedEvent.value?.id == id) markedEvent.value = null
    }

    /** Acknowledges the error once shown. */
    public fun consumeError() {
        errorMessage.value = null
    }
}
