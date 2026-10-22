package com.hub.media.ui

import com.hub.media.core.database.dao.ShowStateRow
import com.hub.media.core.database.dao.TVProgressCounts
import com.hub.media.core.database.entities.AiringStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [countFinishedShows] (#217): "shows finished" is the library's own rule, so Stats and the library
 * can never disagree about whether a show is done.
 */
class ShowsFinishedTest {
    private fun state(
        id: String,
        airing: AiringStatus? = AiringStatus.ENDED,
        abandoned: Boolean = false,
        finishedEarlierRun: Boolean = false,
    ) = ShowStateRow(
        mediaId = id,
        airingStatus = airing,
        abandoned = abandoned,
        finishedEarlierRun = finishedEarlierRun,
    )

    /** [total] episodes, [watched] of them watched; [specials] of the total are season 0, unwatched. */
    private fun progress(
        id: String,
        total: Int,
        watched: Int,
        specials: Int = 0,
    ) = id to TVProgressCounts(id, total, watched, totalMain = total - specials, watchedMain = watched)

    @Test
    fun anEndedShow_allWatched_isFinished() {
        assertEquals(1, countFinishedShows(listOf(state("a")), mapOf(progress("a", 10, 10)), includeSpecials = true))
    }

    @Test
    fun aShowStillAiring_allWatched_isNotFinished() {
        assertEquals(
            0,
            countFinishedShows(
                listOf(state("a", AiringStatus.CONTINUING)),
                mapOf(progress("a", 10, 10)),
                includeSpecials = true,
            ),
        )
    }

    @Test
    fun abandoned_partlyWatched_andEmptyShows_areNotFinished() {
        val states = listOf(state("x", abandoned = true), state("p"), state("empty"))
        val progress = mapOf(progress("x", 10, 10), progress("p", 10, 4))

        assertEquals(0, countFinishedShows(states, progress, includeSpecials = true))
    }

    @Test
    fun unwatchedSpecials_countOnlyWhenTheSettingIncludesThem() {
        // 12 episodes, 2 of them specials and unwatched: every regular episode is watched.
        val states = listOf(state("a"))
        val progress = mapOf(progress("a", total = 12, watched = 10, specials = 2))

        assertEquals(1, countFinishedShows(states, progress, includeSpecials = false))
        assertEquals(0, countFinishedShows(states, progress, includeSpecials = true))
    }

    /** Watch again resets the ticks (#231), but a show being rewatched was still finished once. */
    @Test
    fun rewatchInProgress_stillCountsAsFinished() {
        val states =
            listOf(
                state("re", finishedEarlierRun = true),
                state("airing", AiringStatus.CONTINUING, finishedEarlierRun = true),
            )
        val progress = mapOf(progress("re", 10, 3), progress("airing", 10, 3))

        assertEquals(
            1,
            countFinishedShows(states, progress, includeSpecials = true),
            "a show still airing is never finished",
        )
    }

    /** Abandoning a rewatch does not undo the finished first watch-through (#231). */
    @Test
    fun anAbandonedRewatch_ofAFinishedShow_stillCounts() {
        val states = listOf(state("a", abandoned = true, finishedEarlierRun = true))
        assertEquals(1, countFinishedShows(states, mapOf(progress("a", 10, 2)), includeSpecials = true))
    }
}
