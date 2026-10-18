package com.hub.media.core.database

import com.hub.media.core.database.dao.FinishOutcomes
import com.hub.media.core.database.dao.FinishedBookRow
import com.hub.media.core.database.dao.TimedSessionRow
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.util.newId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The two reads behind Books stats part 2 (#219): the period's timed sessions with each book's
 * format and authors (one read for all three breakdowns), and the finished and DNF counts.
 */
class TimedSessionsDaoTest {
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun at(epochSeconds: Long) = Instant.fromEpochSeconds(epochSeconds)

    private suspend fun book(
        id: String,
        format: BookFormat = BookFormat.PAPERBACK,
        authors: String? = "Ursula K. Le Guin",
        status: ReadingStatus = ReadingStatus.READING,
    ) {
        db.mediaItemDao().insert(sampleMediaItem(id = id))
        db.bookDetailsDao().insert(
            sampleBookDetails(mediaId = id, format = format).copy(authors = authors),
        )
        db.readThroughDao().insert(ReadThroughEntity(newId(), id, 1, status))
    }

    private suspend fun session(
        mediaId: String,
        start: Long,
        durationSeconds: Long?,
    ) {
        db.readingSessionDao().insertIntoLatestRead(
            sampleReadingSession(mediaId = mediaId, timestampStart = at(start), durationSeconds = durationSeconds),
        )
    }

    private suspend fun timed(
        from: Long?,
        to: Long?,
    ) = db.statsDao().observeTimedSessions(from?.let(::at), to?.let(::at)).first()

    @Test
    fun keepsTimedSessionsInsideTheBounds_withTheirBooksFormatAndAuthors() =
        runTest {
            book("b1", format = BookFormat.EBOOK, authors = "A; B")
            session("b1", start = 999, durationSeconds = 300) // before: out
            session("b1", start = 1_000, durationSeconds = 600) // first instant: in
            session("b1", start = 2_000, durationSeconds = 900) // the end is exclusive: out

            assertEquals(
                // The book's id and title too (#221): the year in review names the longest session's book.
                listOf(TimedSessionRow(at(1_000), 600, BookFormat.EBOOK, "A; B", mediaId = "b1", title = "Test Book")),
                timed(1_000, 2_000),
            )
        }

    @Test
    fun aMissingOrZeroDuration_isLeftOut() =
        runTest {
            book("b1")
            session("b1", start = 1_100, durationSeconds = null)
            session("b1", start = 1_200, durationSeconds = 0)
            session("b1", start = 1_300, durationSeconds = 60) // positive control

            assertEquals(listOf(60L), timed(1_000, 2_000).map { it.durationSeconds })
        }

    @Test
    fun allTime_hasNoBounds() =
        runTest {
            book("b1", authors = null)
            session("b1", start = 10, durationSeconds = 60)
            session("b1", start = 9_000_000, durationSeconds = 120)

            val rows = timed(null, null)

            assertEquals(listOf(60L, 120L), rows.map { it.durationSeconds }.sorted())
            assertEquals(null, rows.first().authors)
        }

    @Test
    fun finishOutcomes_countFinishedAndDnf_only() =
        runTest {
            book("f1", status = ReadingStatus.FINISHED)
            book("f2", status = ReadingStatus.FINISHED)
            book("d1", status = ReadingStatus.DNF)
            book("r1", status = ReadingStatus.READING)
            book("t1", status = ReadingStatus.TO_READ)

            assertEquals(
                FinishOutcomes(finished = 2, dnf = 1),
                db.statsDao().observeFinishOutcomes(ReadingStatus.FINISHED, ReadingStatus.DNF).first(),
            )
        }

    @Test
    fun finishOutcomes_onAnEmptyLibrary_areZero() =
        runTest {
            assertEquals(
                FinishOutcomes(0, 0),
                db.statsDao().observeFinishOutcomes(ReadingStatus.FINISHED, ReadingStatus.DNF).first(),
            )
        }

    // --- #221: what a year in review reads ------------------------------------------------------

    private suspend fun finished(
        id: String,
        pages: Int?,
        at: Long,
        status: ReadingStatus = ReadingStatus.FINISHED,
    ) {
        db.mediaItemDao().insert(sampleMediaItem(id = id, title = "Book $id"))
        db.bookDetailsDao().insert(
            sampleBookDetails(
                mediaId = id,
                totalPages = pages,
            ).copy(authors = "Le Guin"),
        )
        db.readThroughDao().insert(ReadThroughEntity(newId(), id, 1, status, finishedAt = at(at)))
    }

    @Test
    fun finishedBooks_areBoundedAndFinishedOnly_withTheirPages() =
        runTest {
            finished("in", pages = 412, at = 1_500)
            finished("unknown", pages = null, at = 1_600)
            finished("before", pages = 900, at = 900)
            finished("reading", pages = 300, at = 1_700, status = ReadingStatus.READING)

            val rows = db.statsDao().observeFinishedBooks(at(1_000), at(2_000), ReadingStatus.FINISHED).first()

            assertEquals(
                listOf(
                    FinishedBookRow("in", "Book in", 412, "Le Guin", at(1_500)),
                    FinishedBookRow("unknown", "Book unknown", null, "Le Guin", at(1_600)),
                ),
                rows.sortedBy { it.finishedAt },
            )
        }

    @Test
    fun firstActivity_isTheEarliestOfSessionsViewingsAndFinishes() =
        runTest {
            book("b1")
            session("b1", start = 5_000, durationSeconds = 60)
            finished("f1", pages = 100, at = 4_000)
            db.mediaItemDao().insert(
                sampleMediaItem(id = "film", type = com.hub.media.core.database.entities.MediaType.MOVIE),
            )
            db.movieWriteDao().insertViewing(
                com.hub.media.core.database.entities
                    .WatchLogEntity(id = "w", mediaId = "film", watchedAt = at(3_000)),
            )

            assertEquals(at(3_000), db.statsDao().observeFirstActivity().first(), "the viewing is earliest")
        }

    @Test
    fun firstActivity_onAnEmptyLibrary_isNull() =
        runTest {
            assertEquals(null, db.statsDao().observeFirstActivity().first())
        }

    // --- #230: stats count reads ------------------------------------------------------------------

    /** Finish outcomes count reads, so a book finished then re-read and abandoned is one of each (#230). */
    @Test
    fun finishOutcomes_countReads() =
        runTest {
            db.insertTestBook("b", status = ReadingStatus.FINISHED, finishedAt = at(1))
            db.readThroughDao().insert(ReadThroughEntity(newId(), "b", 2, ReadingStatus.DNF))

            assertEquals(
                FinishOutcomes(finished = 1, dnf = 1),
                db.statsDao().observeFinishOutcomes(ReadingStatus.FINISHED, ReadingStatus.DNF).first(),
            )
        }

    /** A re-read finished in the range is a finished book of that range, at the re-read's date (#230). */
    @Test
    fun finishedBooks_includeAReread_atItsOwnDate() =
        runTest {
            db.insertTestBook("b", status = ReadingStatus.FINISHED, finishedAt = at(900), title = "Book b")
            db.readThroughDao().insert(
                ReadThroughEntity(newId(), "b", 2, ReadingStatus.FINISHED, finishedAt = at(1_500)),
            )

            val rows = db.statsDao().observeFinishedBooks(at(1_000), at(2_000), ReadingStatus.FINISHED).first()

            assertEquals(listOf("b" to at(1_500)), rows.map { it.mediaId to it.finishedAt })
        }

    /** An earlier read's finish is activity, even once a re-read has moved the book on (#230). */
    @Test
    fun firstActivity_seesAnEarlierReadsFinish() =
        runTest {
            db.insertTestBook("b", status = ReadingStatus.FINISHED, finishedAt = at(1_000))
            db.readThroughDao().insert(ReadThroughEntity(newId(), "b", 2, ReadingStatus.READING))

            assertEquals(at(1_000), db.statsDao().observeFirstActivity().first())
        }
}
