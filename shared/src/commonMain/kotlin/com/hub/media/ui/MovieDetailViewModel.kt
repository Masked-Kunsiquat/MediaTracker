package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.TitleRating
import com.hub.media.features.books.data.titleRating
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.features.media.domain.BulkDeleteUseCase
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.tv.domain.WatchedWhen
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

    /**
     * @property viewings The film's watch history (#192), newest first.
     * @property pendingWatched Becoming Watched is waiting on the "when?" prompt.
     * @property pendingWatchedAgain "Watched again" is waiting on the same prompt.
     * @property viewingUndo The viewings just deleted, for the snackbar's Undo.
     * @property statusUndo The status just left Watched, for the snackbar's Undo.
     */
    public data class Ready(
        val movie: MediaWithDetails.Movie,
        val errorMessage: String? = null,
        val viewings: List<WatchLogEntity> = emptyList(),
        val pendingWatched: Boolean = false,
        val pendingWatchedAgain: Boolean = false,
        val viewingUndo: FilmViewingUndo? = null,
        val statusUndo: StatusUndo? = null,
    ) : MovieDetailUiState() {
        /** Your rating across this film's rated viewings (#231): [viewings] are newest first, so oldest first here. */
        val titleRating: TitleRating? get() = titleRating(viewings.reversed().map { it.rating })
    }
}

/**
 * Viewings deleted in a row, merged into one Undo the way an episode's are (#90). [demoted] is
 * whether any delete in the run moved the film to Watchlist, so undoing it puts Watched back.
 */
public data class FilmViewingUndo(
    val id: Long,
    val logs: List<WatchLogEntity>,
    val demoted: Boolean,
)

/** A status change away from Watched, offered back by the snackbar ("Moved to Watching", Undo). */
public data class StatusUndo(
    val id: Long,
    val previous: WatchStatus,
    val current: WatchStatus,
)

/**
 * Drives the movie detail screen (ROADMAP Task 13 Phase B) — the movie counterpart of
 * [BookDetailViewModel], and deliberately the same shape, including routing deletion through
 * [BulkDeleteUseCase] so a movie's poster gets the same reference-aware cleanup a book's cover
 * does.
 *
 * Since #192 it also owns the film's watch history: becoming Watched asks when (the route shows the
 * prompt while [MovieDetailUiState.Ready.pendingWatched] is set), and leaving Watched keeps the
 * viewings and offers Undo.
 */
