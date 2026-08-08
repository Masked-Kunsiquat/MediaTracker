package com.hub.media.features.portability.domain

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.execSQL
import com.hub.media.core.database.APP_DATABASE_VERSION
import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.AppDatabaseConstructor
import com.hub.media.core.database.RestoreMarker
import com.hub.media.core.database.SQLITE_HEADER_SIZE
import com.hub.media.core.database.buildAppDatabase
import com.hub.media.core.database.consumeRestoreMarker
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.selfHealDatabaseIfNeeded
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.RecordingLogger
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
import kotlin.test.assertFailsWith
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

    /**
     * Finding 2's exact scenario: [com.hub.media.core.database.validateStagedDatabaseIntegrity]
     * used to open the candidate with `BundledSQLiteDriver`'s default flags (`SQLITE_OPEN_READWRITE
     * or SQLITE_OPEN_CREATE`) -- so merely *validating* a picked file, before the user has
     * confirmed anything destructive, opened it with write access it never actually exercises
     * (only `PRAGMA integrity_check` and a `SELECT` against `sqlite_master` run, never a write).
     *
     * A behavioral, integration-level reproduction (holding the candidate's own OS file-permission
     * read-only, and separately confirming no `-wal`/`-shm` sidecar or byte-level change survives
     * validation across several realistic candidate shapes -- a checkpointed WAL-mode file, a
     * WAL-free `VACUUM INTO` backup, and a WAL-mode file with genuinely uncommitted `-wal` content)
     * was tried first and abandoned: on this JVM/`BundledSQLiteDriver` 2.7.0/Windows combination, a
     * connection that only ever issues `SELECT`/`PRAGMA integrity_check` (never an `INSERT`/
     * `UPDATE`/DDL statement) does not observably differ on disk between the two open flags for any
     * of those shapes -- confirmed empirically, not assumed. So this test instead verifies the one
     * guarantee [SQLITE_OPEN_READONLY] actually, unconditionally provides on every platform: **any**
     * attempted write through a connection opened with it is rejected outright by SQLite itself,
     * regardless of what the calling code goes on to do with the connection. That's exactly why the
     * fix closes Finding 2's hazard even though this specific function's current query pattern
     * happens not to trip it today: if a future edit to [com.hub.media.core.database
     * .validateStagedDatabaseIntegrity] ever added a write (accidentally or otherwise), the
     * `SQLITE_OPEN_READONLY` connection it already opens would make that write fail loudly and
     * immediately, rather than silently succeeding against a user's about-to-be-restored candidate
     * file the way the pre-fix `SQLITE_OPEN_READWRITE` open would have let it.
     */
    @Test
    fun sqliteOpenReadOnlyFlag_rejectsWrites_thatTheDefaultOpenFlagsWouldSilentlyAllow() = runTest {
        val candidatePath = path("read-only-flag-check.db")
        createFreshDatabaseFile(candidatePath)

        // The exact flag validateStagedDatabaseIntegrity now opens the candidate with: any write
        // attempt on this connection must be rejected outright, never silently applied.
        BundledSQLiteDriver().open(candidatePath, SQLITE_OPEN_READONLY).use { readOnlyConnection ->
            assertFailsWith<SQLiteException>(
                "SQLITE_OPEN_READONLY must make any write attempt fail outright -- if this " +
                    "connection can still write, opening the candidate with this flag protects " +
                    "nothing",
            ) {
                readOnlyConnection.execSQL("CREATE TABLE should_never_be_created (id INTEGER)")
            }
        }

        // Sanity check on the other side: the exact flags the pre-fix (buggy) code used really do
        // permit that same write -- so the assertion above is genuinely exercising the difference
        // this fix makes, not something SQLite would have refused either way.
        BundledSQLiteDriver().open(candidatePath).use { readWriteConnection ->
            readWriteConnection.execSQL("CREATE TABLE should_be_creatable_here (id INTEGER)")
        }
    }

    // ==========================================================================================
    // stage(): integrity validation beyond the 100-byte header (Finding 4)
    // ==========================================================================================

    /**
     * The core "100-byte header is too low a bar" regression test: a file whose first 100 bytes are
     * a completely legitimate MediaTracker header (real magic string, real current `user_version`)
     * but whose body was chopped off entirely. Header-only validation cannot tell this apart from a
     * genuinely intact database -- only actually opening it (this function's new second pass) can.
     */
    @Test
    fun stage_truncatedFileWithValidHeader_isRejectedDespitePassingHeaderCheck() = runTest {
        val candidatePath = path("truncated.db")
        createFreshDatabaseFile(candidatePath)
        val fullBytes = File(candidatePath).readBytes()
        assertTrue(
            fullBytes.size > SQLITE_HEADER_SIZE,
            "sanity: a real database file must be larger than just its own header, or this test " +
                "proves nothing",
        )
        // Keep the header completely intact (so the pre-existing magic-string/user_version check
        // still passes it) but discard everything after it -- simulating a backup file truncated by
        // an interrupted copy/upload.
        File(candidatePath).writeBytes(fullBytes.copyOf(SQLITE_HEADER_SIZE + 16))

        val result = DefaultRestoreDatabaseUseCase(path("live.db")).stage(candidatePath)

        assertIs<Resource.Error>(result)
        assertFalse(File(candidatePath).exists(), "a rejected candidate must be cleaned up, not left behind")
    }

    /**
     * A structurally valid, openable SQLite file that simply isn't a MediaTracker database at all
     * (unrelated table, `user_version` left at SQLite's own default of 0 -- which the pre-existing
     * header check alone would accept, since 0 <= APP_DATABASE_VERSION). Proves the new
     * expected-table check, not just the integrity check, actually runs: this file passes
     * `PRAGMA integrity_check` cleanly (it's a perfectly valid SQLite file) but must still be
     * refused because it has none of this app's tables.
     */
    @Test
    fun stage_validSqliteFileThatIsNotAMediaTrackerDatabase_isRejected() = runTest {
        val candidatePath = path("unrelated.db")
        BundledSQLiteDriver().open(candidatePath).use { connection ->
            connection.execSQL("CREATE TABLE some_other_apps_table (id INTEGER PRIMARY KEY, note TEXT)")
        }

        val result = DefaultRestoreDatabaseUseCase(path("live.db")).stage(candidatePath)

        assertIs<Resource.Error>(result)
        assertFalse(File(candidatePath).exists(), "a rejected candidate must be cleaned up, not left behind")
    }

    /**
     * Finding 3's exact scenario: the expected-table check used to *always* exclude
     * `app_settings` from the required set (so a genuinely older backup wouldn't be wrongly
     * rejected) -- but that meant a candidate that itself claims `user_version = 4` while
     * genuinely missing `app_settings` (corrupt, hand-crafted, or a botched hand-edit of a real
     * backup) sailed through undetected. Built by seeding a real v3-schema file (so
     * `app_settings` genuinely does not exist) and then hand-bumping only its `user_version` to 4
     * -- exactly what a file lying about its own schema version looks like on disk. The required
     * set must now be chosen from the candidate's own reported version, so this is rejected
     * before [DefaultRestoreDatabaseUseCase.commit] is ever reachable.
     */
    @Test
    fun stage_v4DatabaseMissingAppSettingsTable_isRejectedBeforeCommit() = runTest {
        migrationHelper.createDatabase(3).use { db ->
            db.execSQL("PRAGMA user_version = 4")
        }
        val candidatePath = tempDir.resolve("migration-seed.db").toString()

        val result = DefaultRestoreDatabaseUseCase(path("live.db")).stage(candidatePath)

        assertIs<Resource.Error>(result)
        assertTrue(
            result.message.contains("app_settings"),
            "the failure message should name the missing table -- got: ${result.message}",
        )
        assertFalse(File(candidatePath).exists(), "a rejected candidate must be cleaned up, not left behind")
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

    /**
     * Finding 1's exact scenario, reproduced deterministically: a live database's own `-wal` file
     * fails to move aside during [DefaultRestoreDatabaseUseCase.commit]'s swap. Forced by placing a
     * *directory* at the rename's target (`<live>.pre-restore-bak-wal`) rather than holding a file
     * lock open -- `Files.move` refuses to replace an existing directory with a file on every
     * platform, whereas a lock on the source file only reliably blocks a rename on Windows (on
     * Linux/macOS the rename typically still succeeds through an open handle), which would make this
     * test silently stop proving anything on those platforms.
     *
     * Before Finding 1's fix, the rollback taken here only ever restored the *main* database file --
     * it never moved an already-renamed sidecar back. Here the `-wal` rename is the one forced to
     * fail (so it never leaves `walPath`), but the `-shm` rename genuinely succeeds first (real
     * content actually lands at the backup path) -- exactly the "one sidecar moved, the other didn't"
     * split Finding 1 describes. The old rollback would restore the main file, report "Nothing was
     * changed," and leave the real `-shm` content stranded at the backup path with `shmPath` missing
     * entirely -- making that message a lie. The fixed rollback must put `-shm` back too before it's
     * allowed to say nothing changed.
     */
    @Test
    fun commit_walSidecarRenameFailure_abortsAndRestoresOriginal_neverReportingFalseSuccess() = runTest {
        val livePath = path("live.db")
        val walPath = "$livePath-wal"
        val shmPath = "$livePath-shm"
        File(livePath).writeText("ORIGINAL-MAIN-CONTENT")
        File(walPath).writeText("ORIGINAL-WAL-CONTENT-WITH-UNCHECKPOINTED-COMMITS")
        File(shmPath).writeText("ORIGINAL-SHM-CONTENT")

        val stagedPath = path("candidate.db")
        File(stagedPath).writeText("STAGED-REPLACEMENT-CONTENT")
        val staged = StagedRestoreInfo(
            stagedFilePath = stagedPath,
            schemaVersionFound = APP_DATABASE_VERSION,
            isOlderSchemaVersion = false,
        )

        // A directory sitting at the wal rename's target makes it fail on every platform -- the
        // shm rename (a different target, untouched) is left free to succeed normally.
        val backupWalPath = "$livePath.pre-restore-bak-wal"
        File(backupWalPath).mkdirs()
        val result = try {
            DefaultRestoreDatabaseUseCase(livePath).commit(staged)
        } finally {
            File(backupWalPath).delete()
        }

        assertIs<Resource.Error>(result)
        assertTrue(
            result.message.contains("Nothing was changed"),
            "the failure message must be truthful, not merely present -- got: ${result.message}",
        )
        assertEquals(
            "ORIGINAL-MAIN-CONTENT",
            File(livePath).readText(),
            "the live file must be exactly what it was -- never replaced when its WAL couldn't travel with it",
        )
        assertEquals(
            "ORIGINAL-WAL-CONTENT-WITH-UNCHECKPOINTED-COMMITS",
            File(walPath).readText(),
            "the original WAL (holding whatever commits weren't yet checkpointed) must still be right " +
                "next to the live file it belongs to, not stranded at the backup path or lost",
        )
        assertEquals(
            "ORIGINAL-SHM-CONTENT",
            File(shmPath).readText(),
            "Finding 1: the SHM sidecar DID successfully move aside to the backup path before the WAL " +
                "rename failed -- the rollback must move it back too, not just the main file, or " +
                "'Nothing was changed' would be a lie while it sat stranded at the backup path",
        )
        assertTrue(File(stagedPath).exists(), "the staged candidate must be untouched -- the swap never reached it")
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

    /**
     * A `-wal` rename can fail on a live process too, not only via a mid-syscall crash (e.g.
     * another handle transiently blocking it). Forced here by placing a *directory* at the
     * rename's target (`<live>-wal`) rather than holding a file lock open -- `Files.move` refuses
     * to replace an existing directory with a file on every platform, whereas a lock on the
     * backup's `-wal` file (the rename's *source*) only reliably blocks the rename on Windows (on
     * Linux/macOS the rename typically still succeeds through an open handle), which would make
     * this test silently stop proving anything on those platforms. The old ordering (main file
     * first, sidecars last, with return values never checked) would rename the main file into
     * place regardless, leaving the live file present -- satisfying [selfHealDatabaseIfNeeded]'s
     * own "already healed" sentinel -- while the backup `-wal` stays permanently stranded. The
     * fixed ordering must instead leave the live file missing (so a later retry can still succeed)
     * whenever a needed sidecar rename fails.
     */
    @Test
    fun selfHeal_walRenameFailure_neverLeavesLiveFilePresentWithoutIt() = runTest {
        val livePath = path("live.db")
        val backupPath = "$livePath.pre-restore-bak"
        val backupWalPath = "$backupPath-wal"
        val backupShmPath = "$backupPath-shm"
        File(backupPath).writeText("MAIN-CONTENT")
        File(backupWalPath).writeText("WAL-CONTENT-WITH-UNCHECKPOINTED-COMMITS")
        File(backupShmPath).writeText("SHM-CONTENT")

        // A directory sitting at the wal rename's target (livePath-wal) makes the rename fail on
        // every platform -- exactly one sidecar rename failing, live process, no crash involved.
        val walPath = "$livePath-wal"
        File(walPath).mkdirs()

        selfHealDatabaseIfNeeded(livePath)

        assertFalse(
            File(livePath).exists(),
            "the live file must NOT appear until its -wal sidecar successfully travels with it " +
                "-- otherwise the next launch would see 'live file present' and never look for " +
                "the stranded WAL again",
        )
        assertTrue(File(backupPath).exists(), "the main backup file must be untouched while the WAL move failed")
        assertTrue(File(backupWalPath).exists(), "the WAL must still be at the backup path (its rename failed)")

        // Directory cleared (as if the transient condition cleared) -- a subsequent call must
        // finish the job completely, with no data lost.
        File(walPath).delete()
        selfHealDatabaseIfNeeded(livePath)

        assertTrue(File(livePath).exists(), "retrying after the transient failure clears must complete the heal")
        assertEquals("WAL-CONTENT-WITH-UNCHECKPOINTED-COMMITS", File("$livePath-wal").readText())
        assertEquals("SHM-CONTENT", File("$livePath-shm").readText())
        assertFalse(File(backupPath).exists())
        assertFalse(File(backupWalPath).exists())
        assertFalse(File(backupShmPath).exists())
    }

    // ==========================================================================================
    // ROADMAP Task 15: rejection/failure branches must log, and never log book/library content.
    // ==========================================================================================

    /**
     * [DefaultRestoreDatabaseUseCase] takes a [com.hub.media.core.util.Logger] the same way
     * [OpenLibraryIsbnCoverProbe][com.hub.media.features.books.network.OpenLibraryIsbnCoverProbe]
     * does -- injected, defaulting to [com.hub.media.core.util.AppLogger] -- so tests inject a
     * [RecordingLogger] directly rather than touching the process-wide [com.hub.media.core.util.AppLogger]
     * singleton (unlike [com.hub.media.core.database.MigrationTest], which has no such injection
     * seam for a top-level `Migration` object).
     */
    @Test
    fun stage_notASqliteFile_logsWarningNamingWhyItWasRejected() = runTest {
        val candidatePath = path("not-a-db.txt")
        File(candidatePath).writeText("this is definitely not a sqlite database")
        val recorder = RecordingLogger()

        val result = DefaultRestoreDatabaseUseCase(path("live.db"), logger = recorder).stage(candidatePath)

        assertIs<Resource.Error>(result)
        val entry = recorder.entries.single()
        assertEquals(LogLevel.WARN, entry.level)
        assertEquals("RestoreDatabaseUseCase", entry.tag)
        assertTrue(entry.message.contains("not a SQLite database"))
    }

    @Test
    fun stage_schemaVersionNewerThanCurrent_logsWarningWithVersionNumbers() = runTest {
        val candidatePath = path("future.db")
        createFreshDatabaseFile(candidatePath)
        BundledSQLiteDriver().open(candidatePath).use { connection ->
            connection.execSQL("PRAGMA user_version = ${APP_DATABASE_VERSION + 1}")
        }
        val recorder = RecordingLogger()

        DefaultRestoreDatabaseUseCase(path("live.db"), logger = recorder).stage(candidatePath)

        val entry = recorder.entries.single()
        assertEquals(LogLevel.WARN, entry.level)
        assertTrue(entry.message.contains("${APP_DATABASE_VERSION + 1}"))
    }

    /**
     * Covers [com.hub.media.core.database.validateStagedDatabaseIntegrity]'s own logging (a
     * different file/tag from [DefaultRestoreDatabaseUseCase]'s), reached through
     * [DefaultRestoreDatabaseUseCase.stage] exactly as production code reaches it -- the `logger`
     * parameter [DefaultRestoreDatabaseUseCase.stage] passes through to it.
     */
    @Test
    fun stage_notAMediaTrackerDatabase_logsWarningNamingMissingTables() = runTest {
        val candidatePath = path("unrelated.db")
        BundledSQLiteDriver().open(candidatePath).use { connection ->
            connection.execSQL("CREATE TABLE some_other_apps_table (id INTEGER PRIMARY KEY, note TEXT)")
        }
        val recorder = RecordingLogger()

        val result = DefaultRestoreDatabaseUseCase(path("live.db"), logger = recorder).stage(candidatePath)

        assertIs<Resource.Error>(result)
        val entry = recorder.entries.single()
        assertEquals(LogLevel.WARN, entry.level)
        assertEquals("StagedDatabaseValidation", entry.tag)
        assertTrue(entry.message.contains("media_items"), "should name a missing required table")
    }

    /**
     * Reuses [commit_walSidecarRenameFailure_abortsAndRestoresOriginal_neverReportingFalseSuccess]'s
     * exact forced-failure setup (a directory sitting at the WAL rename's backup target), this time
     * asserting on logging: the failure must be recorded at ERROR, the rollback outcome must be
     * discoverable from the log (not just the returned message), and -- the identifier-rule half of
     * this task's requirement -- the log message must never echo the sidecar files' actual byte
     * content, only fixed diagnostic text and already-user-visible paths.
     */
    @Test
    fun commit_walSidecarRenameFailure_logsErrorWithoutEchoingFileContent() = runTest {
        val livePath = path("live.db")
        val walPath = "$livePath-wal"
        val shmPath = "$livePath-shm"
        File(livePath).writeText("ORIGINAL-MAIN-CONTENT")
        File(walPath).writeText("ORIGINAL-WAL-CONTENT-WITH-UNCHECKPOINTED-COMMITS")
        File(shmPath).writeText("ORIGINAL-SHM-CONTENT")

        val stagedPath = path("candidate.db")
        File(stagedPath).writeText("STAGED-REPLACEMENT-CONTENT")
        val staged = StagedRestoreInfo(
            stagedFilePath = stagedPath,
            schemaVersionFound = APP_DATABASE_VERSION,
            isOlderSchemaVersion = false,
        )

        val backupWalPath = "$livePath.pre-restore-bak-wal"
        File(backupWalPath).mkdirs()
        val recorder = RecordingLogger()
        val result = try {
            DefaultRestoreDatabaseUseCase(livePath, logger = recorder).commit(staged)
        } finally {
            File(backupWalPath).delete()
        }

        assertIs<Resource.Error>(result)
        val errorEntries = recorder.entries.filter { it.level == LogLevel.ERROR }
        assertTrue(errorEntries.isNotEmpty(), "a swap failure must be logged at ERROR")
        assertTrue(errorEntries.any { it.tag == "RestoreDatabaseUseCase" })
        assertTrue(
            errorEntries.none {
                it.message.contains("ORIGINAL-WAL-CONTENT") ||
                    it.message.contains("ORIGINAL-MAIN-CONTENT") ||
                    it.message.contains("STAGED-REPLACEMENT-CONTENT")
            },
            "the log message must never echo file content -- only fixed diagnostic text and paths",
        )
    }
}
