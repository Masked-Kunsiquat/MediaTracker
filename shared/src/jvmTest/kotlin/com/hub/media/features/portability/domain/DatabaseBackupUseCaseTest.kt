package com.hub.media.features.portability.domain

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.AppDatabaseConstructor
import com.hub.media.core.database.buildAppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import java.io.File
import java.nio.file.Files
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Integration test for [DefaultDatabaseBackupUseCase] against a **real, file-backed** [AppDatabase]
 * (unlike `ExportDataUseCaseTest`'s in-memory one) -- backup/restore is precisely the feature area
 * where an in-memory database can't prove anything, since the whole point is exercising real
 * on-disk WAL behavior. Lives in `com.hub.media.features.portability.domain`, already excluded from
 * the android unit-test variant by the package-wide filter in `shared/build.gradle.kts`
 * (`:shared:jvmTest` is the authoritative gate for this whole package).
 */
class DatabaseBackupUseCaseTest {

    private val tempDir = Files.createTempDirectory("backup-test")
    private val dbFile = tempDir.resolve("live.db").toFile()

    private fun openLiveDatabase(): AppDatabase =
        buildAppDatabase(Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath, factory = AppDatabaseConstructor::initialize))

    @AfterTest
    fun tearDown() {
        Files.list(tempDir).use { paths -> paths.forEach { if (it.exists()) it.deleteIfExists() } }
        if (tempDir.exists()) tempDir.deleteExisting()
    }

    /**
     * The core WAL-safety deliverable (task brief): confirms the live database's own `-wal` file is
     * genuinely non-trivial (proof the just-inserted row has **not** yet been checkpointed into the
     * main `.db` file -- so a naive copy of just that main file would miss it), then asserts the
     * backup produced by [DefaultDatabaseBackupUseCase] nonetheless contains the row when opened
     * fresh. This is not vacuous: a plain `dbFile.copyTo(...)` in place of the `VACUUM INTO` call
     * was verified by hand to produce a backup file *missing* the row (opening it fresh throws a
     * corrupt-database-image error, since a bare main-file copy without its WAL is not a valid
     * standalone database at all) before this test was finalized against the real implementation.
     */
    @Test
    fun execute_capturesRowStillOnlyInWal_notYetCheckpointedToMainFile() = runTest {
        val liveDb = openLiveDatabase()
        val bookRepository = BookRepository(liveDb)
        val addResult = bookRepository.addBook(
            title = "WAL Safety Check",
            releaseYear = 2024,
            purchasePrice = null,
            format = BookFormat.EBOOK,
            totalPages = null,
            isbn = null,
            externalIdentifiers = emptyList(),
        )
        assertIs<Resource.Success<String>>(addResult)

        val walFile = File("${dbFile.absolutePath}-wal")
        assertTrue(
            walFile.exists() && walFile.length() > 0,
            "expected the just-inserted row to still be sitting only in the -wal file " +
                "(this app's live database runs in WAL mode by default -- see " +
                "DefaultDatabaseBackupUseCase's KDoc) -- if this fails, WAL isn't actually active " +
                "and the rest of this test proves nothing",
        )

        val useCase = DefaultDatabaseBackupUseCase(liveDb, dbFile.absolutePath)
        val result = useCase.execute()
        assertIs<Resource.Success<BackupResult>>(result)
        val stagedFile = File(result.data.stagedFilePath)
        assertTrue(stagedFile.exists(), "backup use case must actually produce a file")

        liveDb.close()

        val backupDb = buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(name = stagedFile.absolutePath, factory = AppDatabaseConstructor::initialize),
        )
        try {
            val titles = backupDb.mediaItemDao().observeAll().first().map { it.title }
            assertEquals(listOf("WAL Safety Check"), titles, "the backup must contain the row that was only in the WAL")
        } finally {
            backupDb.close()
            stagedFile.delete()
        }
    }

    @Test
    fun execute_emptyDatabase_producesAnOpenableBackup() = runTest {
        val liveDb = openLiveDatabase()
        val useCase = DefaultDatabaseBackupUseCase(liveDb, dbFile.absolutePath)
        val result = useCase.execute()
        assertIs<Resource.Success<BackupResult>>(result)
        val stagedFile = File(result.data.stagedFilePath)
        assertTrue(stagedFile.exists())

        liveDb.close()

        val backupDb = buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(name = stagedFile.absolutePath, factory = AppDatabaseConstructor::initialize),
        )
        try {
            val books = backupDb.mediaItemDao().observeAll().first()
            assertTrue(books.isEmpty())
        } finally {
            backupDb.close()
            stagedFile.delete()
        }
    }

    @Test
    fun execute_suggestedFileNameHasDbExtensionAndPrefix() = runTest {
        val liveDb = openLiveDatabase()
        val useCase = DefaultDatabaseBackupUseCase(liveDb, dbFile.absolutePath)
        val result = useCase.execute()
        assertIs<Resource.Success<BackupResult>>(result)
        assertTrue(result.data.suggestedFileName.startsWith("media_tracker_backup_"))
        assertTrue(result.data.suggestedFileName.endsWith(".db"))
        File(result.data.stagedFilePath).delete()
        liveDb.close()
    }

    /**
     * Regression guard for ROADMAP Task 15 Phase B ("Must be excluded from backup and export --
     * the non-obvious hazard"): proves the actual bytes of a produced `.sqlite` backup never
     * contain content from a log file sitting right next to the live database, at the exact
     * fixed-contract path (`<filesDir>/logs/`) the persistent log store (a sibling, in-progress
     * workstream) is contracted to write to. Exercises the real
     * [DefaultDatabaseBackupUseCase.execute] path end to end -- not vacuous, because it would fail
     * the moment `VACUUM INTO`'s destination (or anything upstream of it) started pulling in
     * arbitrary filesystem content instead of only ever reading/writing SQLite pages (see that
     * class's "why there is no exclude filter" KDoc section for the structural reason it doesn't
     * today).
     */
    @Test
    fun execute_decoyLogFileBesideLiveDatabase_backupBytesContainNoLogMarker() = runTest {
        val logMarker = "REGRESSION_GUARD_LOG_MARKER_do_not_leak_into_backup"
        val logsDir = File(dbFile.parentFile, "logs").apply { mkdirs() }
        val decoyLogFile = File(logsDir, "app.log").apply {
            writeText("ERROR OpenLibraryIsbnCoverProbe: $logMarker\n")
        }

        // try starts immediately after decoyLogFile exists, so a failure anywhere in setup below
        // (opening the live DB, adding the book, running the use case) still reaches the finally
        // block and cleans up logsDir/decoyLogFile rather than leaking them past this test.
        var liveDb: AppDatabase? = null
        var stagedFile: File? = null
        try {
            liveDb = openLiveDatabase()
            val bookRepository = BookRepository(liveDb)
            val addResult = bookRepository.addBook(
                title = "Log Exclusion Check",
                releaseYear = 2024,
                purchasePrice = null,
                format = BookFormat.EBOOK,
                totalPages = null,
                isbn = null,
                externalIdentifiers = emptyList(),
            )
            assertIs<Resource.Success<String>>(addResult)

            val useCase = DefaultDatabaseBackupUseCase(liveDb, dbFile.absolutePath)
            val result = useCase.execute()
            assertIs<Resource.Success<BackupResult>>(result)
            // Only assigned once execute() has actually succeeded -- the finally block guards its
            // deletion with a null check, since a failed/never-reached execute() means there is no
            // staged file to delete.
            stagedFile = File(result.data.stagedFilePath)

            val backupBytes = stagedFile.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(
                !backupBytes.contains(logMarker),
                "backup bytes must never contain log content -- see DefaultDatabaseBackupUseCase's " +
                    "\"why there is no exclude filter\" KDoc section",
            )
            // Sanity check that the decoy file itself really holds the marker, so a typo in the
            // marker string above couldn't make this test pass for the wrong reason.
            assertTrue(decoyLogFile.readText().contains(logMarker))
        } finally {
            stagedFile?.delete()
            liveDb?.close()
            decoyLogFile.delete()
            logsDir.delete()
        }
    }

    /**
     * Regression guard, complementary to the byte-level check above: confirms the produced
     * backup's own SQLite schema (queried directly via [BundledSQLiteDriver], the same mechanism
     * [com.hub.media.core.database.validateStagedDatabaseIntegrity] uses for restore validation)
     * contains only tables [AppDatabase] actually defines -- none of them log-related -- and
     * genuinely finds real tables rather than an empty/broken connection (the
     * `media_items`/`book_details` assertion below is what keeps this from being a vacuous "empty
     * set contains no log tables" pass). This is what [DefaultDatabaseBackupUseCase]'s KDoc means
     * by "the log store must never become a Room entity/table": if it ever did, `VACUUM INTO`
     * would snapshot it exactly like every other table, and this test would immediately start
     * failing.
     */
    @Test
    fun execute_backupSchemaContainsNoLogRelatedTable() = runTest {
        val liveDb = openLiveDatabase()
        val bookRepository = BookRepository(liveDb)
        val addResult = bookRepository.addBook(
            title = "Schema Check",
            releaseYear = 2024,
            purchasePrice = null,
            format = BookFormat.EBOOK,
            totalPages = null,
            isbn = null,
            externalIdentifiers = emptyList(),
        )
        assertIs<Resource.Success<String>>(addResult)

        val useCase = DefaultDatabaseBackupUseCase(liveDb, dbFile.absolutePath)
        val result = useCase.execute()
        assertIs<Resource.Success<BackupResult>>(result)
        val stagedFile = File(result.data.stagedFilePath)
        liveDb.close()

        try {
            val tableNames = mutableSetOf<String>()
            BundledSQLiteDriver().open(stagedFile.absolutePath, SQLITE_OPEN_READONLY).use { connection ->
                connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { statement ->
                    while (statement.step()) tableNames += statement.getText(0)
                }
            }

            assertTrue(
                tableNames.containsAll(setOf("media_items", "book_details")),
                "expected to find this app's real tables in the backup -- if this fails, the rest " +
                    "of this test proves nothing (it would vacuously pass on an empty/broken result)",
            )
            // A blanket `contains("log", ignoreCase = true)` is too broad and forward-looking-
            // buggy: AGENTS.md §3.4 explicitly plans a WatchLogs table (Movies/TV activity
            // history, ROADMAP Task 13) -- following this codebase's existing tableName
            // convention (snake_case: "media_items", "book_details", "reading_sessions"), that
            // would be named "watch_logs" and would trip a bare "log" substring check the moment
            // it's added, even though it has nothing to do with this regression guard. The same
            // could happen to a future "reading_logs"-style rename. What this test actually cares
            // about (see this test's KDoc) is specifically whether the Task 15 LogEntry store got
            // turned into a Room table -- which, by that same naming convention, would be named
            // "log_entry" or "log_entries" -- but guessing the exact spelling would make this
            // guard miss "app_logs", "logs", or whatever else a future change actually picked.
            // So: still match "log" broadly, and subtract the domain tables that legitimately
            // carry the word. Anything new containing "log" then has to be added here
            // deliberately, which is precisely the moment someone should be asking whether it
            // belongs in a backed-up database at all.
            val legitimateActivityTables = setOf("watch_logs", "reading_logs")
            val logRelatedTables = tableNames.filter {
                it.contains("log", ignoreCase = true) && it.lowercase() !in legitimateActivityTables
            }
            assertTrue(
                logRelatedTables.isEmpty(),
                "backup schema must never contain a log-related table, found: $logRelatedTables",
            )
        } finally {
            stagedFile.delete()
        }
    }
}