public class MovieDetailViewModel(
    private val movieId: String,
    private val movieRepository: MovieRepository,
    private val deleteMediaUseCase: BulkDeleteUseCase,
) : ViewModel() {
    private val errorMessage = MutableStateFlow<String?>(null)
    private val pendingWatched = MutableStateFlow(false)
    private val pendingWatchedAgain = MutableStateFlow(false)
    private val viewingUndo = MutableStateFlow<FilmViewingUndo?>(null)
    private val statusUndo = MutableStateFlow<StatusUndo?>(null)
    private var undoSeq = 0L

    private data class Overlay(
        val pendingWatched: Boolean,
        val pendingWatchedAgain: Boolean,
        val viewingUndo: FilmViewingUndo?,
        val statusUndo: StatusUndo?,
    )

    private val overlay = combine(pendingWatched, pendingWatchedAgain, viewingUndo, statusUndo, ::Overlay)

    public val uiState: StateFlow<MovieDetailUiState> =
        combine(
            movieRepository.observeMovieDetail(movieId),
            movieRepository.observeViewings(movieId),
            errorMessage,
            overlay,
        ) { movie, viewings, error, o ->
            if (movie == null) {
                MovieDetailUiState.NotFound
            } else {
                MovieDetailUiState.Ready(
                    movie = movie,
                    errorMessage = error,
                    viewings = viewings,
                    pendingWatched = o.pendingWatched,
                    pendingWatchedAgain = o.pendingWatchedAgain,
                    viewingUndo = o.viewingUndo,
                    statusUndo = o.statusUndo,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = MovieDetailUiState.Loading,
        )

    /**
     * Quick status change without a full edit round-trip, mirroring
     * [BookDetailViewModel.updateStatus]. Becoming Watched asks when first ([confirmWatched]); any
     * other change is written straight away, and leaving Watched offers [undoStatus].
     *
     * Writes only the status, via [MovieRepository.updateWatchStatus] — it deliberately does not
     * re-send the movie's other fields, since none of them are what the user just changed. See that
     * function's KDoc for what re-sending them cost.
     */
    public fun requestStatus(status: WatchStatus) {
        val ready = uiState.value as? MovieDetailUiState.Ready ?: return
        if (status == WatchStatus.WATCHED && ready.movie.details?.status != WatchStatus.WATCHED) {
            pendingWatched.value = true
            return
        }
        viewModelScope.launch {
            when (val result = movieRepository.updateWatchStatus(mediaId = movieId, status = status)) {
                is Resource.Error -> errorMessage.value = result.message
                is Resource.Success ->
                    if (result.data == WatchStatus.WATCHED && status != WatchStatus.WATCHED) {
                        statusUndo.value = StatusUndo(++undoSeq, previous = result.data, current = status)
                    }
            }
        }
    }

    /** Becomes Watched at [watchedWhen], rating that viewing with the dialog's stars when chosen (#231). */
    public fun confirmWatched(
        watchedWhen: WatchedWhen,
        rating: Int? = null,
    ) {
        // Cleared before the launch, so a recomposition cannot show the prompt a second time.
        pendingWatched.value = false
        viewModelScope.launch {
            val result = movieRepository.updateWatchStatus(movieId, WatchStatus.WATCHED, watchedWhen, rating)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    public fun dismissWatched() {
        pendingWatched.value = false
    }

    public fun requestWatchedAgain() {
        pendingWatchedAgain.value = true
    }

    public fun confirmWatchedAgain(
        watchedWhen: WatchedWhen,
        rating: Int? = null,
    ) {
        pendingWatchedAgain.value = false
        viewModelScope.launch {
            val result = movieRepository.addViewing(movieId, watchedWhen, rating)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /** Rates one viewing from the History list (#231); `null` clears it. */
    public fun rateViewing(
        logId: String,
        halves: Int?,
    ) {
        viewModelScope.launch {
            val result = movieRepository.setViewingRating(logId, halves)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    public fun dismissWatchedAgain() {
        pendingWatchedAgain.value = false
    }

    /** Deletes one viewing; deletes in a row merge into one Undo. */
    public fun deleteViewing(log: WatchLogEntity) {
        viewModelScope.launch {
            when (val result = movieRepository.deleteViewing(log.id)) {
                is Resource.Error -> errorMessage.value = result.message
                is Resource.Success -> {
                    val deletion = result.data
                    viewingUndo.value =
                        viewingUndo.value?.let {
                            it.copy(
                                id = ++undoSeq,
                                logs = it.logs + deletion.log,
                                demoted =
                                    it.demoted || deletion.demoted,
                            )
                        }
                            ?: FilmViewingUndo(++undoSeq, listOf(deletion.log), deletion.demoted)
                }
            }
        }
    }

    public fun undoViewing() {
        val undo = viewingUndo.value ?: return
        viewingUndo.value = null
        viewModelScope.launch {
            val result = movieRepository.restoreViewings(undo.logs, undo.demoted)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /**
     * The snackbar for [id] timed out: the deletes stand. Keyed, so an older snackbar cannot clear
     * a newer merged Undo.
     */
    public fun consumeViewingUndo(id: Long) {
        if (viewingUndo.value?.id == id) viewingUndo.value = null
    }

    public fun undoStatus() {
        val undo = statusUndo.value ?: return
        statusUndo.value = null
        viewModelScope.launch {
            val result = movieRepository.restoreStatus(movieId, undo.previous, expectedCurrent = undo.current)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    public fun consumeStatusUndo(id: Long) {
        if (statusUndo.value?.id == id) statusUndo.value = null
    }

    public fun editViewingDate(
        log: WatchLogEntity,
        watchedWhen: WatchedWhen,
    ) {
        viewModelScope.launch {
            val result = movieRepository.editViewingDate(log.id, watchedWhen)
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
