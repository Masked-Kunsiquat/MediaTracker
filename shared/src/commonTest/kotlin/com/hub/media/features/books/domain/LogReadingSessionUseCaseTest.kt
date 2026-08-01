package com.hub.media.features.books.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.books.timer.ReadingTimerResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Tests [LogReadingSessionUseCase] against a real (in-memory) [AppDatabase] via
 * [ReadingSessionRepository], following the same in-memory-Room pattern as
 * `ReadingSessionRepositoryTest` and `AddBookByIsbnUseCaseTest`. Room-touching, so this lives in
 * `com.hub.media.features.books.domain` and is excluded from the Android unit-test variant by the
 * package-wide filter in shared/build.gradle.kts — `:shared:jvmTest` is the authoritative gate.
 */
class LogReadingSessionUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ReadingSessionRepository
    private lateinit var useCase: LogReadingSessionUseCase
    private lateinit var mediaId: String

    @BeforeTest
    fun setUp() = runTest {
        db = testAppDatabase()
        repository = ReadingSessionRepository(db)
        useCase = LogReadingSessionUseCase(repository)

        mediaId = newId()
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId, type = MediaType.BOOK))
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private suspend fun noSessionsPersisted(): Boolean =
        repository.observeSessionsForMedia(mediaId).first().isEmpty()

    @Test
    fun execute_timerResultOverload_happyPath_persistsSession() = runTest {
        val timestampEnd = start.plus(Duration.parse("25m"))
        val timerResult = ReadingTimerResult(
            timestampStart = start,
            timestampEnd = timestampEnd,
            durationSeconds = 1_500,
        )

        val result = useCase.execute(
            mediaId = mediaId,
            timerResult = timerResult,
            startUnit = 10.0,
            endUnit = 42.0,
            deltaPages = 32,
            notes = "Chapter 3",
        )

        assertIs<Resource.Success<String>>(result)
        val session = db.readingSessionDao().getById(result.data)
        assertTrue(session != null)
        assertTrue(session.mediaId == mediaId)
        assertTrue(session.timestampStart == start)
        assertTrue(session.timestampEnd == timestampEnd)
        assertTrue(session.durationSeconds == 1_500L)
        assertTrue(session.startUnit == 10.0)
        assertTrue(session.endUnit == 42.0)
        assertTrue(session.deltaPages == 32)
        assertTrue(session.notes == "Chapter 3")
    }

    @Test
    fun execute_explicitBoundsOverload_happyPath_persistsSession() = runTest {
        val timestampEnd = start.plus(Duration.parse("1h"))

        val result = useCase.execute(
            mediaId = mediaId,
            timestampStart = start,
            timestampEnd = timestampEnd,
            durationSeconds = 3_600,
            startUnit = 0.0,
            endUnit = 50.5,
        )

        assertIs<Resource.Success<String>>(result)
        val session = db.readingSessionDao().getById(result.data)
        assertTrue(session?.endUnit == 50.5)
        assertTrue(session?.deltaPages == null)
        assertTrue(session?.notes == null)
    }

    @Test
    fun execute_explicitBoundsOverload_nullDuration_persistsWithNullDuration() = runTest {
        // Schema v2 (ROADMAP Task 5 pre-phase): the explicit-bounds overload (manual entry) may
        // omit duration entirely -- null means "unknown", not 0. The timerResult overload never
        // exercises this path since ReadingTimerResult.durationSeconds is a non-null Long.
        val timestampEnd = start.plus(Duration.parse("1h"))

        val result = useCase.execute(
            mediaId = mediaId,
            timestampStart = start,
            timestampEnd = timestampEnd,
            durationSeconds = null,
            startUnit = 0.0,
            endUnit = 50.5,
        )

        assertIs<Resource.Success<String>>(result)
        val session = db.readingSessionDao().getById(result.data)
        assertTrue(session?.durationSeconds == null)
        assertTrue(session?.endUnit == 50.5)
    }

    @Test
    fun execute_negativeStartUnit_returnsErrorAndPersistsNothing() = runTest {
        val timerResult = ReadingTimerResult(start, start.plus(Duration.parse("1m")), 60)

        val result = useCase.execute(
            mediaId = mediaId,
            timerResult = timerResult,
            startUnit = -1.0,
            endUnit = 10.0,
        )

        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("startUnit"))
        assertTrue(noSessionsPersisted())
    }

    @Test
    fun execute_negativeEndUnit_returnsErrorAndPersistsNothing() = runTest {
        val timerResult = ReadingTimerResult(start, start.plus(Duration.parse("1m")), 60)

        val result = useCase.execute(
            mediaId = mediaId,
            timerResult = timerResult,
            startUnit = 5.0,
            endUnit = -0.5,
        )

        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("endUnit"))
        assertTrue(noSessionsPersisted())
    }

    @Test
    fun execute_endUnitLessThanStartUnit_isAllowed_rereadIsNotAnError() = runTest {
        // Per class KDoc: position moving backward (e.g. flipping back to reread a chapter) is a
        // legitimate input, not a validation failure — unlike negative positions.
        val timerResult = ReadingTimerResult(start, start.plus(Duration.parse("10m")), 600)

        val result = useCase.execute(
            mediaId = mediaId,
            timerResult = timerResult,
            startUnit = 100.0,
            endUnit = 80.0,
            deltaPages = -20,
        )

        assertIs<Resource.Success<String>>(result)
        val session = db.readingSessionDao().getById(result.data)
        assertTrue(session?.startUnit == 100.0)
        assertTrue(session?.endUnit == 80.0)
        assertTrue(session?.deltaPages == -20)
    }

    @Test
    fun execute_zeroSecondZeroPageSession_isAllowed() = runTest {
        val timerResult = ReadingTimerResult(start, start, 0)

        val result = useCase.execute(
            mediaId = mediaId,
            timerResult = timerResult,
            startUnit = 12.0,
            endUnit = 12.0,
            deltaPages = 0,
        )

        assertIs<Resource.Success<String>>(result)
        val session = db.readingSessionDao().getById(result.data)
        assertTrue(session?.durationSeconds == 0L)
        assertTrue(session?.deltaPages == 0)
    }

    @Test
    fun execute_nanStartUnit_returnsErrorAndPersistsNothing() = runTest {
        // Double.NaN satisfies `NaN < 0.0 == false` (IEEE 754), so a plain `< 0.0` guard would let
        // it through; the use case must reject non-finite values explicitly (see class KDoc).
        val timerResult = ReadingTimerResult(start, start.plus(Duration.parse("1m")), 60)

        val result = useCase.execute(
            mediaId = mediaId,
            timerResult = timerResult,
            startUnit = Double.NaN,
            endUnit = 10.0,
        )

        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("startUnit"))
        assertTrue(noSessionsPersisted())
    }

    @Test
    fun execute_nanEndUnit_returnsErrorAndPersistsNothing() = runTest {
        val timerResult = ReadingTimerResult(start, start.plus(Duration.parse("1m")), 60)

        val result = useCase.execute(
            mediaId = mediaId,
            timerResult = timerResult,
            startUnit = 5.0,
            endUnit = Double.NaN,
        )

        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("endUnit"))
        assertTrue(noSessionsPersisted())
    }

    @Test
    fun execute_positiveInfinityStartUnit_returnsErrorAndPersistsNothing() = runTest {
        val timerResult = ReadingTimerResult(start, start.plus(Duration.parse("1m")), 60)

        val result = useCase.execute(
            mediaId = mediaId,
            timerResult = timerResult,
            startUnit = Double.POSITIVE_INFINITY,
            endUnit = 10.0,
        )

        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("startUnit"))
        assertTrue(noSessionsPersisted())
    }

    @Test
    fun execute_positiveInfinityEndUnit_returnsErrorAndPersistsNothing() = runTest {
        val timerResult = ReadingTimerResult(start, start.plus(Duration.parse("1m")), 60)

        val result = useCase.execute(
            mediaId = mediaId,
            timerResult = timerResult,
            startUnit = 5.0,
            endUnit = Double.POSITIVE_INFINITY,
        )

        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("endUnit"))
        assertTrue(noSessionsPersisted())
    }

    @Test
    fun execute_invalidTimestamps_propagatesRepositoryErrorAndPersistsNothing() = runTest {
        // Repository-level validation (timestampEnd >= timestampStart) still applies for the
        // explicit-bounds overload, e.g. a manual-entry form with bad input.
        val result = useCase.execute(
            mediaId = mediaId,
            timestampStart = start,
            timestampEnd = start.minus(Duration.parse("1h")),
            durationSeconds = 0,
            startUnit = 0.0,
            endUnit = 10.0,
        )

        assertIs<Resource.Error>(result)
        assertTrue(noSessionsPersisted())
    }
}
