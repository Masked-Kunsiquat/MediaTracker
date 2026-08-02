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
    fun updateSession_happyPath_persistsAllFields() = runTest {
        val timestampStart = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val timestampEnd = Instant.fromEpochMilliseconds(1_700_000_600_000)
        val addResult = repo.logSession(
            mediaId = mediaId,
            timestampStart = timestampStart,
            timestampEnd = timestampEnd,
            durationSeconds = 600,
            startUnit = 10.0,
            endUnit = 25.0,
            deltaPages = 15,
            notes = "Original notes",
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data

        val newStart = Instant.fromEpochMilliseconds(1_700_100_000_000)
        val newEnd = Instant.fromEpochMilliseconds(1_700_101_800_000)
        val updateResult = repo.updateSession(
            sessionId = sessionId,
            timestampStart = newStart,
            timestampEnd = newEnd,
            durationSeconds = 1_800,
            startUnit = 25.0,
            endUnit = 60.0,
            deltaPages = 35,
            notes = "Edited notes",
        )

        assertIs<Resource.Success<Unit>>(updateResult)
        val session = db.readingSessionDao().getById(sessionId)
        assertTrue(session != null)
        assertTrue(session.mediaId == mediaId, "mediaId must not change on edit")
        assertTrue(session.timestampStart == newStart)
        assertTrue(session.timestampEnd == newEnd)
        assertTrue(session.durationSeconds == 1_800L)
        assertTrue(session.startUnit == 25.0)
        assertTrue(session.endUnit == 60.0)
        assertTrue(session.deltaPages == 35)
        assertTrue(session.notes == "Edited notes")
    }

    @Test
    fun updateSession_nullDuration_persistsWithNullDuration() = runTest {
        val now = Clock.System.now()
        val addResult = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now,
            durationSeconds = 600,
            startUnit = 10.0,
            endUnit = 25.0,
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data

        val updateResult = repo.updateSession(
            sessionId = sessionId,
            timestampStart = now,
            timestampEnd = now,
            durationSeconds = null,
            startUnit = 10.0,
            endUnit = 25.0,
        )

        assertIs<Resource.Success<Unit>>(updateResult)
        val session = db.readingSessionDao().getById(sessionId)
        assertTrue(session?.durationSeconds == null)
    }

    @Test
    fun updateSession_invalidEndBeforeStart_returnsErrorAndLeavesRowUnchanged() = runTest {
        val now = Clock.System.now()
        val addResult = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now.plus(kotlin.time.Duration.parse("1h")),
            durationSeconds = 3600,
            startUnit = 10.0,
            endUnit = 25.0,
            notes = "Untouched",
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data
        val before = db.readingSessionDao().getById(sessionId)

        val updateResult = repo.updateSession(
            sessionId = sessionId,
            timestampStart = now,
            timestampEnd = now.minus(kotlin.time.Duration.parse("1h")), // end before start
            durationSeconds = 3600,
            startUnit = 50.0,
            endUnit = 75.0,
            notes = "Should not be saved",
        )

        assertIs<Resource.Error>(updateResult)
        assertTrue(updateResult.message.contains("timestampEnd"))
        val after = db.readingSessionDao().getById(sessionId)
        assertTrue(after == before, "a rejected update must leave the existing row completely unchanged")
    }

    @Test
    fun updateSession_negativeDuration_returnsErrorAndLeavesRowUnchanged() = runTest {
        val now = Clock.System.now()
        val addResult = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now.plus(kotlin.time.Duration.parse("1h")),
            durationSeconds = 3600,
            startUnit = 10.0,
            endUnit = 25.0,
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data
        val before = db.readingSessionDao().getById(sessionId)

        val updateResult = repo.updateSession(
            sessionId = sessionId,
            timestampStart = now,
            timestampEnd = now.plus(kotlin.time.Duration.parse("1h")),
            durationSeconds = -1,
            startUnit = 10.0,
            endUnit = 25.0,
        )

        assertIs<Resource.Error>(updateResult)
        assertTrue(updateResult.message.contains("durationSeconds"))
        val after = db.readingSessionDao().getById(sessionId)
        assertTrue(after == before, "a rejected update must leave the existing row completely unchanged")
    }

    @Test
    fun readingSessionDao_update_returnsZeroRowsAffectedWhenRowVanishedMidFlight() = runTest {
        // Finding #4: updateSession's getById-then-update shape has a window where the row can be
        // deleted by another writer between the two calls. ReadingSessionDao.update now returns
        // the affected-row count (0 vs 1) specifically so that race is detectable rather than
        // silently no-op'ing. This directly exercises that DAO-level contract: simulate the row
        // vanishing after a caller already read it (as updateSession's `existing` would have), then
        // confirm the subsequent update reports 0 rows affected instead of silently "succeeding."
        val now = Clock.System.now()
        val addResult = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now,
            durationSeconds = 0,
            startUnit = 0.0,
            endUnit = 0.0,
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data
        val existing = db.readingSessionDao().getById(sessionId)
        assertTrue(existing != null)

        // Simulate a concurrent delete winning the race between the read above and the write below.
        db.readingSessionDao().deleteById(sessionId)

        val rowsAffected = db.readingSessionDao().update(existing)
        assertTrue(rowsAffected == 0, "update on a vanished row must report 0 affected rows, not silently succeed")
    }

    @Test
    fun updateSession_happyPath_updateReportsOneRowAffected() = runTest {
        // Complements the vanished-row case above: a genuine update against a still-existing row
        // must report exactly one row affected, confirming the DAO's Int return isn't always 0/stub.
        val now = Clock.System.now()
        val addResult = repo.logSession(
            mediaId = mediaId,
            timestampStart = now,
            timestampEnd = now,
            durationSeconds = 0,
            startUnit = 0.0,
            endUnit = 0.0,
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data
        val existing = db.readingSessionDao().getById(sessionId)
        assertTrue(existing != null)

        val rowsAffected = db.readingSessionDao().update(existing.copy(notes = "Edited directly"))
        assertTrue(rowsAffected == 1)
    }

    @Test
    fun updateSession_nonexistentId_returnsError() = runTest {
        val now = Clock.System.now()

        val updateResult = repo.updateSession(
            sessionId = newId(),
            timestampStart = now,
            timestampEnd = now,
            durationSeconds = 0,
            startUnit = 0.0,
            endUnit = 0.0,
        )

        assertIs<Resource.Error>(updateResult)
        assertTrue(updateResult.message.contains("not found"))
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
