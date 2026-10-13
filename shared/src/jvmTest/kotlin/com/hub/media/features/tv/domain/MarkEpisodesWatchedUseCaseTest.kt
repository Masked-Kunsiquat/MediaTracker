package com.hub.media.features.tv.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** [MarkEpisodesWatchedUseCase] (#131) against a real database, in a zone west of UTC. */
class MarkEpisodesWatchedUseCaseTest {
    private class FixedClock(
        var now: Instant,
    ) : Clock {
        override fun now(): Instant = now
    }

    private val la = TimeZone.of("America/Los_Angeles")
    private lateinit var db: AppDatabase
    private lateinit var clock: FixedClock
    private lateinit var useCase: MarkEpisodesWatchedUseCase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        clock = FixedClock(Instant.parse("2026-10-12T20:00:00Z"))
        useCase = MarkEpisodesWatchedUseCase(db, clock, zone = { la })
    }

    @AfterTest
    fun tearDown() = db.close()

    /** A show of one season of [count] quick-filled episodes; returns them in episode order. */
    private suspend fun season(count: Int): List<EpisodeEntity> {
        val r = TVShowRepository(db).addShow(title = "S", seasons = listOf(SeasonQuickFill(1, episodeCount = count)))
        assertIs<Resource.Success<String>>(r)
        return db.episodeDao().getByMediaIdAndSeason(r.data, 1)
    }

    /** Adds one dated episode to [like]'s show and returns it. */
    private suspend fun datedEpisode(
        like: EpisodeEntity,
        number: Int,
        air: Instant,
    ): EpisodeEntity {
        val e = EpisodeEntity(newId(), like.mediaId, 1, number, airDate = air)
        db.tvWriteDao().insertEpisodes(listOf(e))
        return e
    }

    private suspend fun watchedAt(id: String) = db.episodeDao().getById(id)!!.watchedAt

    @Test
    fun now_marksTargetAndCatchUp() =
        runTest {
            val (e1, e2, e3) = season(3)
            val result = useCase.execute(listOf(e3.id, e1.id, e2.id), WatchedWhen.Now)
            assertEquals(Resource.Success(3), result)
            for (e in listOf(e1, e2, e3)) assertEquals(clock.now, watchedAt(e.id))
        }

    @Test
    fun onAirDate_givesEachEpisodeItsOwnCalendarDay() =
        runTest {
            val base = season(1).single()
            val a = datedEpisode(base, 2, Instant.parse("2024-02-16T00:00:00Z"))
            val b = datedEpisode(base, 3, Instant.parse("2024-02-23T00:00:00Z"))
            assertEquals(Resource.Success(2), useCase.execute(listOf(b.id, a.id), WatchedWhen.OnAirDate))
            assertEquals(LocalDate(2024, 2, 16), watchedAt(a.id)!!.toLocalDateTime(la).date)
            assertEquals(LocalDate(2024, 2, 23), watchedAt(b.id)!!.toLocalDateTime(la).date)
        }

    @Test
    fun onAirDate_missingOnAnyEpisode_writesNothing() =
        runTest {
            val base = season(1).single() // quick-filled: no air date
            val dated = datedEpisode(base, 2, Instant.parse("2024-02-16T00:00:00Z"))
            assertIs<Resource.Error>(useCase.execute(listOf(dated.id, base.id), WatchedWhen.OnAirDate))
            assertNull(watchedAt(dated.id))
            assertNull(watchedAt(base.id))
        }

    @Test
    fun multiEpisodeRequest_skipsUnaired() =
        runTest {
            val (e1, e2) = season(2)
            val future = datedEpisode(e1, 3, clock.now + 1.days)
            val result = useCase.execute(listOf(e1.id, e2.id, future.id), WatchedWhen.Now, targetIsExplicit = false)
            assertEquals(Resource.Success(2), result)
            assertNull(watchedAt(future.id))
        }

    @Test
    fun singleUnairedTarget_isStillWritten() =
        runTest {
            val future = datedEpisode(season(1).single(), 2, clock.now + 1.days)
            assertEquals(Resource.Success(1), useCase.execute(listOf(future.id), WatchedWhen.Now))
            assertEquals(clock.now, watchedAt(future.id))
        }

    @Test
    fun alreadyWatched_isNeverOverwritten() =
        runTest {
            val e1 = season(1).single()
            useCase.execute(listOf(e1.id), WatchedWhen.On(LocalDate(2024, 1, 1)))
            val first = watchedAt(e1.id)
            assertEquals(Resource.Success(0), useCase.execute(listOf(e1.id), WatchedWhen.Now))
            assertEquals(first, watchedAt(e1.id))
        }
}
