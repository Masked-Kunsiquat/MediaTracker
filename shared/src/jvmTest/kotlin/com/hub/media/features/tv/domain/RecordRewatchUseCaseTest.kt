package com.hub.media.features.tv.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

/** [RecordRewatchUseCase] (#90): "Watched again" adds a viewing, through #131's date rules. */
class RecordRewatchUseCaseTest {
    private class FixedClock(
        val now: Instant,
    ) : Clock {
        override fun now(): Instant = now
    }

    private val clock = FixedClock(Instant.parse("2026-10-13T20:00:00Z"))
    private val la = TimeZone.of("America/Los_Angeles")
    private lateinit var db: AppDatabase
    private lateinit var useCase: RecordRewatchUseCase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        useCase = RecordRewatchUseCase(db, clock, zone = { la })
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun watchedEpisode(at: Instant): String {
        val r = TVShowRepository(db).addShow(title = "S", seasons = listOf(SeasonQuickFill(1, episodeCount = 1)))
        assertIs<Resource.Success<String>>(r)
        val id =
            db
                .episodeDao()
                .getByMediaIdAndSeason(r.data, 1)
                .single()
                .id
        db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to at))
        return id
    }

    private suspend fun viewings(id: String) = db.watchLogDao().observeForEpisode(id).first()

    @Test
    fun rewatchNow_addsAViewing_andMovesWatchedAtForward() =
        runTest {
            val id = watchedEpisode(Instant.parse("2024-01-01T20:00:00Z"))
            assertIs<Resource.Success<Unit>>(useCase.execute(id, WatchedWhen.Now))
            assertEquals(2, viewings(id).size)
            assertEquals(clock.now, db.episodeDao().getById(id)!!.watchedAt)
        }

    @Test
    fun rewatchOnAPickedDay_isNoonLocal_andBackdatedKeepsWatchedAt() =
        runTest {
            val first = Instant.parse("2025-06-01T20:00:00Z")
            val id = watchedEpisode(first)
            useCase.execute(id, WatchedWhen.On(LocalDate(2024, 5, 1)))
            assertEquals(2, viewings(id).size)
            assertEquals(first, db.episodeDao().getById(id)!!.watchedAt, "a back-dated rewatch does not move watchedAt")
        }

    @Test
    fun rewatchOnAirDate_withoutAnAirDate_isRefused_andWritesNothing() =
        runTest {
            val id = watchedEpisode(Instant.parse("2024-01-01T20:00:00Z"))
            assertIs<Resource.Error>(useCase.execute(id, WatchedWhen.OnAirDate))
            assertEquals(1, viewings(id).size)
        }
}
