package com.hub.media.core.database

import com.hub.media.core.database.dao.ImportBookInsert
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

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
    fun importAtomically_allValidInserts_appliesEveryRow() = runTest {
        val inserts = (1..3).map { i ->
            val mediaId = "media-$i"
            ImportBookInsert(
                mediaItem = sampleMediaItem(id = mediaId, title = "Book $i"),
                details = sampleBookDetails(mediaId = mediaId),
                identifiers = emptyList(),
            )
        }

        db.importWriteDao().importAtomically(inserts, emptyList(), emptyList(), emptyList())

        assertEquals(3, db.mediaItemDao().observeAll().first().size)
    }

    @Test
    fun importAtomically_constraintViolationPartway_rollsBackEveryEarlierInsertInTheSameCall() = runTest {
        // Pre-existing row #2 (already in the database before this import call) collides with one
        // of the batch's own inserts -- OnConflictStrategy.ABORT on insertMediaItem makes this
        // throw a genuine SQLite constraint exception once importAtomically reaches it.
        val preExisting = sampleMediaItem(id = "media-2", title = "Already here")
        db.mediaItemDao().insert(preExisting)
        db.bookDetailsDao().insert(sampleBookDetails(mediaId = "media-2"))

        val inserts = listOf(
            ImportBookInsert(
                mediaItem = sampleMediaItem(id = "media-1", title = "Book 1"),
                details = sampleBookDetails(mediaId = "media-1"),
                identifiers = emptyList(),
            ),
            ImportBookInsert(
                // Duplicate primary key against the pre-existing row -- forces a mid-batch failure.
                mediaItem = sampleMediaItem(id = "media-2", title = "Colliding book"),
                details = sampleBookDetails(mediaId = "media-2"),
                identifiers = emptyList(),
            ),
            ImportBookInsert(
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
}
