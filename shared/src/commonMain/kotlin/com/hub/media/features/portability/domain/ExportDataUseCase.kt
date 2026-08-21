package com.hub.media.features.portability.domain

import com.hub.media.core.database.MediaRepository
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.core.util.info
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.portability.csv.LibraryCsvExporter
import com.hub.media.features.portability.csv.ReadingLogCsvExporter
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

/** Log tag for this file's adoption sites (ROADMAP Task 15 Phase C). */
private const val TAG = "ExportDataUseCase"

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
 *
 * ### Why there is no "exclude log data" filter here (ROADMAP Task 15 Phase B)
 * There is no log-related code in this class, deliberately -- not an oversight to fill in later.
 * [execute] only ever reads through [bookRepository]/[readingSessionRepository], both backed by
 * Room tables, and formats what they return via [LibraryCsvExporter]/[ReadingLogCsvExporter],
 * whose column sets are fixed at compile time (see each exporter's KDoc for the exact list). The
 * persistent log store (Task 15 Phase B) is a flat file under `<filesDir>/logs/`, deliberately
 * **not** a Room table -- "that would bloat the database that gets backed up and CSV-exported,"
 * per that task's own ROADMAP entry -- so there is no query, no table, and no field this class
 * could pull log content from even by accident. This is a structural guarantee, not a runtime
 * check: adding a defensive "strip log fields" step here would be dead code, since no code path
 * exists for log content to reach [CsvExportBundle] in the first place. **What must stay true for
 * this guarantee to hold:** the log store must never become a Room entity/table, and this class
 * must never gain a dependency that reads the log directory. [ExportDataUseCaseLogExclusionTest]
 * is the regression guard -- it plants a decoy log file at the real fixed-contract path and
 * asserts neither generated CSV contains its content, so a future change that *did* wire log
 * content into this path would fail that test immediately.
 */
public class ExportDataUseCase(
    private val mediaRepository: MediaRepository,
    private val bookRepository: BookRepository,
    private val readingSessionRepository: ReadingSessionRepository,
    private val logger: Logger = AppLogger,
) : ExportUseCase {
    /**
     * Runs the export: reads one current snapshot of books, their external identifiers, and every
     * reading session (each via `Flow.first()` -- a one-shot read, not an ongoing subscription),
     * then formats them via [LibraryCsvExporter]/[ReadingLogCsvExporter].
     *
     * @return [Resource.Success] with both generated CSV documents, or [Resource.Error] describing
     *   why the read/format step failed. Never throws.
     */
    public override suspend fun execute(): Resource<CsvExportBundle> =
        try {
            // Every media type, not just books. This read used to go through
            // BookRepository.observeAllBooksWithDetails(), which filters to MediaType.BOOK at the
            // DAO -- so a movie was silently absent from the backup rather than failing loudly.
            // LibraryCsvExporter has handled the polymorphic list since Issue #67; only its source
            // was still book-shaped.
            val media = mediaRepository.observeAllMediaWithDetails().first()
            // Still via BookRepository, which is where this lives today even though it reads every
            // identifier regardless of media type. Moving it to MediaRepository is the tidier home
            // and is deliberately not done here.
            val identifiersByMediaId =
                bookRepository
                    .observeAllExternalIdentifiers()
                    .first()
                    .groupBy { it.mediaId }
            val sessions = readingSessionRepository.observeAllSessions().first()

            // Build the bundle BEFORE logging completion, deliberately -- a completion entry must
            // never be written before the thing it claims completed. If either exporter throws, this
            // ordering ensures the catch block below logs "Export failed" instead of this having
            // already logged "Export completed" moments earlier.
            val bundle =
                CsvExportBundle(
                    libraryCsv = LibraryCsvExporter.export(media, identifiersByMediaId),
                    readingLogsCsv = ReadingLogCsvExporter.export(sessions),
                )
            logger.info(TAG) {
                "Export completed: ${media.size} item(s), ${sessions.size} session(s)"
            }
            Resource.Success(bundle)
        } catch (e: CancellationException) {
            // Rethrown ahead of the Exception catch -- on JVM CancellationException is an Exception, so
            // swallowing it would break structured concurrency and log a cancelled screen as a failure.
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Export failed" }
            Resource.Error(
                message = "Failed to export data: ${e.message ?: "Unknown error"}",
                cause = e,
            )
        }
}
