package com.hub.media.features.books.domain

import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.books.timer.ReadingTimer
import com.hub.media.features.books.timer.ReadingTimerResult
import kotlin.time.Instant

/**
 * Connects a finished [ReadingTimer] run — or an explicit start/end/duration triple, e.g. from a
 * manual session-entry form not backed by a live timer — plus user-entered position bounds to
 * [ReadingSessionRepository.logSession] (ROADMAP Task 4 Phase A).
 *
 * ### Division of validation responsibility
 * [ReadingSessionRepository.logSession] already validates `timestampEnd >= timestampStart` and
 * `durationSeconds >= 0`. Those are timer/time invariants — a real [ReadingTimerResult] can never
 * violate them, but a hand-typed manual-entry form (the explicit-bounds [execute] overload) could,
 * so the repository's checks still matter and are deliberately not duplicated here.
 *
 * This use case adds the one kind of validation the repository doesn't do: **position bounds**.
 * - **Negative, `NaN`, or infinite [startUnit]/[endUnit] is always invalid** — a physical page
 *   number or e-reader percentage (AGENTS.md §3.5) must be a finite, non-negative value — and
 *   returns [Resource.Error] without ever calling the repository, so nothing is persisted.
 *   `Double.NaN` in particular satisfies `NaN < 0.0 == false` (per IEEE 754, every comparison
 *   involving `NaN` other than `!=` is `false`), so a plain `< 0.0` check silently lets `NaN`
 *   through to persist and then poison any downstream progress math (e.g. `currentProgress`
 *   comparisons/formatting) that reads it back out. [isFinite] rejects both `NaN` and
 *   `±Infinity` up front.
 * - **`endUnit < startUnit` is allowed, on purpose.** Position isn't like time: time can't run
 *   backward, but reading position legitimately can. Flipping back mid-session to reread an
 *   earlier chapter, or a session that starts by resuming from a bookmark that was itself a
 *   reread, are both real, non-erroneous inputs — "re-reading backwards" isn't a coherent concept
 *   to reject, only "negative position" is. [ReadingSessionRepositoryTest.logSession_negativePages_succeeds]
 *   already encodes this same rule one layer down; this use case intentionally does not add a
 *   stricter monotonicity constraint on top of it.
 *
 * @param repository Persists the validated session.
 */
