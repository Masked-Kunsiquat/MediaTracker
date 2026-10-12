package com.hub.media.features.tv.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.dao.UpNextRow
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * [TVShowRepository.observeUpNext] / [com.hub.media.core.database.dao.EpisodeDao.observeUpNext]
 * (#89) against a real database. Every exclusion test asserts a positive control first -- a show
 * that *should* be present is -- so "absent" cannot pass on an empty or broken query.
 */
class UpNextQueryTest {
    private class SteppingClock(
        var now: Instant,
    ) : Clock {
        override fun now(): Instant = now
    }

    private lateinit var db: AppDatabase
    private lateinit var clock: SteppingClock
    private lateinit var repo: TVShowRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        clock = SteppingClock(Instant.fromEpochMilliseconds(1_760_000_000_000))
        repo = TVShowRepository(db, clock)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun show(
        title: String,
        vararg seasons: Pair<Int, Int>,
    ): String {
        val result =
            repo.addShow(
                title = title,
                seasons =
                    seasons.map { (s, n) ->
                        SeasonQuickFill(s, episodeCount = n)
                    },
            )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    /** Watches episodes 1..[through] of [season], advancing the clock a minute per tick. */
    private suspend fun watch(
        showId: String,
        season: Int,
        through: Int,
    ) {
        for (episode in db.episodeDao().getByMediaIdAndSeason(showId, season).filter { it.episodeNumber <= through }) {
            clock.now += 1.minutes
            assertIs<Resource.Success<Unit>>(repo.setEpisodeWatched(episode.id, watched = true))
        }
    }

    private suspend fun Flow<List<UpNextRow>>.now() = first()

    @Test
    fun startedShow_offersItsLowestUnwatchedEpisode_withRemainingCount() =
        runTest {
            val fleabag = show("Fleabag", 1 to 6)
            watch(fleabag, season = 1, through = 3)

            val row = repo.observeUpNext(includeSpecials = true).now().single()
            assertEquals(fleabag, row.mediaId)
            assertEquals(1 to 4, row.seasonNumber to row.episodeNumber)
            assertEquals(3, row.remaining)
            assertNull(row.episodeTitle, "quick-filled, so no title -- the screen says 'Episode 4'")
        }

    @Test
    fun notStartedAndAbandonedShows_areLeftOut() =
        runTest {
            val started = show("Started", 1 to 3)
            watch(started, 1, through = 1)
            show("Never started", 1 to 3)
            val abandoned = show("Abandoned", 1 to 3)
            watch(abandoned, 1, through = 1)
            assertIs<Resource.Success<Unit>>(repo.updateWatchStatus(abandoned, WatchStatus.ABANDONED))

            val rows = repo.observeUpNext(includeSpecials = true).now()
            assertEquals(listOf("Started"), rows.map { it.showTitle })
        }

    @Test
    fun futureEpisode_isNotOffered_soAnUpToDateShowDropsOut() =
        runTest {
            val control = show("Control", 1 to 2)
            watch(control, 1, through = 1)
            val airing = show("Airing", 1 to 2)
            watch(airing, 1, through = 2)
            db.tvWriteDao().insertEpisodes(
                listOf(
                    EpisodeEntity(newId(), airing, 1, 3, airDate = clock.now + 7.days),
                ),
            )

            val rows = repo.observeUpNext(includeSpecials = true).now()
            assertEquals(listOf("Control"), rows.map { it.showTitle })

            // Once it has aired it is offered.
            clock.now += 8.days
            val later = repo.observeUpNext(includeSpecials = true).now()
            val row = assertNotNull(later.firstOrNull { it.mediaId == airing })
            assertEquals(1 to 3, row.seasonNumber to row.episodeNumber)
        }

    @Test
    fun specials_comeAfterRegularEpisodes_andOnlyWhenIncluded() =
        runTest {
            val severance = show("Severance", 0 to 1, 1 to 2)
            watch(severance, 1, through = 1)

            val midway = repo.observeUpNext(includeSpecials = true).now().single()
            assertEquals(
                1 to 2,
                midway.seasonNumber to midway.episodeNumber,
                "an unwatched special must not block the queue",
            )
            assertEquals(2, midway.remaining, "the special still counts as left while included")

            watch(severance, 1, through = 2)
            val special = repo.observeUpNext(includeSpecials = true).now().single()
            assertEquals(0 to 1, special.seasonNumber to special.episodeNumber)

            assertEquals(emptyList(), repo.observeUpNext(includeSpecials = false).now())
        }

    @Test
    fun showsAreOrderedByMostRecentlyWatched() =
        runTest {
            val a = show("A", 1 to 3)
            val b = show("B", 1 to 3)
            watch(a, 1, through = 1)
            watch(b, 1, through = 1)
            assertEquals(listOf("B", "A"), repo.observeUpNext(includeSpecials = true).now().map { it.showTitle })

            watch(a, 1, through = 2)
            assertEquals(listOf("A", "B"), repo.observeUpNext(includeSpecials = true).now().map { it.showTitle })
        }

    @Test
    fun tickingTheOfferedEpisode_advancesTheSameSubscription() =
        runTest {
            val showId = show("Show", 1 to 3)
            watch(showId, 1, through = 1)
            val queue = repo.observeUpNext(includeSpecials = true)

            val first = queue.first { rows -> rows.any { it.episodeNumber == 2 } }.single()
            repo.setEpisodeWatched(first.episodeId, watched = true)

            val next = queue.first { rows -> rows.any { it.episodeNumber == 3 } }.single()
            assertEquals(1, next.remaining)
        }
}
