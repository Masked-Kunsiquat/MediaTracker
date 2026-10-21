package com.hub.media.core.database.dao

import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.ui.LibraryStatusFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * [TVProgressCounts.counted] -- the one place the include-specials setting (#88) is applied. Shapes
 * mirror the shows `docs/sample-data` carries for exactly this purpose.
 */
class TVProgressCountsTest {
    /** 19 regular episodes, every one watched, plus one unwatched special. */
    private val severance =
        TVProgressCounts("severance", totalEpisodes = 20, watchedEpisodes = 19, totalMain = 19, watchedMain = 19)
    private val chernobyl =
        TVProgressCounts("chernobyl", totalEpisodes = 5, watchedEpisodes = 5, totalMain = 5, watchedMain = 5)
    private val fleabag =
        TVProgressCounts("fleabag", totalEpisodes = 6, watchedEpisodes = 3, totalMain = 6, watchedMain = 3)

    @Test
    fun severance_specialsIncluded_isNineteenOfTwenty_andInProgress() {
        val row = severance.counted(includeSpecials = true)
        assertEquals(TVProgressRow("severance", 20, 19), row)
        assertEquals(
            LibraryStatusFilter.IN_PROGRESS,
            LibraryStatusFilter.ofShow(abandoned = false, row, AiringStatus.ENDED),
        )
    }

    @Test
    fun severance_specialsExcluded_isNineteenOfNineteen_andFinished() {
        val row = severance.counted(includeSpecials = false)
        assertEquals(TVProgressRow("severance", 19, 19), row)
        assertEquals(
            LibraryStatusFilter.FINISHED,
            LibraryStatusFilter.ofShow(abandoned = false, row, AiringStatus.ENDED),
        )
    }

    @Test
    fun showsWithoutSpecials_readTheSameEitherWay() {
        for (counts in listOf(chernobyl, fleabag)) {
            assertEquals(counts.counted(includeSpecials = true), counts.counted(includeSpecials = false))
        }
        assertEquals(
            LibraryStatusFilter.FINISHED,
            LibraryStatusFilter.ofShow(abandoned = false, chernobyl.counted(includeSpecials = false)),
        )
    }

    @Test
    fun specialsOnlyShow_excluded_fallsBackToCountingThemAll() {
        // Without the fallback the main pair is 0 / 0, which ofShow reads as NOT_STARTED forever.
        val specialsOnly = TVProgressCounts("x", totalEpisodes = 2, watchedEpisodes = 2, totalMain = 0, watchedMain = 0)
        val row = specialsOnly.counted(includeSpecials = false)
        assertEquals(TVProgressRow("x", 2, 2), row)
        assertEquals(LibraryStatusFilter.FINISHED, LibraryStatusFilter.ofShow(abandoned = false, row))
    }

    @Test
    fun of_buildsTheSameCountsAsTheQuery() {
        fun ep(
            season: Int,
            number: Int,
            watched: Boolean,
        ) = EpisodeEntity(
            id = "$season-$number",
            mediaId = "severance",
            seasonNumber = season,
            episodeNumber = number,
            watchedAt = if (watched) Instant.fromEpochMilliseconds(1L) else null,
        )
        val episodes =
            listOf(ep(0, 1, watched = false)) +
                (1..9).map { ep(1, it, watched = true) } +
                (1..10).map { ep(2, it, watched = true) }

        assertEquals(severance, TVProgressCounts.of("severance", episodes))
    }
}
