package com.hub.media.ui

import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.ArchiveExporter
import com.hub.media.features.portability.domain.ExportArchiveFile
import kotlinx.coroutines.CompletableDeferred

/** A Room-free [ArchiveExporter] for [ExportViewModel] tests: a canned result, an optional gate, a record of calls. */
internal class FakeArchiveExporter(
    private val result: Resource<ExportArchiveFile> =
        Resource.Success(
            ExportArchiveFile(
                bytes = byteArrayOf(0x50, 0x4B, 3, 4),
                suggestedFileName = "media-tracker-export-2026-10-14.zip",
            ),
        ),
) : ArchiveExporter {
    var callCount: Int = 0
        private set

    val appVersions: MutableList<String> = mutableListOf()

    var awaitGate: Boolean = false

    private val gate = CompletableDeferred<Unit>()

    override suspend fun export(appVersion: String): Resource<ExportArchiveFile> {
        callCount++
        appVersions += appVersion
        if (awaitGate) gate.await()
        return result
    }

    fun release() {
        gate.complete(Unit)
    }
}
