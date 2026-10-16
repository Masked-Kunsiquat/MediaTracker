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
            assertEquals(PeriodStatsRow(null, 0, null, 0), stats(1_000, 2_000))
        }

    @Test
    fun sessions_areSummedAndCounted_withNullsExcludedFromSums_notTreatedAsZero() =
        runTest {
            insertBook()
            session(1_100, durationSeconds = 300, deltaPages = 10)
            session(1_200, durationSeconds = null, deltaPages = null)
            session(1_300, durationSeconds = 500, deltaPages = 5)

            assertEquals(PeriodStatsRow(800L, 3, 15, 0), stats(1_000, 2_000))
        }

    @Test
    fun whenEverySessionLacksADuration_timeIsUnknown_butTheyStillCount() =
        runTest {
            insertBook()
            session(1_100, durationSeconds = null, deltaPages = null)
            session(1_200, durationSeconds = null, deltaPages = null)

            assertEquals(PeriodStatsRow(null, 2, null, 0), stats(1_000, 2_000))
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

            assertEquals(1, stats(null, null).booksFinished)
            assertEquals(0, stats(0, 999_999_999).booksFinished)
        }

    @Test
    fun allTime_isUnbounded_atBothEnds() =
        runTest {
            insertBook()
            session(1, durationSeconds = 60, deltaPages = 1)
            session(999_999_999, durationSeconds = 60, deltaPages = 1)

            assertEquals(PeriodStatsRow(120L, 2, 2, 0), stats(null, null))
        }
}
