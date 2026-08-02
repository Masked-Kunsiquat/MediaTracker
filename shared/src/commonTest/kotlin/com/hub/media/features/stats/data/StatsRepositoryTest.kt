package com.hub.media.features.stats.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.sampleReadingSession
import com.hub.media.core.database.testAppDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

/**
 * [StatsRepository] tests against a real in-memory [AppDatabase] (same builder as
 * [com.hub.media.core.database.ReadingSessionDaoTest]/[com.hub.media.features.books.data.BookRepositoryTest]),
 * covering:
 *  - the null-vs-0 / "sum only known values" domain semantics from the ROADMAP Task 5 note,
 *    re-verified at the repository's public API (not just [com.hub.media.core.database.dao.StatsDao]'s
 *    raw SQL, which [com.hub.media.core.database.StatsDaoTest] already covers exhaustively);
 *  - [StatsRepository.observeReadingStreak]'s day-math with deterministic fixed [Clock]/[TimeZone]
 *    pairs, including a timezone edge case proving local-date (not UTC) bucketing is used;
 *  - [StatsRepository.thisWeekBounds]/[StatsRepository.thisMonthBounds]'s pure bound computation.
 *
 * Room-backed (real [AppDatabase]), so this class is excluded from the android unit-test variant
 * by the `com.hub.media.features.stats.*` package filter in shared/build.gradle.kts, same
 * reasoning as the books DAO/repository tests — `:shared:jvmTest` is the authoritative gate.
 */
class StatsRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: StatsRepository
    private lateinit var mediaId: String

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = StatsRepository(db)
        mediaId = "media-1"
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun insertBook() {
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId))
    }

    private suspend fun insertSession(
        timestampStart: Instant,
        durationSeconds: Long? = 600,
        deltaPages: Int? = 10,
    ) {
        db.readingSessionDao().insert(
            sampleReadingSession(
                mediaId = mediaId,
                timestampStart = timestampStart,
                durationSeconds = durationSeconds,
                deltaPages = deltaPages,
            ),
        )
    }

    private fun instant(epochSeconds: Long): Instant = Instant.fromEpochSeconds(epochSeconds)

    // ---- observeTimeReadInRange -------------------------------------------------------------

    @Test
    fun observeTimeReadInRange_excludesNullDurationSessions() = runTest {
        insertBook()
        insertSession(instant(1_100), durationSeconds = 100)
        insertSession(instant(1_200), durationSeconds = null)

        val total = repo.observeTimeReadInRange(instant(1_000), instant(2_000)).first()

        assertEquals(100L, total, "a null-duration session must not contribute to the time-read total")
    }

    @Test
    fun observeTimeReadInRange_emptyRange_isNullNotZero() = runTest {
        insertBook()

        val total = repo.observeTimeReadInRange(instant(1_000), instant(2_000)).first()

        assertNull(total, "no known-duration data in range must surface as null, not a misleading 0")
    }

    // ---- observeSessionCountInRange ----------------------------------------------------------

    @Test
    fun observeSessionCountInRange_countsNullDurationSessionsToo() = runTest {
        insertBook()
        insertSession(instant(1_100), durationSeconds = 100)
        insertSession(instant(1_200), durationSeconds = null)

        val count = repo.observeSessionCountInRange(instant(1_000), instant(2_000)).first()

        assertEquals(2, count, "a null-duration session still happened and must still be counted")
    }

    // ---- observePagesReadInRange -------------------------------------------------------------

    @Test
    fun observePagesReadInRange_ignoresNullDeltaPages() = runTest {
        insertBook()
        insertSession(instant(1_100), deltaPages = 20)
        insertSession(instant(1_200), deltaPages = null)

        val pages = repo.observePagesReadInRange(instant(1_000), instant(2_000)).first()

        assertEquals(20, pages)
    }

    @Test
    fun observePagesReadInRange_emptyRange_isNullNotZero() = runTest {
        insertBook()

        val pages = repo.observePagesReadInRange(instant(1_000), instant(2_000)).first()

        assertNull(pages)
    }

    // ---- range boundary ([from, to)) ---------------------------------------------------------

    @Test
    fun rangeBoundary_fromInclusive_toExclusive() = runTest {
        insertBook()
        insertSession(instant(1_000)) // exactly at `from` -> included
        insertSession(instant(2_000)) // exactly at `to` -> excluded

        val count = repo.observeSessionCountInRange(instant(1_000), instant(2_000)).first()

        assertEquals(1, count)
    }

    // ---- observeReadingStreak ----------------------------------------------------------------

    private fun fixedClock(instant: Instant): Clock = object : Clock {
        override fun now(): Instant = instant
    }

    /** One UTC calendar day's worth of milliseconds since epoch, offset by [hour] (UTC). */
    private fun utcDay(epochDay: Long, hour: Int = 12): Instant =
        Instant.fromEpochMilliseconds(epochDay * 86_400_000L + hour * 3_600_000L)

    @Test
    fun observeReadingStreak_threeConsecutiveDays_streakIsThree() = runTest {
        insertBook()
        insertSession(utcDay(100))
        insertSession(utcDay(99))
        insertSession(utcDay(98))

        val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100, hour = 18))).first()

        assertEquals(3, streak)
    }

    @Test
    fun observeReadingStreak_gapBreaksStreak() = runTest {
        insertBook()
        insertSession(utcDay(100))
        insertSession(utcDay(99))
        insertSession(utcDay(97)) // day 98 skipped

        val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100, hour = 18))).first()

        assertEquals(2, streak, "a gap day must stop the count immediately, not skip over it")
    }

    @Test
    fun observeReadingStreak_todayWithoutSessionYet_keepsYesterdaysStreak() = runTest {
        insertBook()
        // Three consecutive days ending yesterday; nothing logged yet for "today".
        insertSession(utcDay(99))
        insertSession(utcDay(98))
        insertSession(utcDay(97))

        val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100, hour = 8))).first()

        assertEquals(3, streak, "today having no session yet must not break a streak that ran through yesterday")
    }

    @Test
    fun observeReadingStreak_noSessions_isZero() = runTest {
        insertBook()

        val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100))).first()

        assertEquals(0, streak)
    }

    @Test
    fun observeReadingStreak_singleSessionToday_isOne() = runTest {
        insertBook()
        insertSession(utcDay(100))

        val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100, hour = 20))).first()

        assertEquals(1, streak)
    }

    @Test
    fun observeReadingStreak_neitherTodayNorYesterdayHasSession_isZero() = runTest {
        insertBook()
        insertSession(utcDay(90)) // long past, not adjacent to "today"

        val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100))).first()

        assertEquals(0, streak)
    }

    /**
     * Proves day-bucketing uses the injected [TimeZone] (local calendar date), not UTC.
     *
     * A session is logged at JST (`+09:00`) local time `2024-06-16T00:30` — 30 minutes after local
     * midnight, so its *local* calendar day is June 16th, but converting that same instant to UTC
     * lands on June 15th (`2024-06-15T15:30Z`), a full local calendar day earlier. A second,
     * unambiguous control session sits on June 14th in both zones (local noon, nowhere near a day
     * boundary).
     *
     * "Now" is fixed to the late-night session's own instant, so — bucketed correctly by JST —
     * `today` (June 16) has a session, June 15 is a genuine gap, and the streak is `1`. If day
     * bucketing instead (incorrectly) used the session's UTC calendar day, the late-night session
     * would be misplaced onto June 15th; today (still correctly resolved as June 16 via the
     * injected zone) would then have *no* session, falling back to the "yesterday" rule, where the
     * mis-bucketed June 15th session would then wrongly chain with the June 14th control session
     * into a streak of `2`. Asserting `1` (not `2`) is therefore proof local-date bucketing is
     * actually in effect, not an accident of the "today without a session" fallback.
     */
    @Test
    fun observeReadingStreak_lateEveningSession_bucketedByLocalDateNotUtc() = runTest {
        insertBook()
        val jst = TimeZone.of("+09:00")

        val controlDayInstant = LocalDateTime(2024, 6, 14, 12, 0).toInstant(jst)
        val lateNightInstant = LocalDateTime(2024, 6, 16, 0, 30).toInstant(jst)

        insertSession(controlDayInstant)
        insertSession(lateNightInstant)

        val streak = repo.observeReadingStreak(jst, fixedClock(lateNightInstant)).first()

        assertEquals(
            1,
            streak,
            "the late-night session must be bucketed onto its JST calendar day (June 16, matching " +
                "\"today\"), not its earlier UTC calendar day (June 15) -- a UTC-bucketed bug would " +
                "chain a mis-placed June 15 session with the June 14 control session into a streak of 2",
        )
    }

    // ---- thisWeekBounds / thisMonthBounds -----------------------------------------------------

    @Test
    fun thisWeekBounds_startsOnMonday() {
        // 2024-06-19 is a Wednesday; the containing ISO week runs Monday 2024-06-17 through
        // (exclusive) Monday 2024-06-24.
        val wednesday = LocalDateTime(2024, 6, 19, 15, 0).toInstant(TimeZone.UTC)

        val (from, to) = StatsRepository.thisWeekBounds(TimeZone.UTC, fixedClock(wednesday))

        assertEquals(LocalDate(2024, 6, 17).atStartOfDayIn(TimeZone.UTC), from)
        assertEquals(LocalDate(2024, 6, 24).atStartOfDayIn(TimeZone.UTC), to)
    }

    @Test
    fun thisWeekBounds_sundayYieldsWeekStartingPrecedingMonday() {
        // 2024-06-23 is a Sunday (day 7 of ISO week); the containing ISO week runs Monday
        // 2024-06-17 through (exclusive) Monday 2024-06-24.
        val sunday = LocalDateTime(2024, 6, 23, 15, 0).toInstant(TimeZone.UTC)

        val (from, to) = StatsRepository.thisWeekBounds(TimeZone.UTC, fixedClock(sunday))

        assertEquals(LocalDate(2024, 6, 17).atStartOfDayIn(TimeZone.UTC), from)
        assertEquals(LocalDate(2024, 6, 24).atStartOfDayIn(TimeZone.UTC), to)
    }

    @Test
    fun thisMonthBounds_spansFirstOfMonthThroughFirstOfNextMonth() {
        val juneDate = LocalDateTime(2024, 6, 19, 15, 0).toInstant(TimeZone.UTC)

        val (from, to) = StatsRepository.thisMonthBounds(TimeZone.UTC, fixedClock(juneDate))

        assertEquals(LocalDate(2024, 6, 1).atStartOfDayIn(TimeZone.UTC), from)
        assertEquals(LocalDate(2024, 7, 1).atStartOfDayIn(TimeZone.UTC), to)
    }

    // ---- books-finished stat (ROADMAP Task 6 Phase C) ----------------------------------------

    @Test
    fun observeBooksFinishedTotal_countsFinishedBooksAcrossAllTime() = runTest {
        insertBook()
        db.bookDetailsDao().insert(
            sampleBookDetails(mediaId = mediaId, status = ReadingStatus.FINISHED, finishedAt = instant(1_500)),
        )

        val total = repo.observeBooksFinishedTotal().first()

        assertEquals(1, total)
    }

    @Test
    fun observeBooksFinishedTotal_zeroWhenNoBookIsFinished() = runTest {
        insertBook()
        db.bookDetailsDao().insert(sampleBookDetails(mediaId = mediaId, status = ReadingStatus.READING))

        val total = repo.observeBooksFinishedTotal().first()

        assertEquals(0, total)
    }

    @Test
    fun observeBooksFinishedInRange_onlyCountsFinishesWithinBounds() = runTest {
        insertBook()
        db.bookDetailsDao().insert(
            sampleBookDetails(mediaId = mediaId, status = ReadingStatus.FINISHED, finishedAt = instant(1_500)),
        )

        val inRange = repo.observeBooksFinishedInRange(instant(1_000), instant(2_000)).first()
        val outOfRange = repo.observeBooksFinishedInRange(instant(2_000), instant(3_000)).first()

        assertEquals(1, inRange)
        assertEquals(0, outOfRange)
    }

    @Test
    fun thisMonthBounds_decemberYieldsUpperBoundInJanuaryOfFollowingYear() {
        val decemberDate = LocalDateTime(2024, 12, 15, 15, 0).toInstant(TimeZone.UTC)

        val (from, to) = StatsRepository.thisMonthBounds(TimeZone.UTC, fixedClock(decemberDate))

        assertEquals(LocalDate(2024, 12, 1).atStartOfDayIn(TimeZone.UTC), from)
        assertEquals(LocalDate(2025, 1, 1).atStartOfDayIn(TimeZone.UTC), to)
    }
}
