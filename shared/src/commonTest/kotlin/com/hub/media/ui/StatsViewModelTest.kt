package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.sampleReadingSession
import com.hub.media.core.database.testAppDatabase
import com.hub.media.features.stats.data.StatsRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * [StatsViewModel] tests against a real in-memory [AppDatabase] (same builder/style as
 * [LibraryViewModelTest]/[BookDetailViewModelTest]), so the `combine` -> `stateIn` wiring over
 * [StatsRepository]'s reactive queries is exercised end to end.
 *
 * [timeZone]/[clock] are fixed so "this week"/"this month" bounds (computed once at
 * [StatsViewModel] construction — see its KDoc) are deterministic and every inserted session in
 * these tests deliberately falls inside them, matching [StatsRepository]'s "sum only known
 * values" semantics already covered directly by [com.hub.media.features.stats.data.StatsRepositoryTest].
 *
 * Room-backed, so this class is excluded from the android unit-test variant by exact class name
 * in shared/build.gradle.kts, same as [LibraryViewModelTest]/[BookDetailViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: StatsRepository
    private lateinit var mediaId: String

    private val timeZone = TimeZone.UTC
    private val now: Instant = LocalDateTime(2024, 6, 19, 15, 0).toInstant(timeZone)
    private val clock: Clock = object : Clock {
        override fun now(): Instant = now
    }

    @BeforeTest
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main; UnconfinedTestDispatcher runs launched
        // coroutines eagerly so uiState updates are observable without manually pumping a
        // TestCoroutineScheduler (same convention as LibraryViewModelTest).
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = testAppDatabase()
        repository = StatsRepository(db)
        mediaId = "media-1"
    }

    @AfterTest
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertBook() {
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId))
    }

    private fun newViewModel() = StatsViewModel(statsRepository = repository, timeZone = timeZone, clock = clock)

    @Test
    fun uiState_initialValue_isLoading() {
        val viewModel = newViewModel()

        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun uiState_transitionsFromLoadingToPopulated_reflectingExistingSessions() = runTest {
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
    fun uiState_reactsToNewSessionInsert() = runTest {
        insertBook()
        val viewModel = newViewModel()
        val initial = viewModel.uiState.first { !it.isLoading }
        assertEquals(0, initial.week.sessionCount)
        assertEquals(0, initial.currentStreakDays)

        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = now, durationSeconds = 300, deltaPages = 5),
        )

        val updated = viewModel.uiState.first { it.week.sessionCount > 0 }

        assertEquals(1, updated.week.sessionCount)
        assertEquals(300L, updated.week.timeReadSeconds)
        assertEquals(5, updated.week.pagesRead)
        assertEquals(1, updated.currentStreakDays)
    }

    @Test
    fun uiState_nullDurationSession_countedButNotTimed() = runTest {
        insertBook()
        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = now, durationSeconds = null, deltaPages = null),
        )

        val viewModel = newViewModel()
        val populated = viewModel.uiState.first { !it.isLoading }

        assertEquals(1, populated.week.sessionCount, "a null-duration session still counts as a session")
        assertEquals(null, populated.week.timeReadSeconds, "a null-duration session must not surface as known time")
        assertEquals(null, populated.week.pagesRead)
        assertEquals(1, populated.currentStreakDays)
    }

    @Test
    fun uiState_sessionOutsideWeekBoundsButInsideMonth_excludedFromWeekIncludedInMonth() = runTest {
        insertBook()
        // Insert a session on June 10 (before the week starting June 17, but within June)
        // now is June 19, 2024 (Wednesday); the ISO week runs Monday June 17 through Sunday June 23
        val earlyMonthInstant = LocalDateTime(2024, 6, 10, 12, 0).toInstant(timeZone)
        val weekSessionInstant = now

        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = earlyMonthInstant, durationSeconds = 200, deltaPages = 5),
        )
        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = weekSessionInstant, durationSeconds = 300, deltaPages = 8),
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
}
