package com.hub.media.features.portability.domain

import androidx.room.Room
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
}
