package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.sampleReadingSession
import com.hub.media.core.database.testAppDatabase
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.setReadingGoal
import com.hub.media.features.stats.data.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/** [YearReviewViewModel] (#221) against a real database, "now" being 19 June 2026 in UTC. */
@OptIn(ExperimentalCoroutinesApi::class)
class YearReviewViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private val viewModels = ViewModelRegistry()
    private val zone = TimeZone.UTC
    private val clock =
        object : Clock {
            override fun now(): Instant = LocalDateTime(2026, 6, 19, 15, 0).toInstant(zone)
        }

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        settings = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    private fun on(
        year: Int,
        month: Int = 3,
    ): Instant = LocalDateTime(year, month, 10, 20, 0).toInstant(zone)

    private fun newViewModel(initialYear: Int? = null) =
        viewModels.track(YearReviewViewModel(StatsRepository(db), settings, zone, clock, initialYear))

    private fun TestScope.hold(viewModel: YearReviewViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { } }
    }

    private var log = 0

    private suspend fun filmWatchedOn(at: Instant) {
        val id = "film-${log++}"
        db.movieWriteDao().insertMediaItem(sampleMediaItem(id = id, title = "Film $id", type = MediaType.MOVIE))
        db.movieWriteDao().insertMovieDetails(MovieDetailsEntity(mediaId = id, runtimeMinutes = 100))
        db.movieWriteDao().insertViewing(WatchLogEntity(id = "log-$id", mediaId = id, watchedAt = at))
    }

    @Test
    fun theYears_runFromTheFirstActivityToNow_newestFirst() =
        runTest {
            db.mediaItemDao().insert(sampleMediaItem(id = "b1"))
            db.readingSessionDao().insertIntoLatestRead(sampleReadingSession(mediaId = "b1", timestampStart = on(2024)))

            val state = newViewModel().uiState.first { !it.isLoading }

            assertEquals(listOf(2026, 2025, 2024), state.years)
            assertEquals(2026, state.year)
        }

    @Test
    fun withNothingLogged_theOnlyYearIsThisOne() =
        runTest {
            assertEquals(listOf(2026), newViewModel().uiState.first { !it.isLoading }.years)
        }

    @Test
    fun pickingAYear_rebuildsTheReviewForIt() =
        runTest {
            filmWatchedOn(on(2025))
            filmWatchedOn(on(2026))
            filmWatchedOn(on(2026, month = 4))
            val viewModel = newViewModel()
            hold(viewModel)
            assertEquals(
                2,
                viewModel.uiState
                    .first { it.review?.year == 2026 }
                    .review!!
                    .totals.films,
            )

            viewModel.selectYear(2025)

            assertEquals(
                1,
                viewModel.uiState
                    .first { it.review?.year == 2025 }
                    .review!!
                    .totals.films,
            )
        }

    @Test
    fun theGoal_followsTheYear() =
        runTest {
            settings.setReadingGoal(year = 2025, books = 30)
            settings.setReadingGoal(year = 2026, books = 12)
            val viewModel = newViewModel()
            hold(viewModel)
            assertEquals(
                12,
                viewModel.uiState
                    .first { it.review?.year == 2026 }
                    .review!!
                    .goal!!
                    .goal,
            )

            viewModel.selectYear(2025)

            assertEquals(
                30,
                viewModel.uiState
                    .first { it.review?.year == 2025 }
                    .review!!
                    .goal!!
                    .goal,
            )
        }

    @Test
    fun openedForAYear_startsThere() =
        runTest {
            filmWatchedOn(on(2025))

            assertEquals(
                2025,
                newViewModel(initialYear = 2025)
                    .uiState
                    .first { it.review != null }
                    .review!!
                    .year,
            )
        }

    // --- #232: the year's favourites --------------------------------------------------------------

    @Test
    fun favourites_useOnlyThatYearsRatings() =
        runTest {
            db.movieWriteDao().insertMediaItem(sampleMediaItem(id = "a", title = "Arrival", type = MediaType.MOVIE))
            db.movieWriteDao().insertMediaItem(sampleMediaItem(id = "h", title = "Heat", type = MediaType.MOVIE))
            db.movieWriteDao().insertViewing(WatchLogEntity("a1", "a", watchedAt = on(2024), rating = 10))
            db.movieWriteDao().insertViewing(WatchLogEntity("a2", "a", watchedAt = on(2025), rating = 7))
            db.movieWriteDao().insertViewing(WatchLogEntity("h1", "h", watchedAt = on(2025), rating = 8))

            // Over all time Arrival averages 8.5 halves and would beat Heat's 8; in 2025 alone it has 7.
            val review = newViewModel(initialYear = 2025).uiState.first { it.review?.year == 2025 }.review!!

            assertEquals("Heat", review.favourites?.film?.title, "Arrival's 2024 five stars do not count in 2025")
            assertNull(review.favourites?.book)
        }

    @Test
    fun aYearWithNothingRated_hasNoFavourites() =
        runTest {
            filmWatchedOn(on(2025))
            assertNull(
                newViewModel(initialYear = 2025)
                    .uiState
                    .first { it.review?.year == 2025 }
                    .review!!
                    .favourites,
            )
        }
}
