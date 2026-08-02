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
     * Observes every reading session across the whole library as a reactive stream (ROADMAP
     * Task 8 Phase A: `reading_logs_export.csv` needs every session, not just one book's — see
     * [com.hub.media.features.portability.domain.ExportDataUseCase]).
     */
    public fun observeAllSessions(): Flow<List<ReadingSessionEntity>> =
        db.readingSessionDao().observeAll()

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
     * Updates an existing reading session (ROADMAP Task 6 Phase B — session editing).
     *
     * Validation mirrors [logSession] EXACTLY (same two checks, same messages): the create and
     * edit paths persist the same shape of data, so there is no reason for their timestamp/
     * duration invariants to diverge — a session that would be rejected at creation time must be
     * rejected at edit time too, and vice versa. Position-bounds validation (finite/non-negative)
     * is the caller's responsibility, same division as [logSession] — see
     * [com.hub.media.features.books.domain.LogReadingSessionUseCase.executeUpdate].
     *
     * Unlike [logSession] (which always inserts a fresh row), this needs a target row to update.
     * [sessionId] not resolving to an existing session — e.g. it was deleted from another screen,
     * or a stale/garbage id — returns [Resource.Error] rather than silently no-op'ing the way a
     * bare `@Update` on a missing primary key would (Room's generated `@Update` just reports 0
     * rows affected; it does not throw), so the caller gets an explicit failure signal instead of
     * a Save button that appears to work but changed nothing.
     *
     * @param sessionId The id of the session to update.
     * @param timestampStart When the session started.
     * @param timestampEnd When the session ended.
     * @param durationSeconds Elapsed time in seconds, or `null` if unknown (schema v2, ROADMAP
     *   Task 5 pre-phase). `null` skips the `>= 0` check entirely, same as [logSession].
     * @param startUnit Start position (page or percentage).
     * @param endUnit End position (page or percentage).
     * @param deltaPages Optional change in pages (page-mode: auto-derived by the caller as
     *   `endUnit - startUnit`; percent-mode: manually entered, may be null).
     * @param notes Optional notes about the session.
     * @return [Resource.Success] on a successful update, or [Resource.Error] on validation
     *   failure, an unknown [sessionId], a DB failure, or the row vanishing (e.g. deleted by
     *   another writer) between the [getById] check above and the write below -- detected via
     *   [com.hub.media.core.database.dao.ReadingSessionDao.update]'s affected-row count rather than
     *   assumed from the earlier [getById] alone.
     */
    public suspend fun updateSession(
        sessionId: String,
        timestampStart: Instant,
        timestampEnd: Instant,
        durationSeconds: Long?,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int? = null,
        notes: String? = null,
    ): Resource<Unit> {
        // Validation per AGENTS.md §7: timestampEnd must be >= timestampStart -- identical to
        // logSession's check above.
        if (timestampEnd < timestampStart) {
            return Resource.Error("timestampEnd must be >= timestampStart")
        }

        // Validation: durationSeconds must be >= 0 when known; null (unknown) always passes --
        // identical to logSession's check above.
        if (durationSeconds != null && durationSeconds < 0) {
            return Resource.Error("durationSeconds must be >= 0")
        }

        return try {
            val existing = db.readingSessionDao().getById(sessionId)
                ?: return Resource.Error("Reading session not found: $sessionId")

            val updated = existing.copy(
                timestampStart = timestampStart,
                timestampEnd = timestampEnd,
                durationSeconds = durationSeconds,
                startUnit = startUnit,
                endUnit = endUnit,
                deltaPages = deltaPages,
                notes = notes,
            )

            val rowsAffected = db.readingSessionDao().update(updated)
            if (rowsAffected == 0) {
                return Resource.Error("Reading session not found: $sessionId")
            }
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(
                message = "Failed to update reading session: ${e.message ?: "Unknown error"}",
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
