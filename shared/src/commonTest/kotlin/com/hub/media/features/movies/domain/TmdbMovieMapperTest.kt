package com.hub.media.features.movies.domain

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.features.movies.data.MovieMetadataValidation
import com.hub.media.features.tv.network.dto.TmdbMovieDetailsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [toMovieMapping] -- where TMDB's answer about a film is interpreted before it reaches the
 * database. Pure, so it lives in `commonTest` and runs on every target.
 *
 * Deliberately mirrors [com.hub.media.features.tv.domain.TmdbShowMapperTest]'s cases where the two
 * mappers share a rule, so a change to one that is not made to the other shows up as an asymmetry in
 * the tests rather than only in production.
 */
class TmdbMovieMapperTest {
    private fun movie(
        id: Int = 603,
        title: String? = "The Matrix",
        releaseDate: String? = "1999-03-30",
        runtime: Int? = 136,
        voteAverage: Double? = 8.2,
        voteCount: Int? = 24000,
        posterPath: String? = "/poster.jpg",
    ) = TmdbMovieDetailsDto(
        id = id,
        title = title,
        overview = "A hacker learns the truth.",
        releaseDate = releaseDate,
        runtime = runtime,
        posterPath = posterPath,
        status = "Released",
        voteAverage = voteAverage,
        voteCount = voteCount,
    )

    @Test
    fun mapsTheFilmRecordOntoTheAddMovieArguments() {
        val mapping = movie().toMovieMapping()

        assertTrue(mapping != null)
        assertEquals("The Matrix", mapping.title)
        assertEquals(1999, mapping.releaseYear)
        assertEquals(136, mapping.runtimeMinutes)
        assertEquals(8.2, mapping.communityRating)
        assertEquals("/poster.jpg", mapping.posterPath)
        assertEquals(
            listOf(IdentifierProvider.TMDB to "603"),
            mapping.externalIdentifiers,
            "without this the row cannot be traced back to the record it came from",
        )
    }

    @Test
    fun aFilmWithNoUsableTitleMapsToNull() {
        assertNull(movie(title = null).toMovieMapping())
        assertNull(movie(title = "   ").toMovieMapping())
    }

    @Test
    fun anUnratedFilmIsUnknownRatherThanZeroOutOfTen() {
        // TMDB answers an unrated title with vote_average 0.0 -- the mean of an empty set, not a
        // score. Confirmed against live TMDB on #128.
        val mapping = movie(voteAverage = 0.0, voteCount = 0).toMovieMapping()

        assertNull(mapping!!.communityRating, "no votes means unknown, not zero")
    }

    @Test
    fun aGenuineZeroWithVotesIsKept() {
        assertEquals(0.0, movie(voteAverage = 0.0, voteCount = 5).toMovieMapping()!!.communityRating)
    }

    @Test
    fun aZeroRuntimeBecomesUnknownRatherThanZero() {
        // 0 is never a runtime; validateRuntimeMinutes would reject a stored 0 and fail the film.
        assertNull(movie(runtime = 0).toMovieMapping()!!.runtimeMinutes)
    }

    @Test
    fun aReleaseYearOutsideTheAcceptedWindowIsDroppedRatherThanFailingTheFilm() {
        // "0001-01-01" yields year 1, which validateReleaseYear rejects -- and one rejected field
        // fails the whole addMovie, so a nonsense date would cost the user the film itself.
        val mapping = movie(releaseDate = "0001-01-01").toMovieMapping()

        assertTrue(mapping != null, "a nonsense date must not lose the film")
        assertNull(mapping.releaseYear)
        assertEquals("The Matrix", mapping.title)
    }

    @Test
    fun aReleaseYearAtEitherBoundaryIsKept() {
        // The film floor is 1888, not television's 1928 -- film predates broadcast by decades.
        assertEquals(
            MovieMetadataValidation.MIN_RELEASE_YEAR,
            movie(releaseDate = "1888-10-14").toMovieMapping()!!.releaseYear,
        )
        assertEquals(
            MovieMetadataValidation.MAX_RELEASE_YEAR,
            movie(releaseDate = "2100-01-01").toMovieMapping()!!.releaseYear,
        )
    }

    @Test
    fun anUnparseableDateBecomesNullRatherThanFailingTheFilm() {
        val mapping = movie(releaseDate = "not-a-date").toMovieMapping()

        assertTrue(mapping != null)
        assertNull(mapping.releaseYear)
    }
}
