package com.hub.media.ui

import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.movies.domain.TmdbMovieMapping
import com.hub.media.features.tv.domain.WatchedWhen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #214: the film form's rules, written once for Add film, the TMDB review and Edit film. */
class FilmFormStateTest {
    @Test
    fun choosingWatched_fromWatchlist_asksWhenAndKeepsStatus() {
        val state = FilmFormState().choosingStatus(WatchStatus.WATCHED)

        assertTrue(state.pendingWatched)
        assertEquals(WatchStatus.WATCHLIST, state.status)
    }

    @Test
    fun choosingWatched_whenItOpenedWatched_appliesAtOnce() {
        val state =
            FilmFormState(status = WatchStatus.WATCHING, initialStatus = WatchStatus.WATCHED)
                .choosingStatus(WatchStatus.WATCHED)

        assertFalse(state.pendingWatched)
        assertEquals(WatchStatus.WATCHED, state.status)
    }

    @Test
    fun confirmingWatched_appliesTheAnswer() {
        val state =
            FilmFormState()
                .choosingStatus(WatchStatus.WATCHED)
                .confirmingWatched(WatchedWhen.Now)

        assertFalse(state.pendingWatched)
        assertEquals(WatchStatus.WATCHED, state.status)
        assertEquals(WatchedWhen.Now, state.watchedWhen)
    }

    @Test
    fun dismissingWatched_keepsTheOldStatus() {
        val state = FilmFormState().choosingStatus(WatchStatus.WATCHED).dismissingWatched()

        assertFalse(state.pendingWatched)
        assertEquals(WatchStatus.WATCHLIST, state.status)
    }

    @Test
    fun choosingAnotherStatus_clearsWatchedWhen() {
        val state =
            FilmFormState(status = WatchStatus.WATCHED, watchedWhen = WatchedWhen.Now)
                .choosingStatus(WatchStatus.WATCHING)

        assertEquals(WatchStatus.WATCHING, state.status)
        assertNull(state.watchedWhen)
    }

    @Test
    fun parsed_blankNumbers_areNull() {
        val parsed = FilmFormState(title = "  Heat ").parsed()

        assertEquals(
            FilmFormParse.Valid(
                title = "Heat",
                releaseYear = null,
                runtimeMinutes = null,
                purchasePrice = null,
                status = WatchStatus.WATCHLIST,
                watchedWhen = null,
            ),
            parsed,
        )
    }

    @Test
    fun parsed_readsEveryNumber() {
        val parsed =
            FilmFormState(title = "Heat", releaseYear = "1995", runtimeMinutes = "170", purchasePrice = "9.99")
                .parsed() as FilmFormParse.Valid

        assertEquals(1995, parsed.releaseYear)
        assertEquals(170, parsed.runtimeMinutes)
        assertEquals(9.99, parsed.purchasePrice)
    }

    @Test
    fun parsed_unreadableRuntime_namesTheField() {
        assertEquals(
            FilmFormParse.Unreadable("runtime"),
            FilmFormState(title = "x", runtimeMinutes = "1.2.3").parsed(),
        )
    }

    @Test
    fun parsed_unreadableYearIsNamedBeforePrice() {
        assertEquals(
            FilmFormParse.Unreadable("release year"),
            FilmFormState(title = "x", releaseYear = "19x5", purchasePrice = "1.2.3").parsed(),
        )
    }

    @Test
    fun toFilmForm_fillsFromTmdbAndLeavesPriceBlank() {
        val form = TmdbMovieMapping(title = "Heat", releaseYear = 1995, runtimeMinutes = 170).toFilmForm()

        assertEquals("Heat", form.title)
        assertEquals("1995", form.releaseYear)
        assertEquals("170", form.runtimeMinutes)
        assertEquals("", form.purchasePrice)
        assertEquals(WatchStatus.WATCHLIST, form.status)
    }

    @Test
    fun toFilmForm_unknownNumbersAreBlank() {
        val form = TmdbMovieMapping(title = "Heat").toFilmForm()

        assertEquals("", form.releaseYear)
        assertEquals("", form.runtimeMinutes)
    }
}
