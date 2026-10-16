package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.error
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.observeIncludeSpecials
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.seconds

/**
 * The hub's three live numbers (#126), one per domain card. A hub that is only a menu is a tap tax;
 * a number on each card makes it somewhere you open the app to look at.
 *
 * @property isLoading `true` until the first reading arrives. The cards show no number meanwhile,
 *   rather than a "0" that is not yet true.
 * @property loadFailed A read failed. The cards show no number rather than a wrong one.
 * @property readingBooks Books whose status is Reading.
 * @property watchlistFilms Films whose status is Watchlist.
 * @property showsUpNext Shows with an episode up next -- the length of the Up next queue.
 */
public data class HubUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val readingBooks: Int = 0,
    val watchlistFilms: Int = 0,
    val showsUpNext: Int = 0,
)

/**
 * Derives [HubUiState] from flows other screens already read, rather than new queries: the library's
 * [MediaRepository.observeAllMediaWithDetails] for the book and film statuses, and
 * [TVShowRepository.observeUpNext] -- under the same "include specials" setting the Up next screen
 * uses -- for the Shows card, so that card and that screen can never disagree about how many shows
 * have something to watch. `observeUpNext` returns one row per show, so its size is the count.
 *
 * A failed read becomes [HubUiState.loadFailed] instead of escaping `stateIn` and crashing the app.
 * Flow's `catch` never swallows cancellation, so this needs no separate rethrow.
 *
 * @param logger Where a failed read is recorded. Defaults to [AppLogger].
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class HubViewModel(
    mediaRepository: MediaRepository,
    tvShowRepository: TVShowRepository,
    settingsRepository: SettingsRepository,
    private val logger: Logger = AppLogger,
) : ViewModel() {
    public val uiState: StateFlow<HubUiState> =
        combine(
            mediaRepository.observeAllMediaWithDetails(),
            settingsRepository.observeIncludeSpecials().flatMapLatest(tvShowRepository::observeUpNext),
        ) { media, upNext ->
            HubUiState(
                isLoading = false,
                readingBooks =
                    media.count { it is MediaWithDetails.Book && it.details?.status == ReadingStatus.READING },
                watchlistFilms =
                    media.count { it is MediaWithDetails.Movie && it.details?.status == WatchStatus.WATCHLIST },
                showsUpNext = upNext.size,
            )
        }.catch { e ->
            // Logged, so blank cards leave a trace in the log viewer rather than no clue at all.
            logger.error(TAG, e) { "Could not read the hub's counts" }
            emit(HubUiState(isLoading = false, loadFailed = true))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = HubUiState(),
        )
}

private const val TAG = "HubViewModel"
