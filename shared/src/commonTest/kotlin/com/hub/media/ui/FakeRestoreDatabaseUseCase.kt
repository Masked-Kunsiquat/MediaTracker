package com.hub.media.ui

import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.RestoreDatabaseUseCase
import com.hub.media.features.portability.domain.StagedRestoreInfo

/**
 * Hand-rolled fake [RestoreDatabaseUseCase] for [RestoreViewModel] tests (AGENTS.md §5 "No
 * Unnecessary Dependencies" -- no mocking library), mirroring [FakeExportDataUseCase]'s shape.
 *
 * [commit] is deliberately never exercised through [RestoreViewModel] itself -- that ViewModel
 * intentionally never calls it (see its KDoc for why: committing requires closing the very
 * [com.hub.media.ui.AppContainer] this use case was wired from, plus a process restart, neither of
 * which can happen from inside a `ViewModel`). It is still implemented here, returning
 * [commitResult] unconditionally, purely to satisfy the [RestoreDatabaseUseCase] interface.
 */
internal class FakeRestoreDatabaseUseCase(
    private val stageResult: Resource<StagedRestoreInfo> =
        Resource.Success(
            StagedRestoreInfo(
                stagedFilePath = "/fake/cache/staged-restore.sqlite",
                schemaVersionFound = 5,
                isOlderSchemaVersion = false,
            ),
        ),
    private val commitResult: Resource<Unit> = Resource.Success(Unit),
) : RestoreDatabaseUseCase {
    /** Number of times [stage] has been called. */
    var stageCallCount: Int = 0
        private set

    override suspend fun stage(incomingFilePath: String): Resource<StagedRestoreInfo> {
        stageCallCount++
        return stageResult
    }

    override suspend fun commit(staged: StagedRestoreInfo): Resource<Unit> = commitResult
}
