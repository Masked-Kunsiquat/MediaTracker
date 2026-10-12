package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.dao.UpNextRow
import com.hub.media.core.util.Resource
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.getIncludeSpecials
import com.hub.media.features.settings.data.observeIncludeSpecials
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.domain.MarkEpisodesWatchedUseCase
import com.hub.media.features.tv.domain.WatchedWhen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * @property items One row per started show, most recently watched first -- see
 *   [com.hub.media.core.database.dao.EpisodeDao.observeUpNext].
 * @property isLoading `true` only before the first emission, so the empty state is not flashed
 *   at a user whose queue simply has not loaded yet.
 * @property pendingTick The open "Mark … watched?" prompt, if any (#131).
 */
public data class UpNextUiState(
    val items: List<UpNextRow> = emptyList(),
    val isLoading: Boolean = true,
    val pendingTick: PendingTick? = null,
    val errorMessage: String? = null,
)

/**
 * Drives the "Up next" screen (#89): the next episode of every started show, in one place.
 *
 * Its check goes through the same dated prompt as the show screen (#131): [requestTick] opens it,
 * [confirmTick] writes through [MarkEpisodesWatchedUseCase]. The queue is reactive, so after the
 * write the show's row re-emits with its following episode, or leaves once the user is caught up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class UpNextViewModel(
    private val tvShowRepository: TVShowRepository,
    private val settingsRepository: SettingsRepository,
    private val markEpisodesWatched: MarkEpisodesWatchedUseCase,
) : ViewModel() {
    private val pendingTick = MutableStateFlow<PendingTick?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)

    public val uiState: StateFlow<UpNextUiState> =
        combine(
            settingsRepository.observeIncludeSpecials().flatMapLatest(tvShowRepository::observeUpNext),
            pendingTick,
            errorMessage,
        ) { items, tick, error ->
            UpNextUiState(items = items, isLoading = false, pendingTick = tick, errorMessage = error)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = UpNextUiState(),
        )

    /** Opens the prompt for [row]'s episode, with the show's earlier unwatched episodes as catch-up. */
    public fun requestTick(row: UpNextRow) {
        viewModelScope.launch {
            val episodes = tvShowRepository.observeEpisodes(row.mediaId).first()
            val target = episodes.firstOrNull { it.id == row.episodeId } ?: return@launch
            pendingTick.value =
                buildPendingTick(target, episodes, settingsRepository.getIncludeSpecials(), markEpisodesWatched.now())
        }
    }

    /** Writes the open prompt; cleared before launching so a second confirm writes nothing. */
    public fun confirmTick(
        watchedWhen: WatchedWhen,
        includeEarlier: Boolean,
    ) {
        val tick = pendingTick.value ?: return
        pendingTick.value = null
        viewModelScope.launch {
            val ids = tick.targetIds + if (includeEarlier) tick.earlierIds else emptyList()
            val result = markEpisodesWatched.execute(ids, watchedWhen)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /** Closes the prompt without writing. */
    public fun dismissTick() {
        pendingTick.value = null
    }

    /** Acknowledges the error once shown. */
    public fun consumeError() {
        errorMessage.value = null
    }
}
