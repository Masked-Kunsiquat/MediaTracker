package com.hub.media.features.books.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing reading session data. Encapsulates all direct database access
 * related to reading progress tracking per AGENTS.md §3.4 (sessions decoupled from items).
 *
 * All write operations are wrapped in [Resource] to handle failures gracefully (AGENTS.md §5).
 */
public class ReadingSessionRepository(private val db: AppDatabase) {

    /**
     * Observes all reading sessions for a specific media (book) as a reactive stream,
     * ordered by most recent first.
     *
     * @param mediaId The ID of the book being tracked.
     * @return A Flow of [ReadingSessionEntity] sorted by timestamp descending.
     */
    public fun observeSessionsForMedia(mediaId: String): Flow<List<ReadingSessionEntity>> =
        db.readingSessionDao().observeSessionsForMedia(mediaId)

    /**
     * Logs a new reading session with validation per AGENTS.md §7.
     * Validates that:
     * - [timestampEnd] >= [timestampStart] (non-negative duration)
     * - [durationSeconds] >= 0, when non-null
     * - Edge cases: 0-page deltas and 0-second sessions are allowed (valid edge cases)
     *
     * @param mediaId The ID of the book being read.
     * @param timestampStart When the session started.
     * @param timestampEnd When the session ended.
     * @param durationSeconds Elapsed time in seconds, or `null` if unknown (schema v2, ROADMAP
     *   Task 5 pre-phase — see [com.hub.media.core.database.entities.ReadingSessionEntity]'s
     *   KDoc). A timer-backed session always has a real value here; only a manual entry may pass
     *   `null`. `null` skips the `>= 0` check entirely (there is nothing to validate).
     * @param startUnit Start position (page or percentage).
     * @param endUnit End position (page or percentage).
     * @param deltaPages Optional change in pages (can be null for ebook/percentage-based tracking).
     * @param notes Optional notes about the session.
     * @return [Resource.Success] with the new session ID, or [Resource.Error] on validation/DB failure.
     */
    public suspend fun logSession(
        mediaId: String,
        timestampStart: Instant,
        timestampEnd: Instant,
        durationSeconds: Long?,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int? = null,
        notes: String? = null,
    ): Resource<String> {
        // Validation per AGENTS.md §7: timestampEnd must be >= timestampStart
        if (timestampEnd < timestampStart) {
            return Resource.Error("timestampEnd must be >= timestampStart")
        }

        // Validation: durationSeconds must be >= 0 when known; null (unknown) always passes.
        if (durationSeconds != null && durationSeconds < 0) {
            return Resource.Error("durationSeconds must be >= 0")
        }

        return try {
            val sessionId = newId()
            val session = ReadingSessionEntity(
                id = sessionId,
                mediaId = mediaId,
                timestampStart = timestampStart,
                timestampEnd = timestampEnd,
                durationSeconds = durationSeconds,
                startUnit = startUnit,
                endUnit = endUnit,
                deltaPages = deltaPages,
                notes = notes,
            )

            db.readingSessionDao().insert(session)
            Resource.Success(sessionId)
        } catch (e: Exception) {
            Resource.Error(
                message = "Failed to log reading session: ${e.message ?: "Unknown error"}",
                cause = e,
            )
        }
    }

    /**
     * Deletes a reading session by ID.
     *
     * @param id The session ID to delete.
     * @return [Resource.Success] if deleted, or [Resource.Error] on failure.
     */
    public suspend fun deleteSession(id: String): Resource<Unit> = try {
        db.readingSessionDao().deleteById(id)
        Resource.Success(Unit)
    } catch (e: Exception) {
        Resource.Error(
            message = "Failed to delete reading session: ${e.message ?: "Unknown error"}",
            cause = e,
        )
    }
}