public class LogReadingSessionUseCase(
    private val repository: ReadingSessionRepository,
) {

    /**
     * Logs a session from a finished [timerResult] (the common path: user pressed stop on a live
     * [ReadingTimer]).
     *
     * @param mediaId The book the session belongs to.
     * @param timerResult The result returned by [ReadingTimer.stop].
     * @param startUnit Position (page or percent) at the start of the session. Must be finite and
     *   `>= 0`.
     * @param endUnit Position at the end of the session. Must be finite and `>= 0`; may be less
     *   than [startUnit] (see class KDoc).
     * @param deltaPages Optional page delta. `0` is a valid edge case (AGENTS.md §7), as is `null`
     *   for percentage/e-reader tracking where a page count doesn't apply.
     * @param notes Optional free-text notes.
     * @return [Resource.Success] with the new session id, or [Resource.Error] on validation or
     *   persistence failure. Never throws.
     */
    public suspend fun execute(
        mediaId: String,
        timerResult: ReadingTimerResult,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int? = null,
        notes: String? = null,
    ): Resource<String> = execute(
        mediaId = mediaId,
        timestampStart = timerResult.timestampStart,
        timestampEnd = timerResult.timestampEnd,
        durationSeconds = timerResult.durationSeconds,
        startUnit = startUnit,
        endUnit = endUnit,
        deltaPages = deltaPages,
        notes = notes,
    )

    /**
     * Logs a session from explicit bounds rather than a [ReadingTimerResult], e.g. a manual
     * session-entry form. See the [timerResult] overload and class KDoc for the full validation
     * contract.
     *
     * @param durationSeconds Elapsed time in seconds, or `null` if unknown (schema v2, ROADMAP
     *   Task 5 pre-phase) — e.g. a backlogged manual entry the user doesn't want to invent a
     *   duration for. Forwarded as-is to [ReadingSessionRepository.logSession], which only
     *   validates `>= 0` when non-null.
     */
    public suspend fun execute(
        mediaId: String,
        timestampStart: Instant,
        timestampEnd: Instant,
        durationSeconds: Long?,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int? = null,
        notes: String? = null,
    ): Resource<String> {
        validatePositions(startUnit, endUnit)?.let { return it }

        return repository.logSession(
            mediaId = mediaId,
            timestampStart = timestampStart,
            timestampEnd = timestampEnd,
            durationSeconds = durationSeconds,
            startUnit = startUnit,
            endUnit = endUnit,
            deltaPages = deltaPages,
            notes = notes,
        )
    }

    /**
     * Updates an existing session (ROADMAP Task 6 Phase B — session editing), via
     * [ReadingSessionRepository.updateSession].
     *
     * ### Why this routes through the repository directly rather than a sibling use case, and why
     * validation stays identical
     * There is exactly one kind of validation this use case owns: the position-bounds check in
     * [validatePositions] (see class KDoc). That check is not tied to *creating* a row — it's an
     * invariant of what a valid session's position bounds look like, full stop — so [executeUpdate]
     * calls the exact same private [validatePositions] helper the explicit-bounds [execute]
     * overload does, rather than re-deriving or loosening it for edits. The timestamp/duration
     * checks are owned by [ReadingSessionRepository] (identically for create and update — see
     * [ReadingSessionRepository.updateSession] KDoc) and are not duplicated here either. The net
     * effect: a session that would be rejected on creation is rejected on edit for the exact same
     * reason, with the validation logic itself living in exactly one place per check.
     *
     * @param sessionId The id of the session being edited.
     * @param durationSeconds Elapsed time in seconds, or `null` if unknown (schema v2). Forwarded
     *   as-is to [ReadingSessionRepository.updateSession].
     * @param startUnit Position (page or percent) at the start of the session. Must be finite and
     *   `>= 0`.
     * @param endUnit Position at the end of the session. Must be finite and `>= 0`; may be less
     *   than [startUnit] (re-reading backward is valid, see class KDoc).
     * @param deltaPages Optional page delta.
     * @param notes Optional free-text notes.
     * @return [Resource.Success] on success, or [Resource.Error] on validation failure, an unknown
     *   [sessionId], or a DB failure. Never throws.
     */
    public suspend fun executeUpdate(
        sessionId: String,
        timestampStart: Instant,
        timestampEnd: Instant,
        durationSeconds: Long?,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int? = null,
        notes: String? = null,
    ): Resource<Unit> {
        validatePositions(startUnit, endUnit)?.let { return it }

        return repository.updateSession(
            sessionId = sessionId,
            timestampStart = timestampStart,
            timestampEnd = timestampEnd,
            durationSeconds = durationSeconds,
            startUnit = startUnit,
            endUnit = endUnit,
            deltaPages = deltaPages,
            notes = notes,
        )
    }

    /**
     * Shared position-bounds validation for both [execute] (explicit-bounds overload) and
     * [executeUpdate] — see class KDoc's "Division of validation responsibility" and
     * [executeUpdate]'s KDoc for why this must be the single place this check lives, rather than
     * copied per entry point where create and edit could silently drift apart.
     *
     * @return A [Resource.Error] describing the first invalid field, or `null` if both are valid.
     *
     * Delegates to [ReadingSessionValidation.validatePositions] (ROADMAP Task 8 Phase B
     * extraction) so the CSV importer can apply this exact rule too, rather than forking a second
     * copy -- see that object's KDoc.
     */
    private fun validatePositions(startUnit: Double, endUnit: Double): Resource.Error? =
        ReadingSessionValidation.validatePositions(startUnit, endUnit)?.let { Resource.Error(it) }
}
