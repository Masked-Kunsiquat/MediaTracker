package com.hub.media.features.portability.domain

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.hub.media.core.database.APP_DATABASE_VERSION
import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.AppDatabaseConstructor
import com.hub.media.core.database.RestoreMarker
import com.hub.media.core.database.buildAppDatabase
import com.hub.media.core.database.consumeRestoreMarker
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.selfHealDatabaseIfNeeded
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule

/**
 * Integration tests for [DefaultRestoreDatabaseUseCase] against real, file-backed databases
 * (ROADMAP Task 8 Phase C) -- the most dangerous action in the app (AGENTS.md §1), so this is the
 * most thoroughly tested. Lives in `com.hub.media.features.portability.domain`, already excluded
 * from the android unit-test variant by the package-wide filter in `shared/build.gradle.kts`;
 * `:shared:jvmTest` is the authoritative gate.
 *
 * Covers every task-brief deliverable: header validation rejects a non-SQLite file, the version
 * check rejects a too-new `user_version` and accepts an older one (with a real subsequent Room
 * migration proving "restore an older backup, then Room migrates it forward" genuinely works, not
 * just that the byte-level check passes), a full backup-then-restore-elsewhere round trip with
 * field-for-field data comparison, and a forced failure path proving the original database is
 * never left missing or corrupted.
 */
class RestoreDatabaseUseCaseTest {

    private val tempDir: Path = Files.createTempDirectory("restore-test")

    @get:Rule
    val migrationHelper: MigrationTestHelper = MigrationTestHelper(
        schemaDirectoryPath = Path.of("schemas"),
        databasePath = tempDir.resolve("migration-seed.db"),
        driver = BundledSQLiteDriver(),
        databaseClass = AppDatabase::class,
        databaseFactory = { AppDatabaseConstructor.initialize() },
    )

    @AfterTest
    fun tearDown() {
        Files.list(tempDir).use { paths -> paths.forEach { if (it.exists()) it.deleteIfExists() } }
        if (tempDir.exists()) tempDir.deleteExisting()
    }

    private fun path(name: String): String = tempDir.resolve(name).toString()

    private fun openDatabase(path: String): AppDatabase =
        buildAppDatabase(Room.databaseBuilder<AppDatabase>(name = path, factory = AppDatabaseConstructor::initialize))

    /**
     * Room KMP opens its underlying connection (and runs `createAllTables`/`PRAGMA user_version`)
     * lazily, on first real use -- not synchronously inside [Room.databaseBuilder]'s `build()]`.
     * A plain `openDatabase(path).close()` with no query in between can therefore close the
     * database before the file (or its tables/`user_version`) was ever actually written to disk.
     * Every test below that needs a real, fully-initialized file at [path] with no data of its own
     * goes through this helper instead, which forces that initialization with a trivial read.
     */
    private suspend fun createFreshDatabaseFile(path: String) {
        val db = openDatabase(path)
        db.mediaItemDao().observeAll().first()
        db.close()
    }

    // ==========================================================================================
    // stage(): header validation
    // ==========================================================================================

    @Test
    fun stage_notASqliteFile_isRejectedAndFileIsDeleted() = runTest {
        val candidatePath = path("not-a-db.txt")
        File(candidatePath).writeText("this is definitely not a sqlite database")

        val useCase = DefaultRestoreDatabaseUseCase(path("live.db"))
        val result = useCase.stage(candidatePath)

        assertIs<Resource.Error>(result)
        assertFalse(File(candidatePath).exists(), "a rejected candidate must be cleaned up, not left behind")
    }

    @Test
    fun stage_emptyFile_isRejected() = runTest {
        val candidatePath = path("empty.db")
        File(candidatePath).writeBytes(ByteArray(0))

        val result = DefaultRestoreDatabaseUseCase(path("live.db")).stage(candidatePath)
        assertIs<Resource.Error>(result)
    }

    @Test
    fun stage_missingFile_isRejected() = runTest {
        val result = DefaultRestoreDatabaseUseCase(path("live.db")).stage(path("does-not-exist.db"))
        assertIs<Resource.Error>(result)
    }

