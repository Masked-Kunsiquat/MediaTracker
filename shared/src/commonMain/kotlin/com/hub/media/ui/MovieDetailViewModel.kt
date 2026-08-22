package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.Resource
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.features.media.domain.BulkDeleteUseCase
import com.hub.media.features.movies.data.MovieRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** State of the movie detail screen (ROADMAP Task 13 Phase B). */
public sealed class MovieDetailUiState {
    /** The first emission has not arrived yet. */
    public data object Loading : MovieDetailUiState()

    /**
     * No movie with this id. Reached when the id was deleted, never existed, **or belongs to a
     * different media type** — [MovieRepository.observeMovieDetail] gates on
     * [com.hub.media.core.database.entities.MediaType.MOVIE], so a book id routed here shows "not
     * found" rather than a book rendered with movie labels.
     */
    public data object NotFound : MovieDetailUiState()

    public data class Ready(
        val movie: MediaWithDetails.Movie,
        val errorMessage: String? = null,
    ) : MovieDetailUiState()
}

/**
 * Drives the movie detail screen (ROADMAP Task 13 Phase B) — the movie counterpart of
 * [BookDetailViewModel], and deliberately the same shape, including routing deletion through
 * [BulkDeleteUseCase] so a movie's poster gets the same reference-aware cleanup a book's cover
 * does.
 */
public class MovieDetailViewModel(
    private val movieId: String,
    private val movieRepository: MovieRepository,
    private val deleteMediaUseCase: BulkDeleteUseCase,
) : ViewModel() {
    private val errorMessage = MutableStateFlow<String?>(null)

    public val uiState: StateFlow<MovieDetailUiState> =
        combine(
            movieRepository.observeMovieDetail(movieId),
            errorMessage,
        ) { movie, error ->
            if (movie == null) {
                MovieDetailUiState.NotFound
            } else {
                MovieDetailUiState.Ready(movie = movie, errorMessage = error)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = MovieDetailUiState.Loading,
        )

    /**
     * Quick status change without a full edit round-trip, mirroring
     * [BookDetailViewModel.updateStatus].
     *
     * Writes only the status, via [MovieRepository.updateWatchStatus] — it deliberately does not
     * re-send the movie's other fields, since none of them are what the user just changed. See that
     * function's KDoc for what re-sending them cost.
     */
    public fun updateStatus(status: WatchStatus) {
        if (uiState.value !is MovieDetailUiState.Ready) return
        viewModelScope.launch {
            val result = movieRepository.updateWatchStatus(mediaId = movieId, status = status)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /** Deletes this movie via [BulkDeleteUseCase], so an unreferenced poster file is cleaned up. */
    public fun deleteMovie() {
        viewModelScope.launch {
            val result = deleteMediaUseCase.execute(listOf(movieId))
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /** Acknowledges an error once shown, so it is not re-displayed on the next recomposition. */
    public fun consumeError() {
        errorMessage.value = null
    }
}
