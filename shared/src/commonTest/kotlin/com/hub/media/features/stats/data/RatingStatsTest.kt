package com.hub.media.features.stats.data

import com.hub.media.core.database.dao.RatedItemRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** [ratingStats] (#232): a period's rated items as per-title scores, a top list, an average and a spread. */
class RatingStatsTest {
    private fun at(day: Int) = Instant.parse("2025-03-${day.toString().padStart(2, '0')}T12:00:00Z")

    private fun row(
        id: String,
        rating: Int,
        at: Instant?,
        title: String = id,
    ) = RatedItemRow(id, title, rating, at)

    @Test
    fun nothingRated_isNull() {
        assertNull(ratingStats(emptyList()))
    }

    @Test
    fun aTitleRatedTwice_isOneEntry_atItsAverage_andCountsOnceInTheAverage() {
        val stats = ratingStats(listOf(row("a", 10, at(1)), row("a", 6, at(2)), row("b", 4, at(3))))!!
        assertEquals(2, stats.titles)
        val a = stats.top.first { it.mediaId == "a" }
        assertEquals(4.0, a.stars)
        assertEquals(2, a.rated)
        assertEquals(at(2), a.latestAt)
        assertEquals(3.0, stats.averageStars, "mean of 4.0 and 2.0, not of 5, 3 and 2")
    }

    @Test
    fun ties_goToTheMostRecent_datelessLast_thenAToZ() {
        val stats =
            ratingStats(
                listOf(
                    row("old", 10, at(1)),
                    row("new", 10, at(9)),
                    row("none", 10, null),
                    row("b", 10, at(5), title = "Beta"),
                    row("a", 10, at(5), title = "alpha"),
                ),
            )!!
        assertEquals(listOf("new", "a", "b", "old", "none"), stats.top.map { it.mediaId })
    }

    @Test
    fun theTopList_isCapped_andHighestFirst() {
        val rows = (1..7).map { row("t$it", it + 2, at(it)) }
        val stats = ratingStats(rows, top = 5)!!
        assertEquals(listOf("t7", "t6", "t5", "t4", "t3"), stats.top.map { it.mediaId })
        assertEquals(7, stats.titles)
    }

    @Test
    fun theSpread_hasTenBuckets_andAnInBetweenScoreGoesWhereItsStarsRound() {
        // 8 and 9 halves average to 8.5 halves = 4.25 stars, which rounds to 4.5 (index 8).
        val stats = ratingStats(listOf(row("x", 8, at(1)), row("x", 9, at(2)), row("y", 2, at(3))))!!
        assertEquals(10, stats.spread.size)
        assertEquals(1, stats.spread[8])
        assertEquals(1, stats.spread[1])
        assertEquals(2, stats.spread.sum())
        val x = stats.top.first { it.mediaId == "x" }
        assertEquals(9, x.roundedHalves)
        assertTrue(x.showsExactScore)
        assertFalse(stats.top.first { it.mediaId == "y" }.showsExactScore)
    }

    @Test
    fun mostCommon_isTheFullestBar_andATiePicksTheHigherScore() {
        val stats = ratingStats(listOf(row("a", 6, at(1)), row("b", 8, at(2))))!!
        assertEquals(8, stats.mostCommonHalves)
    }
}
