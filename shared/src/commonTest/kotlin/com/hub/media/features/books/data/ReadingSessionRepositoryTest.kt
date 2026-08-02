package com.hub.media.features.books.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ReadingSessionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ReadingSessionRepository
    private lateinit var mediaId: String

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = ReadingSessionRepository(db)

        // Create a book to associate sessions with
        runTest {
            mediaId = newId()
            val mediaItem = sampleMediaItem(id = mediaId, type = MediaType.BOOK)
            db.mediaItemDao().insert(mediaItem)
        }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun logSession_happyPath_injectsValidSession() = runTest {
        val timestampStart = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val timestampEnd = Instant.fromEpochMilliseconds(1_700_000_600_000)

        val result = repo.logSession(
            mediaId = mediaId,
            timestampStart = timestampStart,
            timestampEnd = timestampEnd,
            durationSeconds = 600,
            startUnit = 10.0,
            endUnit = 25.0,
            deltaPages = 15,
            notes = "Good progress",
        )

        assertIs<Resource.Success<String>>(result)
        val sessionId = result.data

        // Verify session exists
        val session = db.readingSessionDao().getById(sessionId)
        assertTrue(session != null)
        assertTrue(session.mediaId == mediaId)
        assertTrue(session.durationSeconds == 600L)
        assertTrue(session.deltaPages == 15)
        assertTrue(session.notes == "Good progress")
    }

    @Test
    fun logSession_zeroSeconds_succeeds() = runTest {
        val now = Clock.System.now()

        val result = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now,  // Same timestamp = 0 second session
            durationSeconds = 0,
            startUnit = 10.0,
            endUnit = 10.0,
            deltaPages = 0,
        )

        assertIs<Resource.Success<String>>(result)
        val session = db.readingSessionDao().getById(result.data)
        assertTrue(session?.durationSeconds == 0L)
    }

    @Test
    fun logSession_nullDuration_persistsWithNullDuration() = runTest {
        // Schema v2 (ROADMAP Task 5 pre-phase): null durationSeconds means "unknown", distinct
        // from a legitimate 0-second session (see logSession_zeroSeconds_succeeds above and
        // ReadingSessionEntity's KDoc). A backlogged manual entry is the motivating case.
        val now = Clock.System.now()

        val result = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now,
            durationSeconds = null,
            startUnit = 10.0,
            endUnit = 15.0,
            deltaPages = 5,
        )

        assertIs<Resource.Success<String>>(result)
        val session = db.readingSessionDao().getById(result.data)
        assertTrue(session != null)
        assertTrue(session.durationSeconds == null, "null duration must be persisted as null, not coerced to 0")
    }

    @Test
    fun logSession_negativePages_succeeds() = runTest {
        // Per AGENTS.md §7: edge cases are allowed (0-page and negative progress are valid)
        val now = Clock.System.now()

        val result = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now.plus(kotlin.time.Duration.parse("1h")),
            durationSeconds = 3600,
            startUnit = 100.0,
            endUnit = 90.0,  // Went backwards (reread)
            deltaPages = -10,
        )

        assertIs<Resource.Success<String>>(result)
    }

    @Test
    fun logSession_invalidEndBeforeStart_returnsError() = runTest {
        val now = Clock.System.now()
        val earlier = now.minus(kotlin.time.Duration.parse("1h"))

        val result = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = earlier,  // End before start
            durationSeconds = -3600,
            startUnit = 100.0,
            endUnit = 90.0,
        )

        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("timestampEnd"))
    }

    @Test
    fun logSession_negativeDuration_returnsError() = runTest {
        val now = Clock.System.now()

        val result = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now.plus(kotlin.time.Duration.parse("1h")),
            durationSeconds = -1,  // Negative duration
            startUnit = 10.0,
            endUnit = 25.0,
        )

        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("durationSeconds"))
    }

    @Test
    fun observeSessionsForMedia_emitsAfterInsert() = runTest {
        val now = Clock.System.now()

        val initialSessions = repo.observeSessionsForMedia(mediaId).first()
        assertTrue(initialSessions.isEmpty())

        repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now.plus(kotlin.time.Duration.parse("1h")),
            durationSeconds = 3600,
            startUnit = 0.0,
            endUnit = 50.0,
        )

        val sessionsAfterInsert = repo.observeSessionsForMedia(mediaId).first { it.isNotEmpty() }
        assertTrue(sessionsAfterInsert.size == 1)
    }

    @Test
    fun observeSessionsForMedia_differentiatesByMediaId() = runTest {
        val mediaId2 = newId()
        val mediaItem2 = sampleMediaItem(id = mediaId2, type = MediaType.BOOK)
        db.mediaItemDao().insert(mediaItem2)

        val now = Clock.System.now()

        // Insert sessions for both books
        repo.logSession(mediaId, now, now.plus(kotlin.time.Duration.parse("1h")), 3600, 0.0, 50.0)
        repo.logSession(mediaId2, now, now.plus(kotlin.time.Duration.parse("2h")), 7200, 0.0, 100.0)

        val sessionsForBook1 = repo.observeSessionsForMedia(mediaId).first()
        val sessionsForBook2 = repo.observeSessionsForMedia(mediaId2).first()

        assertTrue(sessionsForBook1.size == 1)
        assertTrue(sessionsForBook2.size == 1)
        assertTrue(sessionsForBook1[0].mediaId == mediaId)
        assertTrue(sessionsForBook2[0].mediaId == mediaId2)
    }

    @Test
    fun deleteSession_removesSession() = runTest {
        val now = Clock.System.now()

        val addResult = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now.plus(kotlin.time.Duration.parse("1h")),
            durationSeconds = 3600,
            startUnit = 0.0,
            endUnit = 50.0,
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data

        val deleteResult = repo.deleteSession(sessionId)
        assertIs<Resource.Success<Unit>>(deleteResult)

        val session = db.readingSessionDao().getById(sessionId)
        assertTrue(session == null)
    }
}
