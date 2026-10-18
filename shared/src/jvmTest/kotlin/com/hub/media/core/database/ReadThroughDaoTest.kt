package com.hub.media.core.database

import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The reads table (#230): every write path gives a book read #1, and sessions join the latest read. */
class ReadThroughDaoTest {
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
    fun addBook_createsReadOne_withTheBooksStatus() =
        runTest {
            val result = BookRepository(db).addBook(title = "Dune", format = BookFormat.PHYSICAL, status = ReadingStatus.READING)
            val mediaId = (result as Resource.Success).data

            val reads = db.readThroughDao().observeForMedia(mediaId).first()

            assertEquals(listOf(1 to ReadingStatus.READING), reads.map { it.number to it.status })
        }

    @Test
    fun aSecondReadWithTheSameNumber_isRejected() =
        runTest {
            db.insertTestBook("b1")

            assertFailsWith<Exception> {
                db.readThroughDao().insert(ReadThroughEntity(newId(), "b1", 1, ReadingStatus.READING))
            }
        }

    @Test
    fun aLoggedSession_joinsTheLatestRead() =
        runTest {
            val readOne = db.insertTestBook("b1", status = ReadingStatus.FINISHED)
            val readTwo = newId()
            db.readThroughDao().insert(ReadThroughEntity(readTwo, "b1", 2, ReadingStatus.READING))

            db.readingSessionDao().insertIntoLatestRead(sampleReadingSession(mediaId = "b1"))

            assertEquals(1, db.readThroughDao().sessionCount(readTwo))
            assertEquals(0, db.readThroughDao().sessionCount(readOne))
        }

    @Test
    fun deletingTheBook_deletesItsReadsAndSessions() =
        runTest {
            val readOne = db.insertTestBook("b1")
            db.readingSessionDao().insertIntoLatestRead(sampleReadingSession(mediaId = "b1"))

            db.mediaItemDao().deleteById("b1")

            assertNull(db.readThroughDao().getById(readOne))
            assertEquals(0, db.readThroughDao().sessionCount(readOne))
        }
}
