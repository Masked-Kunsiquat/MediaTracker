package com.hub.media.core.database

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Verifies [com.hub.media.core.database.dao.StatsDao]'s SQL semantics directly against a real
 * in-memory [AppDatabase] — same builder as [ReadingSessionDaoTest] — since these are handwritten
 * `@Query` strings (no Room-generated boilerplate) whose SQLite `SUM()`/range-boundary behavior is
 * exactly what needs proving. See the DAO's KDoc for the documented "[from, to)" range and
 * null-vs-0 rules this class asserts.
 */
class StatsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var mediaId: String

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        mediaId = "media-1"
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun insertBook() {
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId))
    }

    private fun instant(epochSeconds: Long): Instant = Instant.fromEpochSeconds(epochSeconds)

    @Test
    fun observeTotalKnownDurationInRange_sumsOnlyNonNullDurations() = runTest {
        insertBook()
        val from = instant(1_000)
        val to = instant(2_000)
        db.readingSessionDao().insert(
            sampleReadingSession(
                mediaId = mediaId,
                timestampStart = instant(1_100),
                durationSeconds = 300,
            ),
        )
        db.readingSessionDao().insert(
            sampleReadingSession(
                mediaId = mediaId,
                timestampStart = instant(1_200),
                durationSeconds = null, // must not contribute, and must not be treated as 0 either
            ),
        )
        db.readingSessionDao().insert(
            sampleReadingSession(
                mediaId = mediaId,
                timestampStart = instant(1_300),
                durationSeconds = 500,
            ),
        )

        val total = db.statsDao().observeTotalKnownDurationInRange(from, to).first()

        assertEquals(800L, total, "null-duration session is excluded from sum, not treated as 0")
    }

    @Test
    fun observeTotalKnownDurationInRange_emptyRange_isNull() = runTest {
        insertBook()
        // No sessions at all in [from, to).
        val total = db.statsDao().observeTotalKnownDurationInRange(instant(1_000), instant(2_000)).first()

        assertNull(total, "SUM over an empty match set must surface as null, never coerced to 0")
    }

    @Test
    fun observeTotalKnownDurationInRange_allSessionsNullDuration_isNull() = runTest {
        insertBook()
        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = instant(1_100), durationSeconds = null),
        )

        val total = db.statsDao().observeTotalKnownDurationInRange(instant(1_000), instant(2_000)).first()

        assertNull(total, "when every matching session has a null duration, the sum is null, not 0")
    }

    @Test
    fun observeSessionCountInRange_countsAllSessionsIncludingNullDuration() = runTest {
        insertBook()
        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = instant(1_100), durationSeconds = 300),
        )
        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = instant(1_200), durationSeconds = null),
        )

        val count = db.statsDao().observeSessionCountInRange(instant(1_000), instant(2_000)).first()

        assertEquals(2, count, "session count must include null-duration sessions")
    }

    @Test
    fun observeSessionCountInRange_emptyRange_isZero() = runTest {
        insertBook()

        val count = db.statsDao().observeSessionCountInRange(instant(1_000), instant(2_000)).first()

        assertEquals(0, count, "an empty range is a genuine 0 count, not null")
    }

    @Test
    fun observePagesReadInRange_sumsOnlyNonNullDeltaPages() = runTest {
        insertBook()
        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = instant(1_100), deltaPages = 10),
        )
        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = instant(1_200), deltaPages = null),
        )
        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = instant(1_300), deltaPages = 5),
        )

        val pages = db.statsDao().observePagesReadInRange(instant(1_000), instant(2_000)).first()

        assertEquals(15, pages)
    }

    @Test
    fun observePagesReadInRange_emptyRange_isNull() = runTest {
        insertBook()

        val pages = db.statsDao().observePagesReadInRange(instant(1_000), instant(2_000)).first()

        assertNull(pages)
    }

    @Test
    fun rangeBoundary_fromIsInclusive() = runTest {
        insertBook()
        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = instant(1_000), durationSeconds = 42),
        )

        val count = db.statsDao().observeSessionCountInRange(instant(1_000), instant(2_000)).first()

        assertEquals(1, count, "a session starting exactly at `from` must be included")
    }

    @Test
    fun rangeBoundary_toIsExclusive() = runTest {
        insertBook()
        db.readingSessionDao().insert(
            sampleReadingSession(mediaId = mediaId, timestampStart = instant(2_000), durationSeconds = 42),
        )

        val count = db.statsDao().observeSessionCountInRange(instant(1_000), instant(2_000)).first()

        assertEquals(0, count, "a session starting exactly at `to` must be excluded")
    }

    @Test
    fun observeSessionStartTimestampsInRange_returnsRawStartTimestamps() = runTest {
        insertBook()
        val t1 = instant(1_100)
        val t2 = instant(1_200)
        db.readingSessionDao().insert(sampleReadingSession(mediaId = mediaId, timestampStart = t1))
        db.readingSessionDao().insert(sampleReadingSession(mediaId = mediaId, timestampStart = t2))

        val timestamps = db.statsDao().observeSessionStartTimestampsInRange(instant(1_000), instant(2_000)).first()

        assertEquals(setOf(t1, t2), timestamps.toSet())
    }
}
