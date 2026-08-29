package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.sampleReadingSession
import com.hub.media.core.database.testAppDatabase
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.features.settings.data.setWeekStartDay
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
import kotlin.time.Instant

/**
 * [StatsViewModel] tests against a real in-memory [AppDatabase] (same builder/style as
 * [LibraryViewModelTest]/[BookDetailViewModelTest]), so the `combine` -> `stateIn` wiring over
 * [StatsRepository]'s reactive queries is exercised end to end.
 *
 * [timeZone]/[clock] are fixed so "this week"/"this month" bounds are deterministic and every
 * inserted session in these tests deliberately falls inside them, matching [StatsRepository]'s
 * "sum only known values" semantics already covered directly by
 * [com.hub.media.features.stats.data.StatsRepositoryTest]. `"This month"`'s bounds and `now` itself
 * are still computed once at construction (see [StatsViewModel]'s KDoc); `"this week"`'s bounds are
 * now reactive to [settingsRepository]'s week-start-day preference (ROADMAP Task 7 Phase B) — see
 * the `uiState_weekStartDayChange_*` tests below for that reactivity specifically.
 *
 * Room-backed, so this class lives in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81), same as [LibraryViewModelTest]/[BookDetailViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: StatsRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var mediaId: String
    private val viewModels = ViewModelRegistry()

    private val timeZone = TimeZone.UTC
    private val now: Instant = LocalDateTime(2024, 6, 19, 15, 0).toInstant(timeZone)
    private val clock: Clock =
        object : Clock {
            override fun now(): Instant = now
        }

    @BeforeTest
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main; UnconfinedTestDispatcher runs launched
        // coroutines eagerly so uiState updates are observable without manually pumping a
        // TestCoroutineScheduler (same convention as LibraryViewModelTest).
        viewModels.installMain()
        db = testAppDatabase()
        repository = StatsRepository(db)
        settingsRepository = SettingsRepository(db.appSettingsDao())
        mediaId = "media-1"
    }

    @AfterTest
    fun tearDown() {
        // Cancel every ViewModel's viewModelScope (and its stateIn/WhileSubscribed sharing
        // coroutine) before closing the database or resetting Main -- see ViewModelRegistry's
        // KDoc for why this order matters.
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertBook() {
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId))
    }

    private fun newViewModel() =
        viewModels.track(
            StatsViewModel(
                statsRepository = repository,
                settingsRepository = settingsRepository,
                timeZone = timeZone,
                clock = clock,
            ),
        )

    /**
     * Holds one subscriber on [StatsViewModel.uiState] for the rest of the test.
     *
     * Needed by any test that reads the flow, changes something underneath it, then reads again.
     * `first {}` **cancels its collection as soon as the predicate matches**, so between two such
     * reads the subscriber count is zero — and `uiState` is shared with
     * `SharingStarted.WhileSubscribed(5.seconds)`, which stops the upstream Room query that long
     * after its last subscriber leaves.
     *
     * Whether the upstream survived that gap therefore came down to how the test scheduler
     * happened to advance its *virtual* clock, which `runTest` moves eagerly whenever the test
     * coroutine suspends. That is not something the test controls, and it is why
     * `uiState_reactsToNewSessionInsert` passed locally and failed once on CI against an unrelated
     * commit (#91). Keeping a collector alive removes the gap rather than trying to time it.
     */
    private fun TestScope.holdUiStateSubscribed(viewModel: StatsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }

    @Test
    fun uiState_initialValue_isLoading() {
        val viewModel = newViewModel()

        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun uiState_transitionsFromLoadingToPopulated_reflectingExistingSessions() =
        runTest {
            insertBook()
            db.readingSessionDao().insert(
                sampleReadingSession(mediaId = mediaId, timestampStart = now, durationSeconds = 600, deltaPages = 10),
            )

            val viewModel = newViewModel()
            val populated = viewModel.uiState.first { !it.isLoading }

            assertEquals(600L, populated.week.timeReadSeconds)
            assertEquals(1, populated.week.sessionCount)
            assertEquals(10, populated.week.pagesRead)
            assertEquals(600L, populated.month.timeReadSeconds)
            assertEquals(1, populated.month.sessionCount)
            assertEquals(10, populated.month.pagesRead)
            assertEquals(1, populated.currentStreakDays, "a single session on \"today\" is a 1-day streak")
        }

    @Test
    fun uiState_reactsToNewSessionInsert() =
        runTest {
            insertBook()
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            val initial = viewModel.uiState.first { !it.isLoading }
            assertEquals(0, initial.week.sessionCount)
            assertEquals(0, initial.currentStreakDays)

            db.readingSessionDao().insert(
                sampleReadingSession(mediaId = mediaId, timestampStart = now, durationSeconds = 300, deltaPages = 5),
            )

            // Waits for the insert to have propagated through *every* field, not for one of them
            // to move. StatsViewModel.periodFlow is a combine of four independent Room flows, so
            // one insert makes them re-emit separately and the combine publishes each halfway
            // permutation in between: sessionCount 1 with pagesRead still null, and pagesRead 5
            // with sessionCount still 0. Both were observed. Any single-field predicate therefore
            // catches an intermediate and then asserts a field that has not caught up, which is
            // what made this test flaky (#91) -- the original only skipped the intermediates
            // because nothing was subscribed to observe them.
            //
            // It is worse than one combine, too: uiState is a *nested* combine, of the week
            // period, the month period, the reading streak and the lifetime count. So the streak
            // lags independently of the week's own fields, and a predicate that settles the week
            // can still be handed a stale streak -- which is what kept failing on CI after the
            // week fields were pinned.
            //
            // So the predicate has to name every field this test asserts. That reads as though it
            // asserts nothing, and it is worth being plain about why it does not: if the state
            // never becomes this, `first` never returns and the test fails by timeout rather than
            // by comparison. The predicate *is* the assertion. The `assertEquals` lines below
            // restate it in a form that names the expected values when it does fail.
            val updated =
                viewModel.uiState.first {
                    it.week.sessionCount == 1 &&
                        it.week.pagesRead == 5 &&
                        it.week.timeReadSeconds == 300L &&
                        it.currentStreakDays == 1
                }

            assertEquals(1, updated.week.sessionCount)
            assertEquals(300L, updated.week.timeReadSeconds)
            assertEquals(5, updated.week.pagesRead)
            assertEquals(1, updated.currentStreakDays)
        }

    @Test
    fun uiState_nullDurationSession_countedButNotTimed() =
        runTest {
            insertBook()
            db.readingSessionDao().insert(
                sampleReadingSession(
                    mediaId = mediaId,
                    timestampStart = now,
                    durationSeconds = null,
                    deltaPages = null,
                ),
            )

            val viewModel = newViewModel()
            val populated = viewModel.uiState.first { !it.isLoading }

            assertEquals(1, populated.week.sessionCount, "a null-duration session still counts as a session")
            assertEquals(null, populated.week.timeReadSeconds, "a null-duration session must not surface as known time")
            assertEquals(null, populated.week.pagesRead)
            assertEquals(1, populated.currentStreakDays)
        }

    @Test
    fun uiState_sessionOutsideWeekBoundsButInsideMonth_excludedFromWeekIncludedInMonth() =
        runTest {
            insertBook()
            // Insert a session on June 10 (before the week starting June 17, but within June)
            // now is June 19, 2024 (Wednesday); the ISO week runs Monday June 17 through Sunday June 23
            val earlyMonthInstant = LocalDateTime(2024, 6, 10, 12, 0).toInstant(timeZone)
            val weekSessionInstant = now

            db.readingSessionDao().insert(
                sampleReadingSession(
                    mediaId = mediaId,
                    timestampStart = earlyMonthInstant,
                    durationSeconds = 200,
                    deltaPages = 5,
                ),
            )
            db.readingSessionDao().insert(
                sampleReadingSession(
                    mediaId = mediaId,
                    timestampStart = weekSessionInstant,
                    durationSeconds = 300,
                    deltaPages = 8,
                ),
            )

            val viewModel = newViewModel()
            val populated = viewModel.uiState.first { !it.isLoading }

            assertEquals(1, populated.week.sessionCount, "only the June 19 session is in this week")
            assertEquals(300L, populated.week.timeReadSeconds)
            assertEquals(8, populated.week.pagesRead)
            assertEquals(2, populated.month.sessionCount, "both June 10 and June 19 sessions are in this month")
            assertEquals(500L, populated.month.timeReadSeconds, "200 + 300")
            assertEquals(13, populated.month.pagesRead, "5 + 8")
        }

    // ---- books-finished stat (ROADMAP Task 6 Phase C) ----------------------------------------

    @Test
    fun uiState_booksFinished_lifetimeAndInWeekBothReflectFinishedBook() =
        runTest {
            insertBook()
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = mediaId, status = ReadingStatus.FINISHED, finishedAt = now),
            )

            val viewModel = newViewModel()
            val populated = viewModel.uiState.first { !it.isLoading }

            assertEquals(1, populated.lifetimeBooksFinished)
            assertEquals(1, populated.week.booksFinished)
            assertEquals(1, populated.month.booksFinished)
        }

    @Test
    fun uiState_booksFinished_outsideWeekBounds_excludedFromWeekButCountsLifetime() =
        runTest {
            insertBook()
            val earlyMonthInstant = LocalDateTime(2024, 6, 10, 12, 0).toInstant(timeZone)
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = mediaId, status = ReadingStatus.FINISHED, finishedAt = earlyMonthInstant),
            )

            val viewModel = newViewModel()
            val populated = viewModel.uiState.first { !it.isLoading }

            assertEquals(1, populated.lifetimeBooksFinished, "lifetime total is unaffected by any period bound")
            assertEquals(0, populated.week.booksFinished, "June 10 falls outside the June 17-23 week")
            assertEquals(1, populated.month.booksFinished, "June 10 is still within June")
        }

    // ---- week-start-day reactivity (ROADMAP Task 7 Phase B) ------------------------------------

    @Test
    fun uiState_weekStartDayChange_reBucketsWeekPeriodButNotMonth() =
        runTest {
            insertBook()
            // now = June 19, 2024 (Wednesday). Under the default MONDAY start, "this week" is
            // June 17 (Mon) - June 24 (exclusive); a session on June 16 (Sunday) falls just outside it.
            // Under a SUNDAY start, "this week" instead runs June 16 (Sun) - June 23 (exclusive), which
            // *includes* that same June 16 session -- see StatsRepositoryTest's
            // thisWeekBounds_sundayStart_wednesdayMapsToPrecedingSunday for the bound math this relies
            // on. A second, June 10 session is never in *either* week window (before or after the
            // change) but is always within "this month" -- proving the setting reshapes "this week"
            // only, per the ROADMAP's decided semantics, never "this month".
            val juneSixteenInstant = LocalDateTime(2024, 6, 16, 12, 0).toInstant(timeZone)
            val juneTenInstant = LocalDateTime(2024, 6, 10, 12, 0).toInstant(timeZone)
            db.readingSessionDao().insert(
                sampleReadingSession(
                    mediaId = mediaId,
                    timestampStart = juneSixteenInstant,
                    durationSeconds = 400,
                    deltaPages = 7,
                ),
            )
            db.readingSessionDao().insert(
                sampleReadingSession(
                    mediaId = mediaId,
                    timestampStart = juneTenInstant,
                    durationSeconds = 250,
                    deltaPages = 4,
                ),
            )

            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)
            val beforeChange = viewModel.uiState.first { !it.isLoading }
            assertEquals(
                0,
                beforeChange.week.sessionCount,
                "June 16 must be excluded from the default MONDAY-start week",
            )
            assertEquals(2, beforeChange.month.sessionCount, "both June 10 and June 16 fall within June")

            settingsRepository.setWeekStartDay(WeekStartDay.SUNDAY)

            // Keyed on the fully-settled week rather than on any one field: the same combine of
            // four Room flows publishes every halfway permutation, so a single-field predicate
            // catches one of them. See the longer note in uiState_reactsToNewSessionInsert.
            val afterChange =
                viewModel.uiState.first {
                    it.week.sessionCount == 1 && it.week.pagesRead == 7 && it.week.timeReadSeconds == 400L
                }
            assertEquals(1, afterChange.week.sessionCount, "June 16 must be included once the week starts on Sunday")
            assertEquals(400L, afterChange.week.timeReadSeconds)
            assertEquals(7, afterChange.week.pagesRead)
            assertEquals(
                2,
                afterChange.month.sessionCount,
                "the month period must be unaffected by the week-start-day setting",
            )
            assertEquals(650L, afterChange.month.timeReadSeconds, "400 + 250, unchanged by the setting change")
        }
}
