package com.hub.media.features.books.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.insertTestBook
import com.hub.media.core.database.sampleReadingSession
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/** Re-reads, removing a read, rating a read, and a book's status living on its latest read (#230). */
class ReadThroughRepositoryTest {
    private lateinit var db: AppDatabase
    private val now = Instant.fromEpochMilliseconds(1_800_000_000_000)
    private val clock =
        object : Clock {
            override fun now() = this@ReadThroughRepositoryTest.now
        }
    private lateinit var reads: ReadThroughRepository
    private lateinit var books: BookRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        reads = ReadThroughRepository(db, clock)
        books = BookRepository(db, clock)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun readAgain_afterFinishing_startsReadTwoAtReading() =
        runTest {
            db.insertTestBook("b", status = ReadingStatus.FINISHED, finishedAt = Instant.fromEpochMilliseconds(1))

            assertIs<Resource.Success<String>>(reads.readAgain("b"))

            val all = reads.observeReads("b").first()
            assertEquals(
                listOf(1 to ReadingStatus.FINISHED, 2 to ReadingStatus.READING),
                all.map {
                    it.number to
                        it.status
                },
            )
            assertEquals(now, all[1].startedAt)
            assertEquals(Instant.fromEpochMilliseconds(1), all[0].finishedAt, "read #1 keeps its finish date")
        }

    @Test
    fun readAgain_whileReading_isRefused() =
        runTest {
            db.insertTestBook("b", status = ReadingStatus.READING)

            assertIs<Resource.Error>(reads.readAgain("b"))
            assertEquals(1, reads.observeReads("b").first().size)
        }

    /** Review Focus 2: a double tap makes one read, not two, and does not crash. */
    @Test
    fun readAgain_twiceConcurrently_createsOneRead() =
        runBlocking {
            db.insertTestBook("b", status = ReadingStatus.DNF)

            val results =
                withContext(Dispatchers.Default) {
                    listOf(async { reads.readAgain("b") }, async { reads.readAgain("b") }).awaitAll()
                }

            assertEquals(1, results.count { it is Resource.Success })
            assertEquals(2, reads.observeReads("b").first().size)
        }

    @Test
    fun removeRead_emptyReread_returnsTheBookToItsPreviousRead() =
        runTest {
            db.insertTestBook("b", status = ReadingStatus.FINISHED)
            val two = (reads.readAgain("b") as Resource.Success).data

            assertIs<Resource.Success<Unit>>(reads.removeRead(two))

            assertEquals(ReadingStatus.FINISHED, (books.getBookWithDetails("b") as Resource.Success).data!!.status)
        }

    @Test
    fun removeRead_refusesReadOne_andAReadWithSessions() =
        runTest {
            val one = db.insertTestBook("b", status = ReadingStatus.FINISHED)
            assertIs<Resource.Error>(reads.removeRead(one))

            val two = (reads.readAgain("b") as Resource.Success).data
            db.readingSessionDao().insertIntoLatestRead(sampleReadingSession(mediaId = "b"))

            assertIs<Resource.Error>(reads.removeRead(two))
            assertEquals(2, reads.observeReads("b").first().size)
        }

    @Test
    fun setRating_storesClearsAndRejectsOffGrid() =
        runTest {
            val one = db.insertTestBook("b", status = ReadingStatus.FINISHED)

            assertIs<Resource.Success<Unit>>(reads.setRating(one, 9))
            assertEquals(9, db.readThroughDao().getById(one)!!.rating)
            assertIs<Resource.Success<Unit>>(reads.setRating(one, null))
            assertNull(db.readThroughDao().getById(one)!!.rating)
            assertIs<Resource.Error>(reads.setRating(one, 11))
            assertIs<Resource.Error>(reads.setRating(one, 0))
            assertNull(db.readThroughDao().getById(one)!!.rating, "a refused rating writes nothing")
        }

    @Test
    fun updateReadingStatus_writesTheLatestRead_withFinishedAtRules() =
        runTest {
            db.insertTestBook("b", status = ReadingStatus.FINISHED, finishedAt = Instant.fromEpochMilliseconds(1))
            reads.readAgain("b")

            books.updateReadingStatus("b", ReadingStatus.FINISHED)

            val all = reads.observeReads("b").first()
            assertEquals(Instant.fromEpochMilliseconds(1), all[0].finishedAt)
            assertEquals(now, all[1].finishedAt)
            assertEquals(ReadingStatus.FINISHED, all[1].status)
        }

    /** Review Focus 3: only read #1 may be To read. */
    @Test
    fun updateReadingStatus_toReadOnRereadIsRefused() =
        runTest {
            db.insertTestBook("b", status = ReadingStatus.FINISHED)
            reads.readAgain("b")

            assertIs<Resource.Error>(books.updateReadingStatus("b", ReadingStatus.TO_READ))
            assertEquals(
                ReadingStatus.READING,
                reads
                    .observeReads("b")
                    .first()
                    .last()
                    .status,
            )
        }

    @Test
    fun aBooksStatus_isItsLatestReads() =
        runTest {
            db.insertTestBook("b", status = ReadingStatus.FINISHED)
            reads.readAgain("b")

            val book = books.observeBookDetail("b").first()!!

            assertEquals(ReadingStatus.READING, book.status)
            assertEquals(2, book.reads.size)
            assertEquals(
                ReadingStatus.READING,
                books
                    .observeAllBooksWithDetails()
                    .first()
                    .single()
                    .status,
            )
        }
}
