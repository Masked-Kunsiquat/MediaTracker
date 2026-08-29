package com.hub.media.core.database

import com.hub.media.core.database.dao.ImportMediaInsert
import com.hub.media.core.database.dao.ImportMediaUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests [com.hub.media.core.database.dao.ImportWriteDao.importAtomically]'s all-or-nothing
 * transaction guarantee directly at the DAO level (ROADMAP Task 8 Phase B) -- the exact rollback
 * verification this phase's brief calls for: force a failure partway through a multi-row
 * `importAtomically` call and assert that zero rows from that call survive, mirroring
 * `BookRepositoryTest.addBook_duplicateProviderPair_rollsBackWholeTransaction`'s equivalent
 * coverage of [com.hub.media.core.database.dao.BookWriteDao.insertBookAtomically] for a single
 * row, scaled to many rows in one call.
 */
class ImportWriteDaoTest {
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun importAtomically_allValidInserts_appliesEveryRow() =
        runTest {
            val inserts =
                (1..3).map { i ->
                    val mediaId = "media-$i"
                    ImportMediaInsert(
                        mediaItem = sampleMediaItem(id = mediaId, title = "Book $i"),
                        details = sampleBookDetails(mediaId = mediaId),
                        identifiers = emptyList(),
                    )
                }

            db.importWriteDao().importAtomically(inserts, emptyList(), emptyList(), emptyList())

            assertEquals(
                3,
                db
                    .mediaItemDao()
                    .observeAll()
                    .first()
                    .size,
            )
        }

    @Test
    fun importAtomically_constraintViolationPartway_rollsBackEveryEarlierInsertInTheSameCall() =
        runTest {
            // Pre-existing row #2 (already in the database before this import call) collides with one
            // of the batch's own inserts -- OnConflictStrategy.ABORT on insertMediaItem makes this
            // throw a genuine SQLite constraint exception once importAtomically reaches it.
            val preExisting = sampleMediaItem(id = "media-2", title = "Already here")
            db.mediaItemDao().insert(preExisting)
            db.bookDetailsDao().insert(sampleBookDetails(mediaId = "media-2"))

            val inserts =
                listOf(
                    ImportMediaInsert(
                        mediaItem = sampleMediaItem(id = "media-1", title = "Book 1"),
                        details = sampleBookDetails(mediaId = "media-1"),
                        identifiers = emptyList(),
                    ),
                    ImportMediaInsert(
                        // Duplicate primary key against the pre-existing row -- forces a mid-batch failure.
                        mediaItem = sampleMediaItem(id = "media-2", title = "Colliding book"),
                        details = sampleBookDetails(mediaId = "media-2"),
                        identifiers = emptyList(),
                    ),
                    ImportMediaInsert(
                        mediaItem = sampleMediaItem(id = "media-3", title = "Book 3"),
                        details = sampleBookDetails(mediaId = "media-3"),
                        identifiers = emptyList(),
                    ),
                )

            assertFailsWith<Exception> {
                db.importWriteDao().importAtomically(inserts, emptyList(), emptyList(), emptyList())
            }

            // "media-1" was inserted successfully before the exception on "media-2" -- it must NOT
            // survive: the whole transaction rolled back, not just the statement that actually threw.
            assertNull(db.mediaItemDao().getById("media-1"))
            // "media-3" (queued after the failure) was of course never applied either.
            assertNull(db.mediaItemDao().getById("media-3"))
            // The pre-existing row is untouched -- exactly one row (the one that was already there
            // before this call) remains.
            assertEquals(listOf(preExisting), db.mediaItemDao().observeAll().first())
        }

    @Test
    fun importAtomically_bookUpdateTargetingMissingMediaItem_throwsAndRollsBackWholeCall() =
        runTest {
            // Simulates the PR review's Finding 2: the update's target media item is not in the
            // database at transaction time -- e.g. it was deleted by another writer in the window
            // between ImportDataUseCase reading its pre-write duplicate-resolution snapshot and this
            // transaction actually running. Room's generated @Update silently no-ops (0 rows affected)
            // rather than throwing in that case, which -- unless explicitly checked -- would let this
            // method return normally and ImportDataUseCase's summary over-report the row as "updated"
            // when nothing was actually written.
            val insert =
                ImportMediaInsert(
                    mediaItem = sampleMediaItem(id = "media-1", title = "Fresh Book"),
                    details = sampleBookDetails(mediaId = "media-1"),
                    identifiers = emptyList(),
                )
            val updateForMissingBook =
                ImportMediaUpdate(
                    mediaItem = sampleMediaItem(id = "media-missing", title = "Ghost"),
                    details = sampleBookDetails(mediaId = "media-missing"),
                    identifiers = emptyList(),
                    replaceIdentifiers = false,
                )

            assertFailsWith<Exception> {
                db.importWriteDao().importAtomically(
                    listOf(insert),
                    listOf(updateForMissingBook),
                    emptyList(),
                    emptyList(),
                )
            }

            // The insert queued earlier in the same call must NOT survive -- the whole transaction
            // rolled back, exactly like the constraint-violation case above.
            assertNull(db.mediaItemDao().getById("media-1"))
        }

    @Test
    fun importAtomically_sessionUpdateTargetingMissingSession_throwsAndRollsBackWholeCall() =
        runTest {
            // Same race as above, for a reading-session update rather than a book update.
            val bookInsert =
                ImportMediaInsert(
                    mediaItem = sampleMediaItem(id = "media-1", title = "Fresh Book"),
                    details = sampleBookDetails(mediaId = "media-1"),
                    identifiers = emptyList(),
                )
            val updateForMissingSession = sampleReadingSession(mediaId = "media-1", id = "session-missing")

            assertFailsWith<Exception> {
                db.importWriteDao().importAtomically(
                    listOf(bookInsert),
                    emptyList(),
                    emptyList(),
                    listOf(updateForMissingSession),
                )
            }

            // The book insert queued earlier in the same call must NOT survive either.
            assertNull(db.mediaItemDao().getById("media-1"))
        }
}
