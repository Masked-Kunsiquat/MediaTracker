package com.hub.media.features.tv.data

import com.hub.media.core.database.dao.TVProgressRow
import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.WatchThroughEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a watch-through reads as, and when it asks for a rating (#231). */
class WatchThroughStateTest {
    private val run = WatchThroughEntity("r", "s", 1)

    private fun counts(
        watched: Int,
        total: Int,
    ) = TVProgressRow(mediaId = "s", totalEpisodes = total, watchedEpisodes = watched)

    @Test
    fun labels() {
        assertEquals(RunState.NOT_STARTED, runState(run, counts(0, 3), null))
        assertEquals(RunState.WATCHING, runState(run, counts(1, 3), null))
        assertEquals(RunState.FINISHED, runState(run, counts(3, 3), AiringStatus.ENDED))
        assertEquals(RunState.FINISHED, runState(run, counts(3, 3), null), "unknown airing reads as finished")
        assertEquals(RunState.UP_TO_DATE, runState(run, counts(3, 3), AiringStatus.CONTINUING))
        assertEquals(RunState.ABANDONED, runState(run.copy(abandoned = true), counts(3, 3), null))
        assertEquals(RunState.NOT_STARTED, runState(run, counts(0, 0), null), "no episodes is never complete")
    }

    @Test
    fun asksOnce_whenARunCompletes_unrated() {
        assertTrue(asksToRateRun(wasComplete = false, nowComplete = true, rating = null))
        assertFalse(asksToRateRun(wasComplete = true, nowComplete = true, rating = null))
        assertFalse(asksToRateRun(wasComplete = false, nowComplete = true, rating = 8))
        assertFalse(asksToRateRun(wasComplete = false, nowComplete = false, rating = null))
    }
}
