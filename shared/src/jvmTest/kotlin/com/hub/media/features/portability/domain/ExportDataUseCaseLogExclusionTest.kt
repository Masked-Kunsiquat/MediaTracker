package com.hub.media.features.portability.domain

import androidx.room.Room
import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.AppDatabaseConstructor
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.buildAppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Regression guard for ROADMAP Task 15 Phase B ("Must be excluded from backup and export -- the
 * non-obvious hazard"): proves the CSV documents [ExportDataUseCase] produces never contain
 * content from a log file sitting at the persistent log store's fixed-contract location
 * (`<filesDir>/logs/`, a sibling, in-progress workstream this class deliberately has no dependency
 * on -- see [ExportDataUseCase]'s "why there is no exclude filter" KDoc section). Lives in
 * `com.hub.media.features.portability.domain` next to [ExportDataUseCaseTest] and
 * [DatabaseBackupUseCaseTest] -- Room-backed, so it lives in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81); `:shared:jvmTest` is the authoritative
 * gate. Uses a real, file-backed [AppDatabase] (rather than [ExportDataUseCaseTest]'s in-memory
 * one) purely so a real `logs/` directory can sit beside it on disk, exactly like the real app.
 *
 * This is not a vacuous test: [ExportDataUseCase] genuinely has no code path that could read the
 * decoy file created below, so it exercises the real [ExportDataUseCase.execute] pipeline
 * end-to-end and would fail immediately if a future change ever wired log content into either
 * generated CSV.
 */
class ExportDataUseCaseLogExclusionTest {
    private val tempDir = Files.createTempDirectory("export-log-exclusion-test")
    private val dbFile = tempDir.resolve("live.db").toFile()

    @AfterTest
    fun tearDown() {
        Files.list(tempDir).use { paths -> paths.forEach { if (it.exists()) it.deleteIfExists() } }
        if (tempDir.exists()) tempDir.deleteExisting()
    }

    @Test
    fun execute_decoyLogFileAtFixedContractPath_neitherCsvContainsItsContent() =
        runTest {
            val logMarker = "REGRESSION_GUARD_LOG_MARKER_do_not_leak_into_csv_export"
            // The fixed contract this task was told to coordinate with by convention only (ROADMAP
            // Task 15 Phase B): the log store writes into `<filesDir>/logs/`. dbFile's parent stands
            // in for filesDir here, the same way DatabaseBackupUseCaseTest's dbFile does.
            val logsDir = File(dbFile.parentFile, "logs").apply { mkdirs() }
            val decoyLogFile =
                File(logsDir, "app.log").apply {
                    writeText("WARN BackfillViewModel: $logMarker\n")
                }

            val liveDb =
                buildAppDatabase(
                    Room.databaseBuilder<AppDatabase>(
                        name = dbFile.absolutePath,
                        factory = AppDatabaseConstructor::initialize,
                    ),
                )
            try {
                val bookRepository = BookRepository(liveDb)
                val readingSessionRepository = ReadingSessionRepository(liveDb)

                val addResult =
                    bookRepository.addBook(
                        title = "Log Exclusion Export Check",
                        releaseYear = 2024,
                        purchasePrice = null,
                        format = BookFormat.EBOOK,
                        totalPages = null,
                        isbn = null,
                        externalIdentifiers = emptyList(),
                    )
                assertIs<Resource.Success<String>>(addResult)
                val mediaId = addResult.data

                val sessionResult =
                    readingSessionRepository.logSession(
                        mediaId = mediaId,
                        timestampStart = Instant.fromEpochMilliseconds(1_700_000_000_000),
                        timestampEnd = Instant.fromEpochMilliseconds(1_700_000_600_000),
                        durationSeconds = 600,
                        startUnit = 0.0,
                        endUnit = 10.0,
                        deltaPages = null,
                        notes = null,
                    )
                assertIs<Resource.Success<String>>(sessionResult)

                val useCase = ExportDataUseCase(MediaRepository(liveDb), bookRepository, readingSessionRepository)
                val result = useCase.execute()
                assertIs<Resource.Success<CsvExportBundle>>(result)
                val bundle = result.data

                assertTrue(
                    !bundle.libraryCsv.contains(logMarker),
                    "library_export.csv must never contain log content -- see ExportDataUseCase's " +
                        "\"why there is no exclude filter\" KDoc section",
                )
                assertTrue(
                    !bundle.readingLogsCsv.contains(logMarker),
                    "reading_logs_export.csv must never contain log content -- despite the filename, " +
                        "this file is reading SESSIONS, not application logs; see ExportDataUseCase's " +
                        "\"why there is no exclude filter\" KDoc section",
                )
                assertTrue(
                    !bundle.episodesCsv.contains(logMarker),
                    "episodes_export.csv must never contain log content -- see ExportDataUseCase's " +
                        "\"why there is no exclude filter\" KDoc section",
                )
                // Sanity check that the decoy file itself really holds the marker, so a typo in the
                // marker string above couldn't make this test pass for the wrong reason.
                assertTrue(decoyLogFile.readText().contains(logMarker))
            } finally {
                liveDb.close()
                decoyLogFile.delete()
                logsDir.delete()
            }
        }
}
