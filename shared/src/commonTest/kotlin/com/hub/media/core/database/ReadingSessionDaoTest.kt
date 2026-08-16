package com.hub.media.core.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReadingSessionDaoTest {
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndObserveSessionsForMedia_returnsSession() =
        runTest {
            val media = sampleMediaItem()
            db.mediaItemDao().insert(media)
            val session = sampleReadingSession(mediaId = media.id)

            db.readingSessionDao().insert(session)

            assertEquals(listOf(session), db.readingSessionDao().observeSessionsForMedia(media.id).first())
        }

    /**
     * Edge case required by AGENTS.md §7: a 0-second session on a 0-page book (e.g. a DNF
     * logged the instant it was opened) must round-trip exactly rather than being rejected
     * or coerced to null/NaN.
     */
    @Test
    fun zeroDurationZeroPageSession_isStoredAndReadBackExactly() =
        runTest {
            val media = sampleMediaItem()
            db.mediaItemDao().insert(media)
            db.bookDetailsDao().insert(sampleBookDetails(mediaId = media.id, totalPages = 0))

            val instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val session =
                sampleReadingSession(
                    mediaId = media.id,
                    timestampStart = instant,
                    timestampEnd = instant,
                    durationSeconds = 0,
                    startUnit = 0.0,
                    endUnit = 0.0,
                    deltaPages = 0,
                )

            db.readingSessionDao().insert(session)

            assertEquals(session, db.readingSessionDao().getById(session.id))
        }

    @Test
    fun cascadeDelete_removesSessionsWhenMediaItemDeleted() =
        runTest {
            val media = sampleMediaItem()
            db.mediaItemDao().insert(media)
            db.readingSessionDao().insert(sampleReadingSession(mediaId = media.id))

            db.mediaItemDao().delete(media)

            assertTrue(
                db
                    .readingSessionDao()
                    .observeSessionsForMedia(media.id)
                    .first()
                    .isEmpty(),
            )
        }
}
