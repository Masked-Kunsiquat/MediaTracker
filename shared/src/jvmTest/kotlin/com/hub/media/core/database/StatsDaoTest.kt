package com.hub.media.core.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

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
    fun observeSessionStartTimestampsInRange_returnsRawStartTimestamps() =
        runTest {
            insertBook()
            val t1 = instant(1_100)
            val t2 = instant(1_200)
            db.readingSessionDao().insertIntoLatestRead(sampleReadingSession(mediaId = mediaId, timestampStart = t1))
            db.readingSessionDao().insertIntoLatestRead(sampleReadingSession(mediaId = mediaId, timestampStart = t2))

            val timestamps = db.statsDao().observeSessionStartTimestampsInRange(instant(1_000), instant(2_000)).first()

            assertEquals(setOf(t1, t2), timestamps.toSet())
        }

    // ---- books-finished stat (ROADMAP Task 6 Phase C) ----------------------------------------
}
