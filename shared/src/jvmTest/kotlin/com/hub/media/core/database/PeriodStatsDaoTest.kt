package com.hub.media.core.database

import com.hub.media.core.database.dao.PeriodStatsRow
import com.hub.media.core.database.entities.ReadingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * [com.hub.media.core.database.dao.StatsDao.observePeriodStats] (#91): the one query a stats period
 * now reads. Carries over every case the four per-field queries were held to -- null versus zero,
 * half-open bounds, null-duration sessions counted but not timed, undated finishes -- because this
 * query replaces them and none of those rules may be lost on the way.
 */
class PeriodStatsDaoTest {
    private lateinit var db: AppDatabase
    private val mediaId = "media-1"

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun insertBook() {
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId))
    }

    private fun instant(epochSeconds: Long): Instant = Instant.fromEpochSeconds(epochSeconds)

    private suspend fun session(
        at: Long,
        durationSeconds: Long? = 300,
        deltaPages: Int? = 10,
    ) {
        db.readingSessionDao().insert(
            sampleReadingSession(
                mediaId = mediaId,
                timestampStart = instant(at),
                durationSeconds = durationSeconds,
                deltaPages = deltaPages,
            ),
        )
    }

    private suspend fun stats(
        from: Long?,
        to: Long?,
    ): PeriodStatsRow =
        db
            .statsDao()
            .observePeriodStats(from?.let(::instant), to?.let(::instant), ReadingStatus.FINISHED)
            .first()

    @Test
    fun anEmptyRange_isUnknownTimeAndPages_andZeroCounts() =
        runTest {
            assertEquals(PeriodStatsRow(null, 0, null, 0, 0, null, null, null), stats(1_000, 2_000))
        }

    @Test
    fun sessions_areSummedAndCounted_withNullsExcludedFromSums_notTreatedAsZero() =
        runTest {
            insertBook()
            session(1_100, durationSeconds = 300, deltaPages = 10)
            session(1_200, durationSeconds = null, deltaPages = null)
            session(1_300, durationSeconds = 500, deltaPages = 5)

            assertEquals(PeriodStatsRow(800L, 3, 15, 0, 2, 500L, 800L, 15), stats(1_000, 2_000))
        }

    @Test
    fun whenEverySessionLacksADuration_timeIsUnknown_butTheyStillCount() =
        runTest {
            insertBook()
            session(1_100, durationSeconds = null, deltaPages = null)
            session(1_200, durationSeconds = null, deltaPages = null)

            assertEquals(PeriodStatsRow(null, 2, null, 0, 0, null, null, null), stats(1_000, 2_000))
        }

    @Test
    fun bounds_areHalfOpen_fromIncluded_toExcluded() =
        runTest {
            insertBook()
            session(1_000)
            session(2_000)

            assertEquals(1, stats(1_000, 2_000).sessionCount)
        }

    @Test
    fun booksFinished_countsFinishedStatusWithinTheRange_halfOpen() =
        runTest {
            insertBook()
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = mediaId, status = ReadingStatus.FINISHED, finishedAt = instant(1_000)),
            )
            db.mediaItemDao().insert(sampleMediaItem(id = "media-2"))
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = "media-2", status = ReadingStatus.FINISHED, finishedAt = instant(2_000)),
            )
            db.mediaItemDao().insert(sampleMediaItem(id = "media-3"))
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = "media-3", status = ReadingStatus.READING, finishedAt = instant(1_500)),
            )

            assertEquals(1, stats(1_000, 2_000).booksFinished)
        }

    @Test
    fun anUndatedFinish_countsInAllTime_butInNoDatedRange() =
        runTest {
            insertBook()
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = mediaId, status = ReadingStatus.FINISHED, finishedAt = null),
            )

            // A book still being read is not a finish, in All time or anywhere else.
            db.mediaItemDao().insert(sampleMediaItem(id = "media-2"))
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = "media-2", status = ReadingStatus.READING, finishedAt = null),
            )

            assertEquals(1, stats(null, null).booksFinished)
            assertEquals(0, stats(0, 999_999_999).booksFinished)
        }

    @Test
    fun allTime_isUnbounded_atBothEnds() =
        runTest {
            insertBook()
            session(1, durationSeconds = 60, deltaPages = 1)
            session(999_999_999, durationSeconds = 60, deltaPages = 1)

            assertEquals(PeriodStatsRow(120L, 2, 2, 0, 2, 60L, 120L, 2), stats(null, null))
        }

    @Test
    fun anOpenStatsFlow_updatesWhenABookIsFinished() =
        runTest {
            insertBook()
            val flow = db.statsDao().observePeriodStats(null, null, ReadingStatus.FINISHED)
            assertEquals(0, flow.first().booksFinished)

            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = mediaId, status = ReadingStatus.FINISHED, finishedAt = instant(1_000)),
            )

            // The query observes book_details as well as reading_sessions.
            assertEquals(1, flow.first { it.booksFinished == 1 }.booksFinished)
        }

    // --- Speed and session lengths (#219) -----------------------------------------------------

    @Test
    fun speed_countsOnlySessionsWithBothAKnownDurationAndKnownPages() =
        runTest {
            insertBook()
            session(1_100, durationSeconds = 1_800, deltaPages = 30) // counts
            session(1_200, durationSeconds = 600, deltaPages = null) // time but no pages: excluded
            session(1_300, durationSeconds = null, deltaPages = 40) // pages but no time: excluded
            session(1_400, durationSeconds = 0, deltaPages = 5) // zero time: excluded, never a division by zero

            val row = stats(1_000, 2_000)

            assertEquals(1_800L, row.speedSeconds)
            assertEquals(30, row.speedPages)
        }

    /**
     * A session can move backwards (a corrected page, a bookmark moved back to re-read): its pages
     * are negative. It says nothing about reading speed, and counting it dragged speed down -- alone
     * in a week, to "-100 pages/hour". A session with no forward progress is left out the same way.
     */
    @Test
    fun speed_ignoresSessionsThatMovedBackwardsOrNowhere() =
        runTest {
            insertBook()
            session(1_100, durationSeconds = 3_600, deltaPages = 60) // counts
            session(1_200, durationSeconds = 1_800, deltaPages = -50) // backwards: excluded
            session(1_300, durationSeconds = 900, deltaPages = 0) // nowhere: excluded

            val row = stats(1_000, 2_000)

            assertEquals(3_600L, row.speedSeconds)
            assertEquals(60, row.speedPages)
            // Positive control: all three sessions are in the period and timed.
            assertEquals(3, row.timedSessionCount)
        }

    @Test
    fun speed_isUnknown_whenNoSessionHasBoth() =
        runTest {
            insertBook()
            session(1_100, durationSeconds = 600, deltaPages = null)

            val row = stats(1_000, 2_000)

            assertEquals(null, row.speedSeconds)
            assertEquals(null, row.speedPages)
            // Positive control: the session is there, and timed.
            assertEquals(1, row.timedSessionCount)
        }

    @Test
    fun longestSession_andTimedCount_ignoreSessionsWithoutADuration() =
        runTest {
            insertBook()
            session(1_100, durationSeconds = 900)
            session(1_200, durationSeconds = 2_400)
            session(1_300, durationSeconds = null)

            val row = stats(1_000, 2_000)

            assertEquals(2_400L, row.longestSessionSeconds)
            assertEquals(2, row.timedSessionCount)
            assertEquals(3, row.sessionCount)
        }

    // --- Finish dates for the month chart (#219) -------------------------------------------------

    @Test
    fun finishedAtInRange_returnsOnlyFinishedBooks_withinTheRange() =
        runTest {
            insertBook()
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = mediaId, status = ReadingStatus.FINISHED, finishedAt = instant(1_500)),
            )
            db.mediaItemDao().insert(sampleMediaItem(id = "media-2"))
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = "media-2", status = ReadingStatus.FINISHED, finishedAt = instant(2_000)),
            )
            db.mediaItemDao().insert(sampleMediaItem(id = "media-3"))
            db.bookDetailsDao().insert(
                sampleBookDetails(mediaId = "media-3", status = ReadingStatus.READING, finishedAt = instant(1_600)),
            )

            val dates =
                db.statsDao().observeFinishedAtInRange(instant(1_000), instant(2_000), ReadingStatus.FINISHED).first()

            assertEquals(listOf(instant(1_500)), dates)
        }
}
