package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.dao.FinishOutcomes
import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.sampleReadingSession
import com.hub.media.core.database.testAppDatabase
import com.hub.media.features.settings.data.ReadingGoal
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.features.settings.data.observeReadingGoal
import com.hub.media.features.settings.data.observeStatsDomain
import com.hub.media.features.settings.data.observeStatsPeriod
import com.hub.media.features.settings.data.setReadingGoal
import com.hub.media.features.settings.data.setStatsDomain
import com.hub.media.features.settings.data.setWeekStartDay
import com.hub.media.features.stats.data.GoalProgress
import com.hub.media.features.stats.data.GoalStatus
import com.hub.media.features.stats.data.StatsPeriod
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [StatsViewModel] against a real in-memory database, at a fixed "now" of Wednesday 19 June 2024
 * 15:00 UTC: one period at a time (#91), the chosen period remembered, and -- the point of #91 -- a
 * write never publishing a state whose numbers disagree with each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: StatsRepository
    private lateinit var settingsRepository: SettingsRepository
    private val mediaId = "media-1"
    private val viewModels = ViewModelRegistry()

    private val timeZone = TimeZone.UTC
    private val now: Instant = LocalDateTime(2024, 6, 19, 15, 0).toInstant(timeZone)

    /** "Now" as the clock reports it; a test may move it to cross midnight. */
    private var clockNow: Instant = now
    private val clock: Clock =
        object : Clock {
            override fun now(): Instant = clockNow
        }

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        repository = StatsRepository(db)
        settingsRepository = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() {
        // Cancel every ViewModel's viewModelScope before closing the database or resetting Main --
        // see ViewModelRegistry's KDoc for why this order matters.
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertBook() {
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId))
    }

    private suspend fun session(
        at: Instant,
        durationSeconds: Long? = 300,
        deltaPages: Int? = 5,
    ) {
        db.readingSessionDao().insertIntoLatestRead(
            sampleReadingSession(
                mediaId = mediaId,
                timestampStart = at,
                durationSeconds = durationSeconds,
                deltaPages = deltaPages,
            ),
        )
    }

    private fun june(
        day: Int,
        hour: Int = 12,
    ): Instant = LocalDateTime(2024, 6, day, hour, 0).toInstant(timeZone)

    private fun newViewModel() =
        viewModels.track(
            StatsViewModel(
                statsRepository = repository,
                settingsRepository = settingsRepository,
                timeZone = timeZone,
                clock = clock,
            ),
        )

    private fun TestScope.holdUiStateSubscribed(viewModel: StatsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }

    @Test
    fun uiState_initialValue_isLoading() {
        assertTrue(newViewModel().uiState.value.isLoading)
    }

    @Test
    fun byDefault_itShowsThisWeek_withExistingSessionsAndTheStreak() =
        runTest {
            insertBook()
            session(now, durationSeconds = 600, deltaPages = 10)

            val state = newViewModel().uiState.first { !it.isLoading }

            assertEquals(StatsPeriod.WEEK, state.period)
            assertEquals(
                StatsUiState.Period(
                    600L,
                    1,
                    10,
                    0,
                    averageSessionSeconds = 600L,
                    longestSessionSeconds = 600L,
                    pagesPerHour = 60,
                ),
                state.stats,
            )
            assertEquals(1, state.currentStreakDays, "a single session on \"today\" is a 1-day streak")
        }

    @Test
    fun aNullDurationSession_isCountedButNotTimed() =
        runTest {
            insertBook()
            session(now, durationSeconds = null, deltaPages = null)

            val state = newViewModel().uiState.first { !it.isLoading }

            assertEquals(StatsUiState.Period(null, 1, null, 0), state.stats)
        }

    @Test
    fun selectingAPeriod_showsItsNumbers_andIsRemembered() =
        runTest {
            insertBook()
            session(june(10), durationSeconds = 200, deltaPages = 5)
            session(now, durationSeconds = 300, deltaPages = 8)
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            // Positive control: this week holds only the June 19 session.
            assertEquals(
                1,
                viewModel.uiState
                    .first { !it.isLoading }
                    .stats.sessionCount,
            )

            viewModel.selectPeriod(StatsPeriod.MONTH)

            val month = viewModel.uiState.first { it.period == StatsPeriod.MONTH }
            // 13 pages in 500 s is 93.6 pages/hour, rounded to 94.
            assertEquals(
                StatsUiState.Period(
                    500L,
                    2,
                    13,
                    0,
                    averageSessionSeconds = 250L,
                    longestSessionSeconds = 300L,
                    pagesPerHour = 94,
                ),
                month.stats,
            )
            assertEquals(StatsPeriod.MONTH, settingsRepository.observeStatsPeriod().first())
            // A fresh screen opens where the user left it.
            assertEquals(StatsPeriod.MONTH, newViewModel().uiState.first { !it.isLoading }.period)
        }

    @Test
    fun anUnknownStoredPeriod_fallsBackToWeek() =
        runTest {
            settingsRepository.setString("stats_period", "FORTNIGHT")

            assertEquals(StatsPeriod.WEEK, newViewModel().uiState.first { !it.isLoading }.period)
        }

    @Test
    fun booksFinished_followThePeriod_andAllTimeCountsEveryFinish() =
        runTest {
            insertBook()
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = mediaId, status = ReadingStatus.FINISHED, finishedAt = june(10)),
            )
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)

            assertEquals(
                0,
                viewModel.uiState
                    .first { !it.isLoading }
                    .stats.booksFinished,
                "June 10 is outside this week",
            )
            viewModel.selectPeriod(StatsPeriod.ALL_TIME)
            assertEquals(
                1,
                viewModel.uiState
                    .first { it.period == StatsPeriod.ALL_TIME }
                    .stats.booksFinished,
            )
        }

    @Test
    fun aWeekStartChange_reBucketsTheWeek() =
        runTest {
            insertBook()
            // Sunday 16 June: outside a Monday-start week of the 19th, inside a Sunday-start one.
            session(june(16), durationSeconds = 400, deltaPages = 7)
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            val monday = viewModel.uiState.first { !it.isLoading }.stats
            assertEquals(0, monday.sessionCount)

            settingsRepository.setWeekStartDay(WeekStartDay.SUNDAY)

            // Waits for any change, then names the expected whole period, so a regression fails on
            // the comparison rather than by a minute-long timeout.
            assertEquals(
                StatsUiState.Period(
                    400L,
                    1,
                    7,
                    0,
                    averageSessionSeconds = 400L,
                    longestSessionSeconds = 400L,
                    pagesPerHour = 63,
                ),
                viewModel.uiState
                    .first {
                        it.stats !=
                            monday
                    }.stats,
            )
        }

    /**
     * #91 itself. Every state published around a write must be the before-snapshot or the
     * after-snapshot -- never a mix such as "1 session, pages still unknown", which the old
     * four-flow combine published. The whole period is compared, never one field: a single-field
     * predicate is exactly what let the old flake hide.
     */
    @Test
    fun aWrite_neverPublishesAHalfwayState() =
        runTest {
            insertBook()
            val viewModel = newViewModel()
            val seen = mutableListOf<StatsUiState.Period>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { if (!it.isLoading) seen += it.stats }
            }
            val before = viewModel.uiState.first { !it.isLoading }.stats
            val after =
                StatsUiState.Period(
                    300L,
                    1,
                    5,
                    0,
                    averageSessionSeconds = 300L,
                    longestSessionSeconds = 300L,
                    pagesPerHour = 60,
                )

            session(now, durationSeconds = 300, deltaPages = 5)
            viewModel.uiState.first { it.stats == after && it.currentStreakDays == 1 }

            val halfway = seen.filter { it != before && it != after }
            assertTrue(halfway.isEmpty(), "published a halfway state: $halfway")
        }

    @Test
    fun afterMidnight_pickingAPeriod_refreshesTheStreakToo() =
        runTest {
            insertBook()
            session(now) // the 19th
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            assertEquals(1, viewModel.uiState.first { !it.isLoading }.currentStreakDays)

            // Past midnight, the user reads again and picks a period: today counts toward the streak.
            clockNow = LocalDateTime(2024, 6, 20, 9, 0).toInstant(timeZone)
            session(clockNow)
            viewModel.selectPeriod(StatsPeriod.MONTH)

            val after = viewModel.uiState.first { it.period == StatsPeriod.MONTH }
            assertEquals(2, after.currentStreakDays, "the 19th and the 20th")
        }

    // --- The goal and the months (#219) ---------------------------------------------------------

    /** Finishes [count] books, each on its own media item, at [at]. */
    private suspend fun finishBooks(
        count: Int,
        at: Instant,
    ) {
        repeat(count) { i ->
            val id = "finished-$i-${at.toEpochMilliseconds()}"
            db.mediaItemDao().insert(sampleMediaItem(id = id))
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = id, status = ReadingStatus.FINISHED, finishedAt = at),
            )
        }
    }

    @Test
    fun withNoGoal_theCardOffersThisYear() =
        runTest {
            val state = newViewModel().uiState.first { !it.isLoading }

            assertEquals(null, state.goal)
            assertEquals(2024, state.goalYear)
        }

    @Test
    fun settingAGoal_showsProgressAgainstThisYearsFinishes() =
        runTest {
            finishBooks(5, at = june(3))
            finishBooks(2, at = LocalDateTime(2023, 12, 30, 12, 0).toInstant(timeZone)) // last year: not counted
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            viewModel.uiState.first { !it.isLoading }

            viewModel.setGoal(12)

            // 19 June 2024 is day 171 of a 366-day year: 12 * 171 / 366 = 5.6, so 5 are expected.
            val goal = viewModel.uiState.first { it.goal != null }.goal
            assertEquals(GoalProgress(goal = 12, finished = 5, expected = 5, status = GoalStatus.OnPace), goal)
        }

    @Test
    fun lastYearsGoal_isNotShown() =
        runTest {
            settingsRepository.setReadingGoal(year = 2023, books = 20)

            val state = newViewModel().uiState.first { !it.isLoading }

            assertEquals(null, state.goal)
            // Positive control: the goal really is stored.
            assertEquals(ReadingGoal(2023, 20), settingsRepository.observeReadingGoal(2023).first())
        }

    @Test
    fun clearingTheGoal_removesIt() =
        runTest {
            settingsRepository.setReadingGoal(year = 2024, books = 12)
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            viewModel.uiState.first { it.goal != null }

            viewModel.clearGoal()

            assertEquals(null, viewModel.uiState.first { !it.isLoading && it.goal == null }.goal)
        }

    @Test
    fun theMonthsAppearOnlyOnTheYearView() =
        runTest {
            finishBooks(2, at = june(3))
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            assertEquals(null, viewModel.uiState.first { !it.isLoading }.finishesByMonth, "not on Week")

            viewModel.selectPeriod(StatsPeriod.YEAR)

            val months = viewModel.uiState.first { it.finishesByMonth != null }.finishesByMonth!!
            assertEquals(12, months.size)
            assertEquals(2, months[5], "June")
        }

    /**
     * Left open across New Year, the screen moves on by itself: last year's goal is no longer this
     * year's, and the card offers the new year (#219 review). Before, "today" was re-read only when
     * the user picked a period, changed the week start or came back to the screen.
     */
    @Test
    fun openAcrossNewYear_theGoalCardMovesToTheNewYear() =
        runTest {
            clockNow = LocalDateTime(2024, 12, 31, 23, 59).toInstant(timeZone)
            settingsRepository.setReadingGoal(year = 2024, books = 12)
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            assertEquals(2024, viewModel.uiState.first { it.goal != null }.goalYear)

            clockNow = LocalDateTime(2025, 1, 1, 0, 0, 30).toInstant(timeZone)
            viewModels.advanceMainTimeBy(2.minutes)

            val state = viewModel.uiState.first { it.goalYear == 2025 }
            assertEquals(null, state.goal)
        }

    // --- Part 2: the breakdown and the finish outcomes (#219) -----------------------------------

    @Test
    fun theBreakdown_followsThePeriod() =
        runTest {
            insertBook()
            db.bookDetailsDao().insert(sampleBookDetails(mediaId = mediaId))
            session(june(19), durationSeconds = 600) // this week
            session(june(3), durationSeconds = 900) // this month, an earlier week
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)

            assertEquals(
                600L,
                viewModel.uiState
                    .first { !it.isLoading && it.breakdown.totalSeconds > 0 }
                    .breakdown.totalSeconds,
            )

            viewModel.selectPeriod(StatsPeriod.MONTH)

            assertEquals(
                1_500L,
                viewModel.uiState
                    .first { it.period == StatsPeriod.MONTH && it.breakdown.totalSeconds == 1_500L }
                    .breakdown.totalSeconds,
            )
        }

    @Test
    fun theFinishOutcomes_appearOnlyOnAllTime() =
        runTest {
            finishBooks(1, at = june(3))
            db.mediaItemDao().insert(sampleMediaItem(id = "dnf-1"))
            db.bookDetailsDao().insert(sampleBookDetails(mediaId = "dnf-1", status = ReadingStatus.DNF))
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            assertEquals(null, viewModel.uiState.first { !it.isLoading }.finishOutcomes, "not on Week")

            viewModel.selectPeriod(StatsPeriod.ALL_TIME)

            assertEquals(FinishOutcomes(1, 1), viewModel.uiState.first { it.finishOutcomes != null }.finishOutcomes)
        }

    // --- #217: the Films and Shows tabs ----------------------------------------------------------

    private suspend fun filmWatchedAt(at: Instant) {
        db.movieWriteDao().insertMediaItem(sampleMediaItem(id = "film-1", title = "Arrival", type = MediaType.MOVIE))
        db.movieWriteDao().insertMovieDetails(MovieDetailsEntity(mediaId = "film-1", runtimeMinutes = 116))
        db.movieWriteDao().insertViewing(WatchLogEntity(id = "fl-1", mediaId = "film-1", watchedAt = at))
    }

    /** An ended one-episode show, its episode watched at [at]: finished by the library's rule. */
    private suspend fun showEpisodeWatchedAt(at: Instant) {
        db.tvWriteDao().insertMediaItem(sampleMediaItem(id = "show-1", title = "Severance", type = MediaType.TV_SHOW))
        db.tvWriteDao().insertTVDetails(TVDetailsEntity(mediaId = "show-1", airingStatus = AiringStatus.ENDED))
        db.tvWriteDao().insertEpisodes(
            listOf(
                EpisodeEntity(
                    id = "ep-1",
                    mediaId = "show-1",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    watchedAt = at,
                    runtimeMinutes = 50,
                ),
            ),
        )
        db.movieWriteDao().insertViewing(
            WatchLogEntity(id = "sl-1", mediaId = "show-1", episodeId = "ep-1", watchedAt = at),
        )
    }

    @Test
    fun selectingFilms_showsFilmViewings_andIsRemembered() =
        runTest {
            filmWatchedAt(june(19))
            showEpisodeWatchedAt(june(19))
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            viewModel.uiState.first { !it.isLoading }

            viewModel.selectDomain(MediaType.MOVIE)

            val state = viewModel.uiState.first { it.domain == MediaType.MOVIE && it.viewing != null }
            assertEquals(1, state.viewing!!.breakdown.viewings)
            assertEquals(6_960L, state.viewing!!.breakdown.knownSeconds, "the film, not the episode")
            assertEquals(MediaType.MOVIE, settingsRepository.observeStatsDomain().first())
        }

    @Test
    fun theShowsTab_countsEpisodes_notFilms() =
        runTest {
            filmWatchedAt(june(19))
            showEpisodeWatchedAt(june(19))
            settingsRepository.setStatsDomain(MediaType.TV_SHOW)

            val state = newViewModel().uiState.first { it.viewing != null }

            assertEquals(1, state.viewing!!.breakdown.viewings)
            assertEquals(3_000L, state.viewing!!.breakdown.knownSeconds)
        }

    @Test
    fun showsFinished_isOnlyOnTheShowsTab_underAllTime() =
        runTest {
            showEpisodeWatchedAt(june(19))
            settingsRepository.setStatsDomain(MediaType.TV_SHOW)
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            assertEquals(
                null,
                viewModel.uiState
                    .first { it.viewing != null }
                    .viewing!!
                    .showsFinished,
                "not on Week",
            )

            viewModel.selectPeriod(StatsPeriod.ALL_TIME)

            assertEquals(
                1,
                viewModel.uiState
                    .first { it.viewing?.showsFinished != null }
                    .viewing!!
                    .showsFinished,
            )
        }

    @Test
    fun theBooksTab_hasNoViewingNumbers() =
        runTest {
            filmWatchedAt(june(19))

            val state = newViewModel().uiState.first { !it.isLoading }

            assertEquals(MediaType.BOOK, state.domain)
            assertEquals(null, state.viewing)
        }

    /**
     * Opened from the Films library while Books is the remembered tab, Stats opens on Films and
     * remembers it (#217). Whether Books could flash first is a race this test cannot create, so the
     * hold-back itself is pinned by `OpeningOnTest` with the sequence a slow write produces.
     */
    @Test
    fun openedForFilms_opensOnFilms_andRemembersIt() =
        runTest {
            settingsRepository.setStatsDomain(MediaType.BOOK)
            val viewModel =
                viewModels.track(
                    StatsViewModel(
                        statsRepository = repository,
                        settingsRepository = settingsRepository,
                        timeZone = timeZone,
                        clock = clock,
                        initialDomain = MediaType.MOVIE,
                    ),
                )
            val seen = mutableListOf<MediaType>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { if (!it.isLoading) seen += it.domain }
            }

            viewModel.uiState.first { !it.isLoading && it.domain == MediaType.MOVIE }

            assertEquals(listOf(MediaType.MOVIE), seen.distinct())
            assertEquals(MediaType.MOVIE, settingsRepository.observeStatsDomain().first(), "and it is remembered")
        }

    /**
     * The hold-back is once per visit (#217, CodeRabbit): opened for Films, switched to Books, then
     * left in the background past the 5-second window and resumed, Stats must follow Books -- not
     * wait again for Films, which would freeze every number on the screen.
     */
    @Test
    fun afterResuming_theHoldBackDoesNotReturn() =
        runTest {
            val viewModel =
                viewModels.track(
                    StatsViewModel(
                        statsRepository = repository,
                        settingsRepository = settingsRepository,
                        timeZone = timeZone,
                        clock = clock,
                        initialDomain = MediaType.MOVIE,
                    ),
                )
            val first =
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { viewModel.uiState.collect { } }
            viewModel.uiState.first { it.domain == MediaType.MOVIE && !it.isLoading }
            viewModel.selectDomain(MediaType.BOOK)
            viewModel.uiState.first { it.domain == MediaType.BOOK }

            // Leave: nobody collects for longer than WhileSubscribed's 5 seconds, so the upstream stops.
            first.cancel()
            viewModels.advanceMainTimeBy(6.seconds)
            // Come back, and change something so a fresh state is needed.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { } }
            viewModel.selectPeriod(StatsPeriod.YEAR)

            val state = viewModel.uiState.first { it.period == StatsPeriod.YEAR }
            assertEquals(MediaType.BOOK, state.domain)
        }
}
