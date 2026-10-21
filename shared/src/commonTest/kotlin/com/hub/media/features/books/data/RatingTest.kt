package com.hub.media.features.books.data

import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ratings in half-steps, and a title's rating from its reads (#230). */
class RatingTest {
    private fun read(
        n: Int,
        rating: Int?,
    ) = ReadThroughEntity("r$n", "b", n, ReadingStatus.FINISHED, rating = rating)

    @Test
    fun validRatings_areOneToTen() {
        assertEquals((1..10).toList(), (-1..12).filter { isValidRating(it) })
    }

    @Test
    fun noRatedReads_isNull() {
        assertNull(titleRating(listOf(read(1, null), read(2, null))))
    }

    @Test
    fun averageAndLatest_ignoreUnratedReads() {
        // 4.0 then unrated then 5.0: average 4.5, latest 5.0, from 2 rated reads.
        assertEquals(TitleRating(4.5, 5.0, 2), titleRating(listOf(read(1, 8), read(2, null), read(3, 10))))
    }

    @Test
    fun latest_isTheHighestNumberedRatedRead_notListOrder() {
        assertEquals(3.0, titleRating(listOf(read(2, 6), read(1, 10)))!!.latestStars)
    }

    @Test
    fun starsToHalves_acceptsOnlyTheHalfGrid() {
        assertEquals(9, starsToHalves(4.5))
        assertEquals(1, starsToHalves(0.5))
        assertEquals(10, starsToHalves(5.0))
        assertNull(starsToHalves(4.3))
        assertNull(starsToHalves(0.0))
        assertNull(starsToHalves(5.5))
        assertNull(starsToHalves(Double.NaN), "NaN cannot be rounded; it must be refused, not thrown")
        assertNull(starsToHalves(Double.POSITIVE_INFINITY))
    }
}
