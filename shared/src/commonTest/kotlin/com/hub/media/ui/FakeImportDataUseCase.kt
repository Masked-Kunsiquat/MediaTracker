package com.hub.media.ui

import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.features.portability.domain.ImportSummary
import com.hub.media.features.portability.domain.ImportUseCase
import kotlinx.coroutines.CompletableDeferred

/**
 * Hand-rolled fake [ImportUseCase] for [ImportViewModel] tests (AGENTS.md §5 "No Unnecessary
 * Dependencies" -- no mocking library), mirroring [FakeExportDataUseCase]'s exact shape.
 */
internal class FakeImportDataUseCase(
    private val result: Resource<ImportSummary> =
        Resource.Success(
            ImportSummary(
                booksImported = 0,
                booksSkipped = 0,
                booksMerged = 0,
                booksReplaced = 0,
                sessionsImported = 0,
                sessionsSkipped = 0,
                sessionsMerged = 0,
                sessionsReplaced = 0,
                rejections = emptyList(),
            ),
        ),
) : ImportUseCase {
    /** Number of times [execute] has been called. */
    var callCount: Int = 0
        private set

    /** The arguments [execute] was most recently called with, or `null` if never called. */
    var lastArgs: Triple<String?, String?, DuplicatePolicy>? = null
        private set

    /** Number of times [executeGoodreads] has been called (ROADMAP Task 8 Phase D). */
    var goodreadsCallCount: Int = 0
        private set

    /** The arguments [executeGoodreads] was most recently called with, or `null` if never called. */
    var lastGoodreadsArgs: Pair<String, DuplicatePolicy>? = null
        private set

    /** When true, [execute]/[executeGoodreads] suspend on an internal gate until [release] is called. */
    var awaitGate: Boolean = false

    private val gate = CompletableDeferred<Unit>()

    override suspend fun execute(
        libraryCsv: String?,
        readingLogsCsv: String?,
        duplicatePolicy: DuplicatePolicy,
    ): Resource<ImportSummary> {
        callCount++
        lastArgs = Triple(libraryCsv, readingLogsCsv, duplicatePolicy)
        if (awaitGate) gate.await()
        return result
    }

    override suspend fun executeGoodreads(
        goodreadsCsv: String,
        duplicatePolicy: DuplicatePolicy,
    ): Resource<ImportSummary> {
        goodreadsCallCount++
        lastGoodreadsArgs = goodreadsCsv to duplicatePolicy
        if (awaitGate) gate.await()
        return result
    }

    /** Releases a pending [execute]/[executeGoodreads] call started while [awaitGate] was true. */
    fun release() {
        gate.complete(Unit)
    }
}