    @Test
    fun stage_schemaVersionNewerThanCurrent_isRejectedAndFileIsDeleted() = runTest {
        // Build a real, valid, current-schema database, then bump its stored user_version past
        // what this build understands -- simulating a backup taken by a future app version.
        val candidatePath = path("future.db")
        createFreshDatabaseFile(candidatePath)
        BundledSQLiteDriver().open(candidatePath).use { connection ->
            connection.execSQL("PRAGMA user_version = ${APP_DATABASE_VERSION + 1}")
        }

        val result = DefaultRestoreDatabaseUseCase(path("live.db")).stage(candidatePath)

        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("newer"), "message should explain the file is from a newer app version")
        assertFalse(File(candidatePath).exists(), "a rejected too-new candidate must be cleaned up")
    }

    @Test
    fun stage_currentSchemaVersion_isAccepted_andNotFlaggedAsOlder() = runTest {
        val candidatePath = path("current.db")
        createFreshDatabaseFile(candidatePath)

        val result = DefaultRestoreDatabaseUseCase(path("live.db")).stage(candidatePath)

        assertIs<Resource.Success<StagedRestoreInfo>>(result)
        assertEquals(APP_DATABASE_VERSION, result.data.schemaVersionFound)
        assertFalse(result.data.isOlderSchemaVersion)
        assertTrue(File(candidatePath).exists(), "an accepted candidate must not be deleted -- it's staged for commit")
    }

    @Test
    fun stage_olderSchemaVersion_isAcceptedAndFlaggedAsOlder() = runTest {
        migrationHelper.createDatabase(2).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-1', 'BOOK', 'Old Backup Book', 2015, 4.99, 1600000000000, NULL)",
            )
        }
        val candidatePath = tempDir.resolve("migration-seed.db").toString()

        val result = DefaultRestoreDatabaseUseCase(path("live.db")).stage(candidatePath)

        assertIs<Resource.Success<StagedRestoreInfo>>(result)
        assertEquals(2, result.data.schemaVersionFound)
        assertTrue(result.data.isOlderSchemaVersion)
    }

    // ==========================================================================================
    // commit(): the swap, and Room's normal migration path taking over afterward
    // ==========================================================================================

    @Test
    fun commit_olderSchemaVersion_swapsIn_andNextOpenMigratesForwardWithDataIntact() = runTest {
        // Seed a v2-schema file (pre-dating book_details.status/finishedAt/trackingMode and the
        // app_settings table) with one book and one reading session -- exactly the kind of file a
        // genuinely old backup would be.
        migrationHelper.createDatabase(2).use { db ->
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                    "VALUES ('media-1', 'BOOK', 'Migrated Restore Book', 2015, 4.99, 1600000000000, NULL)",
            )
            db.execSQL(
                "INSERT INTO book_details (mediaId, isbn, format, totalPages) " +
                    "VALUES ('media-1', '9780000000000', 'PHYSICAL', 300)",
            )
            db.execSQL(
                "INSERT INTO reading_sessions " +
                    "(id, mediaId, timestampStart, timestampEnd, durationSeconds, startUnit, endUnit, deltaPages, notes) " +
                    "VALUES ('session-1', 'media-1', 1700000000000, 1700000600000, 600, 0.0, 42.0, 42, NULL)",
            )
        }
        val candidatePath = tempDir.resolve("migration-seed.db").toString()
        val livePath = path("live.db")

        val useCase = DefaultRestoreDatabaseUseCase(livePath)
        val staged = assertIs<Resource.Success<StagedRestoreInfo>>(useCase.stage(candidatePath)).data
        val commitResult = useCase.commit(staged)
        assertIs<Resource.Success<Unit>>(commitResult)

        // The next open is the *exact same call path* every ordinary app launch already uses --
        // no restore-aware code needed here at all, only the normal registered migration chain.
        val reopened = openDatabase(livePath)
        try {
            val book = reopened.mediaItemDao().observeById("media-1").first()
            assertNotNull(book, "the restored (and now-migrated) book must still be present")
            assertEquals("Migrated Restore Book", book.title)

            val details = reopened.bookDetailsDao().observeByMediaId("media-1").first()
            assertNotNull(details)
            assertEquals("PAGES", details.trackingMode.name, "v3/v4 migration derivation must have run")

            val sessions = reopened.readingSessionDao().observeSessionsForMedia("media-1").first()
            assertEquals(1, sessions.size)
            assertEquals(600L, sessions.single().durationSeconds)
        } finally {
            reopened.close()
        }

        assertEquals(RestoreMarker.Success, consumeRestoreMarker(livePath))
    }

    @Test
    fun commit_roundTrip_backupThenRestoreElsewhere_dataMatchesFieldForField() = runTest {
        val originalLivePath = path("original.db")
        val originalDb = openDatabase(originalLivePath)
        val bookRepository = BookRepository(originalDb)
        val addResult = bookRepository.addBook(
            title = "Round Trip Book",
            releaseYear = 2019,
            purchasePrice = 12.5,
            format = BookFormat.HARDCOVER,
            totalPages = 410,
            isbn = "9781111111111",
            externalIdentifiers = emptyList(),
        )
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val backupResult = DefaultDatabaseBackupUseCase(originalDb, originalLivePath).execute()
        val backup = assertIs<Resource.Success<BackupResult>>(backupResult).data
        originalDb.close()

        val newLivePath = path("new-live.db")
        val restoreUseCase = DefaultRestoreDatabaseUseCase(newLivePath)
        val staged = assertIs<Resource.Success<StagedRestoreInfo>>(restoreUseCase.stage(backup.stagedFilePath)).data
        assertEquals(APP_DATABASE_VERSION, staged.schemaVersionFound)
        assertFalse(staged.isOlderSchemaVersion)
        assertIs<Resource.Success<Unit>>(restoreUseCase.commit(staged))

        val restoredDb = openDatabase(newLivePath)
        try {
            val restoredBook = restoredDb.mediaItemDao().observeById(mediaId).first()
            assertNotNull(restoredBook)
            assertEquals("Round Trip Book", restoredBook.title)
            assertEquals(2019, restoredBook.releaseYear)
            assertEquals(12.5, restoredBook.purchasePrice)

            val restoredDetails = restoredDb.bookDetailsDao().observeByMediaId(mediaId).first()
            assertNotNull(restoredDetails)
            assertEquals("9781111111111", restoredDetails.isbn)
            assertEquals(410, restoredDetails.totalPages)
            assertEquals(BookFormat.HARDCOVER, restoredDetails.format)
        } finally {
            restoredDb.close()
        }
    }

    // ==========================================================================================
    // Failure path: the original must never be left missing or corrupted.
    // ==========================================================================================

    @Test
    fun commit_stagedFileMissing_failsCleanly_andOriginalLibraryIsRestoredAndOpenable() = runTest {
        val livePath = path("live.db")
        val liveDb = openDatabase(livePath)
        val bookRepository = BookRepository(liveDb)
        val addResult = bookRepository.addBook(
            title = "Must Survive A Failed Restore",
            releaseYear = 2022,
            purchasePrice = null,
            format = BookFormat.EBOOK,
            totalPages = null,
            isbn = null,
            externalIdentifiers = emptyList(),
        )
        assertIs<Resource.Success<String>>(addResult)
        liveDb.close() // AppContainer.close() would have already happened by this point in production.

        val bogusStaged = StagedRestoreInfo(
            stagedFilePath = path("this-file-was-never-created.db"),
            schemaVersionFound = APP_DATABASE_VERSION,
            isOlderSchemaVersion = false,
        )
        val result = DefaultRestoreDatabaseUseCase(livePath).commit(bogusStaged)

        assertIs<Resource.Error>(result)
        assertTrue(File(livePath).exists(), "the live database file must still exist after a failed restore")

        val reopened = openDatabase(livePath)
        try {
            val books = reopened.mediaItemDao().observeAll().first()
            assertEquals(1, books.size)
            assertEquals("Must Survive A Failed Restore", books.single().title)
        } finally {
            reopened.close()
        }

        val marker = consumeRestoreMarker(livePath)
        assertIs<RestoreMarker.Failure>(marker)
    }

    @Test
    fun commit_noLiveDatabaseExistedYet_stillSwapsInSuccessfully() = runTest {
        // A fresh install restoring a backup before any local data was ever created.
        val candidatePath = path("candidate.db")
        createFreshDatabaseFile(candidatePath)
        val livePath = path("live.db")
        assertFalse(File(livePath).exists())

        val useCase = DefaultRestoreDatabaseUseCase(livePath)
        val staged = assertIs<Resource.Success<StagedRestoreInfo>>(useCase.stage(candidatePath)).data
        assertIs<Resource.Success<Unit>>(useCase.commit(staged))

        assertTrue(File(livePath).exists())
    }

    // ==========================================================================================
    // Startup self-healing (core.database.selfHealDatabaseIfNeeded)
    // ==========================================================================================

    @Test
    fun selfHeal_liveFilePresent_isANoOp() = runTest {
        val livePath = path("live.db")
        createFreshDatabaseFile(livePath)
        selfHealDatabaseIfNeeded(livePath)
        assertTrue(File(livePath).exists())
        assertFalse(File("$livePath.pre-restore-bak").exists())
    }

    @Test
    fun selfHeal_liveFileMissingButBackupExists_restoresFromBackup() = runTest {
        val livePath = path("live.db")
        val liveDb = openDatabase(livePath)
        val bookRepository = BookRepository(liveDb)
        assertIs<Resource.Success<String>>(
            bookRepository.addBook(
                title = "Recovered By Self-Heal",
                releaseYear = 2021,
                purchasePrice = null,
                format = BookFormat.EBOOK,
                totalPages = null,
                isbn = null,
                externalIdentifiers = emptyList(),
            ),
        )
        liveDb.close()

        // Simulate exactly the dangerous window commit()'s swap can leave behind: the live file
        // renamed aside, the replacement never having arrived.
        File(livePath).renameTo(File("$livePath.pre-restore-bak"))
        assertFalse(File(livePath).exists())

        selfHealDatabaseIfNeeded(livePath)

        assertTrue(File(livePath).exists(), "self-heal must restore the live file from the backup")
        val reopened = openDatabase(livePath)
        try {
            val books = reopened.mediaItemDao().observeAll().first()
            assertEquals("Recovered By Self-Heal", books.single().title)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun selfHeal_neitherLiveNorBackupExists_isANoOp() = runTest {
        val livePath = path("live.db")
        selfHealDatabaseIfNeeded(livePath)
        assertFalse(File(livePath).exists())
    }
}
