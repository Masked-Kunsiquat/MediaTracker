package com.hub.media.features.books.domain

import kotlin.time.Instant

/**
 * Pure reading-session validation rules (ROADMAP Task 8 Phase B extraction), gathering three
 * checks that previously lived as duplicated inline conditions:
 * - [validateTimestamps]/[validateDuration] were identical inline checks copy-pasted between
 *   [com.hub.media.features.books.data.ReadingSessionRepository.logSession] and
 *   [com.hub.media.features.books.data.ReadingSessionRepository.updateSession] (each KDoc even
 *   said "identical to logSession's check above" -- an acknowledged duplication).
 * - [validatePositions] was a private function on [LogReadingSessionUseCase], unreachable from
 *   outside that class.
 *
 * Both are now single, reusable functions so the CSV importer (`ImportDataUseCase`) can apply the
 * exact same position/timestamp/duration rules a manual entry or edit already enforces, instead of
 * forking a second, divergent copy (AGENTS.md §7's "reuse, don't fork" requirement for this
 * phase's row validation). Message text is unchanged from the inline checks these replace, so
 * existing callers observe identical [com.hub.media.core.util.Resource.Error] text.
 */
public object ReadingSessionValidation {
    /** [timestampEnd] must be `>= timestampStart` -- time cannot run backward. */
    public fun validateTimestamps(
        timestampStart: Instant,
        timestampEnd: Instant,
    ): String? = if (timestampEnd < timestampStart) "timestampEnd must be >= timestampStart" else null

    /** A known [durationSeconds] must be `>= 0`; `null` ("unknown") always passes. */
    public fun validateDuration(durationSeconds: Long?): String? =
        if (durationSeconds != null && durationSeconds < 0) "durationSeconds must be >= 0" else null

    /**
     * [startUnit]/[endUnit] must each be finite and `>= 0` -- see
     * [LogReadingSessionUseCase]'s class KDoc for why `endUnit < startUnit` is deliberately
     * *allowed* (re-reading backward is a legitimate position, not an error) while non-finite or
     * negative values are not.
     */
    public fun validatePositions(
        startUnit: Double,
        endUnit: Double,
    ): String? {
        if (!startUnit.isFinite() || startUnit < 0.0) {
            return "startUnit must be finite and >= 0 (was $startUnit)"
        }
        if (!endUnit.isFinite() || endUnit < 0.0) {
            return "endUnit must be finite and >= 0 (was $endUnit)"
        }
        return null
    }
}
