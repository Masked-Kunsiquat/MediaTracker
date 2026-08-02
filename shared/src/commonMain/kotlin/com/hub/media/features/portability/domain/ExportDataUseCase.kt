package com.hub.media.features.portability.domain

import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.portability.csv.LibraryCsvExporter
import com.hub.media.features.portability.csv.ReadingLogCsvExporter
import kotlinx.coroutines.flow.first

/**
 * Abstraction over "generate both CSV exports" so [com.hub.media.ui.ExportViewModel] can depend on
 * a narrow contract instead of the concrete [ExportDataUseCase] -- mirrors
 * [com.hub.media.features.books.domain.BookIngestionUseCase]'s exact reason for existing
 * (AGENTS.md §5 "No Unnecessary Dependencies" -- no mocking library): `commonTest` can hand-roll a
 * fake implementation with no Room database dependency at all, keeping `ExportViewModelTest`
 * runnable on every test variant instead of needing the Room-touching-test exclusion filter in
 * `shared/build.gradle.kts` that [ExportDataUseCaseTest] itself needs.
 */
public interface ExportUseCase {
    /** See [ExportDataUseCase.execute]. */
    public suspend fun execute(): Resource<CsvExportBundle>
}

/**
 * End-to-end "export my data to CSV" workflow (ROADMAP Task 8 Phase A): takes one consistent
 * snapshot of the whole library plus every reading session, and hands back both generated CSV
 * documents as a [CsvExportBundle]. Pure Kotlin/KMP-clean (no Android APIs, no file I/O) --
 * consumed by [com.hub.media.ui.ExportViewModel]; the app module is responsible for actually
 * writing the two returned strings to files the user picks via SAF.
 *
 * @param bookRepository Source of the library snapshot
 *   ([BookRepository.observeAllBooksWithDetails]) and every external identifier
 *   ([BookRepository.observeAllExternalIdentifiers]).
 * @param readingSessionRepository Source of every reading session across the library
 *   ([ReadingSessionRepository.observeAllSessions]).
 */
public class ExportDataUseCase(
    private val bookRepository: BookRepository,
    private val readingSessionRepository: ReadingSessionRepository,
) : ExportUseCase {

    /**
     * Runs the export: reads one current snapshot of books, their external identifiers, and every
     * reading session (each via `Flow.first()` -- a one-shot read, not an ongoing subscription),
     * then formats them via [LibraryCsvExporter]/[ReadingLogCsvExporter].
     *
     * @return [Resource.Success] with both generated CSV documents, or [Resource.Error] describing
     *   why the read/format step failed. Never throws.
     */
    public override suspend fun execute(): Resource<CsvExportBundle> = try {
        val books = bookRepository.observeAllBooksWithDetails().first()
        val identifiersByMediaId = bookRepository.observeAllExternalIdentifiers().first()
            .groupBy { it.mediaId }
        val sessions = readingSessionRepository.observeAllSessions().first()

        Resource.Success(
            CsvExportBundle(
                libraryCsv = LibraryCsvExporter.export(books, identifiersByMediaId),
                readingLogsCsv = ReadingLogCsvExporter.export(sessions),
            ),
        )
    } catch (e: Exception) {
        Resource.Error(
            message = "Failed to export data: ${e.message ?: "Unknown error"}",
            cause = e,
        )
    }
}
