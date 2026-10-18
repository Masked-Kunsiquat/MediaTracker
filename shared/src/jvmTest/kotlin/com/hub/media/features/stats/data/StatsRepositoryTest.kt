package com.hub.media.features.stats.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.sampleReadingSession
import com.hub.media.core.database.testAppDatabase
import com.hub.media.features.settings.data.WeekStartDay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

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
 * Room-backed (real [AppDatabase]), so this class lives in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81), same
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
        db.readingSessionDao().insertIntoLatestRead(
            sampleReadingSession(
                mediaId = mediaId,
                timestampStart = timestampStart,
                durationSeconds = durationSeconds,
                deltaPages = deltaPages,
            ),
        )
    }

    private fun instant(epochSeconds: Long): Instant = Instant.fromEpochSeconds(epochSeconds)

    // ---- observeReadingStreak ----------------------------------------------------------------

    private fun fixedClock(instant: Instant): Clock =
        object : Clock {
            override fun now(): Instant = instant
        }

    /** One UTC calendar day's worth of milliseconds since epoch, offset by [hour] (UTC). */
    private fun utcDay(
        epochDay: Long,
        hour: Int = 12,
    ): Instant = Instant.fromEpochMilliseconds(epochDay * 86_400_000L + hour * 3_600_000L)

    @Test
    fun observeReadingStreak_threeConsecutiveDays_streakIsThree() =
        runTest {
            insertBook()
            insertSession(utcDay(100))
            insertSession(utcDay(99))
            insertSession(utcDay(98))

            val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100, hour = 18))).first()

            assertEquals(3, streak)
        }

    @Test
    fun observeReadingStreak_gapBreaksStreak() =
        runTest {
            insertBook()
            insertSession(utcDay(100))
            insertSession(utcDay(99))
            insertSession(utcDay(97)) // day 98 skipped

            val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100, hour = 18))).first()

            assertEquals(2, streak, "a gap day must stop the count immediately, not skip over it")
        }

    @Test
    fun observeReadingStreak_todayWithoutSessionYet_keepsYesterdaysStreak() =
        runTest {
            insertBook()
            // Three consecutive days ending yesterday; nothing logged yet for "today".
            insertSession(utcDay(99))
            insertSession(utcDay(98))
            insertSession(utcDay(97))

            val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100, hour = 8))).first()

            assertEquals(3, streak, "today having no session yet must not break a streak that ran through yesterday")
        }

    @Test
    fun observeReadingStreak_noSessions_isZero() =
        runTest {
            insertBook()

            val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100))).first()

            assertEquals(0, streak)
        }

    @Test
    fun observeReadingStreak_singleSessionToday_isOne() =
        runTest {
            insertBook()
            insertSession(utcDay(100))

            val streak = repo.observeReadingStreak(TimeZone.UTC, fixedClock(utcDay(100, hour = 20))).first()

            assertEquals(1, streak)
        }

    @Test
    fun observeReadingStreak_neitherTodayNorYesterdayHasSession_isZero() =
        runTest {
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
    fun observeReadingStreak_lateEveningSession_bucketedByLocalDateNotUtc() =
        runTest {
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
        // 2024-06-17 through (exclusive) Monday 2024-06-24. Uses the default weekStartDay
        // (MONDAY) explicitly, proving a caller that never passes it gets this exact behavior.
        val sunday = LocalDateTime(2024, 6, 23, 15, 0).toInstant(TimeZone.UTC)

        val (from, to) = StatsRepository.thisWeekBounds(TimeZone.UTC, fixedClock(sunday))

        assertEquals(LocalDate(2024, 6, 17).atStartOfDayIn(TimeZone.UTC), from)
        assertEquals(LocalDate(2024, 6, 24).atStartOfDayIn(TimeZone.UTC), to)
    }

    // ---- thisWeekBounds(weekStartDay = ...) (ROADMAP Task 7 Phase B) -------------------------

    @Test
    fun thisWeekBounds_explicitMonday_sundayDateMapsToPrecedingMonday() {
        // Same case as thisWeekBounds_sundayYieldsWeekStartingPrecedingMonday above, but passing
        // WeekStartDay.MONDAY explicitly rather than relying on the default -- the "awkward" case
        // the ROADMAP calls out: a Sunday date under a MONDAY start must map back to the
        // *preceding* Monday, not forward, and not to itself.
        val sunday = LocalDateTime(2024, 6, 23, 15, 0).toInstant(TimeZone.UTC)

        val (from, to) = StatsRepository.thisWeekBounds(TimeZone.UTC, fixedClock(sunday), WeekStartDay.MONDAY)

        assertEquals(LocalDate(2024, 6, 17).atStartOfDayIn(TimeZone.UTC), from)
        assertEquals(LocalDate(2024, 6, 24).atStartOfDayIn(TimeZone.UTC), to)
    }

    @Test
    fun thisWeekBounds_sundayStart_sundayDateMapsToItself() {
        // The other "awkward" case: under a SUNDAY start, that same Sunday IS its own week's
        // start -- zero days back, not six.
        val sunday = LocalDateTime(2024, 6, 23, 15, 0).toInstant(TimeZone.UTC)

        val (from, to) = StatsRepository.thisWeekBounds(TimeZone.UTC, fixedClock(sunday), WeekStartDay.SUNDAY)

        assertEquals(LocalDate(2024, 6, 23).atStartOfDayIn(TimeZone.UTC), from)
        assertEquals(LocalDate(2024, 6, 30).atStartOfDayIn(TimeZone.UTC), to)
    }

    @Test
    fun thisWeekBounds_sundayStart_wednesdayMapsToPrecedingSunday() {
        // 2024-06-19 is a Wednesday; under a SUNDAY start its containing week runs Sunday
        // 2024-06-16 through (exclusive) Sunday 2024-06-23 -- distinct from the MONDAY-start
        // week (06-17 through 06-24) thisMonthBounds/thisWeekBounds' other tests use for the same
        // Wednesday, proving weekStartDay actually changes which days are included.
        val wednesday = LocalDateTime(2024, 6, 19, 15, 0).toInstant(TimeZone.UTC)

        val (from, to) = StatsRepository.thisWeekBounds(TimeZone.UTC, fixedClock(wednesday), WeekStartDay.SUNDAY)

        assertEquals(LocalDate(2024, 6, 16).atStartOfDayIn(TimeZone.UTC), from)
        assertEquals(LocalDate(2024, 6, 23).atStartOfDayIn(TimeZone.UTC), to)
    }

    @Test
    fun thisWeekBounds_sundayStart_mondayMapsToPrecedingSundayOneDayBack() {
        // 2024-06-17 is a Monday, one day after the SUNDAY-start week begins -- a minimal
        // one-day-back case distinct from the Wednesday case above.
        val monday = LocalDateTime(2024, 6, 17, 9, 0).toInstant(TimeZone.UTC)

        val (from, to) = StatsRepository.thisWeekBounds(TimeZone.UTC, fixedClock(monday), WeekStartDay.SUNDAY)

        assertEquals(LocalDate(2024, 6, 16).atStartOfDayIn(TimeZone.UTC), from)
        assertEquals(LocalDate(2024, 6, 23).atStartOfDayIn(TimeZone.UTC), to)
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
    fun thisMonthBounds_decemberYieldsUpperBoundInJanuaryOfFollowingYear() {
        val decemberDate = LocalDateTime(2024, 12, 15, 15, 0).toInstant(TimeZone.UTC)

        val (from, to) = StatsRepository.thisMonthBounds(TimeZone.UTC, fixedClock(decemberDate))

        assertEquals(LocalDate(2024, 12, 1).atStartOfDayIn(TimeZone.UTC), from)
        assertEquals(LocalDate(2025, 1, 1).atStartOfDayIn(TimeZone.UTC), to)
    }

    // --- observePeriodStats (#91) ---------------------------------------------------------------

    private val june19: Clock =
        object : Clock {
            override fun now(): Instant = LocalDateTime(2024, 6, 19, 15, 0).toInstant(TimeZone.UTC)
        }

    private suspend fun periodStats(period: StatsPeriod) =
        repo.observePeriodStats(period, TimeZone.UTC, june19, WeekStartDay.MONDAY).first()

    @Test
    fun observePeriodStats_readsThePeriodsBounds() =
        runTest {
            insertBook()
            insertSession(LocalDateTime(2024, 6, 18, 9, 0).toInstant(TimeZone.UTC), durationSeconds = 600)
            insertSession(LocalDateTime(2024, 3, 2, 9, 0).toInstant(TimeZone.UTC), durationSeconds = 900)

            assertEquals(600L, periodStats(StatsPeriod.WEEK).timeReadSeconds)
            assertEquals(1500L, periodStats(StatsPeriod.YEAR).timeReadSeconds)
            assertEquals(1, periodStats(StatsPeriod.LAST_30_DAYS).sessionCount)
        }

    @Test
    fun observePeriodStats_anUndatedFinish_isInAllTime_notInYear() =
        runTest {
            insertBook()
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = mediaId, status = ReadingStatus.FINISHED, finishedAt = null),
            )

            assertEquals(1, periodStats(StatsPeriod.ALL_TIME).booksFinished)
            assertEquals(0, periodStats(StatsPeriod.YEAR).booksFinished)
        }

    // --- Finishes by month (#219) ------------------------------------------------------------------

    @Test
    fun finishesByMonth_areBucketedInTheLocalZone_notUtc() =
        runTest {
            val newYork = TimeZone.of("America/New_York")
            insertBook()
            // 23:30 on 31 May in New York is already 1 June in UTC; it must count as May.
            db.bookDetailsDao().insert(
                sampleBookDetails(
                    mediaId = mediaId,
                    status = ReadingStatus.FINISHED,
                    finishedAt = LocalDateTime(2024, 5, 31, 23, 30).toInstant(newYork),
                ),
            )
            db.mediaItemDao().insert(sampleMediaItem(id = "media-2"))
            db.bookDetailsDao().insert(
                sampleBookDetails(
                    mediaId = "media-2",
                    status = ReadingStatus.FINISHED,
                    finishedAt = LocalDateTime(2024, 1, 2, 10, 0).toInstant(newYork),
                ),
            )
            val year = LocalDate(2024, 1, 1).atStartOfDayIn(newYork) to LocalDate(2025, 1, 1).atStartOfDayIn(newYork)

            val months = repo.observeFinishesByMonth(year, newYork).first()

            assertEquals(12, months.size)
            assertEquals(1, months[0], "January")
            assertEquals(1, months[4], "May")
            assertEquals(0, months[5], "June must not get the 31 May finish")
        }
}
