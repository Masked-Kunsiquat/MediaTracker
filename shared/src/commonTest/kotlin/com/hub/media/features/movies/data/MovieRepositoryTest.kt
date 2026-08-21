package com.hub.media.features.movies.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Covers [MovieRepository] -- the movie counterpart of
 * [com.hub.media.features.books.data.BookRepositoryTest], deliberately mirroring its setup,
 * naming, and level of KDoc, per that repository's own note that [MovieRepository] is written to
 * behave identically to [BookRepository] where the two overlap.
 */
class MovieRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: MovieRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = MovieRepository(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    /**
     * A [Clock] whose [now] advances by [stepMillis] on every call, starting from [start]. Used
     * wherever a test must prove a timestamp came from a *fresh* [Clock.now] read rather than a
     * stale/reused value -- a fixed clock cannot distinguish "read once and reused" from "read
     * again and got the same wall-clock instant by coincidence", but an advancing one can: two
     * calls always produce two different [Instant]s, so reusing the first is directly observable.
     */
    private class AdvancingClock(
        start: Instant,
        private val stepMillis: Long = 1_000,
    ) : Clock {
        private var current = start

        override fun now(): Instant {
            val value = current
            current = current + stepMillis.milliseconds
            return value
        }
    }

    // ---- addMovie: happy path -------------------------------------------------------------

    @Test
    fun addMovie_happyPath_insertsBothRowsAtomicallyAndReturnsId() =
        runTest {
            val result =
                repo.addMovie(
                    title = "The Matrix",
                    releaseYear = 1999,
                    purchasePrice = 14.99,
                    runtimeMinutes = 136,
                    status = WatchStatus.WATCHLIST,
                    coverImageHash = "matrix.jpg",
                )

            assertIs<Resource.Success<String>>(result)
            val mediaId = result.data

            val mediaItem = db.mediaItemDao().getById(mediaId)
            assertEquals("The Matrix", mediaItem?.title)
            assertEquals(MediaType.MOVIE, mediaItem?.type)
            assertEquals(1999, mediaItem?.releaseYear)
            assertEquals(14.99, mediaItem?.purchasePrice)
            assertEquals("matrix.jpg", mediaItem?.coverImageHash)

            val details = db.movieDetailsDao().getByMediaId(mediaId)
            assertEquals(mediaId, details?.mediaId)
            assertEquals(136, details?.runtimeMinutes)
            assertEquals(WatchStatus.WATCHLIST, details?.status)
            assertNull(details?.watchedAt)
        }

    // ---- addMovie: watchedAt on insert -----------------------------------------------------

    @Test
    fun addMovie_statusWatched_stampsWatchedAtToClockNow() =
        runTest {
            val fixedInstant = Instant.fromEpochMilliseconds(1_000_000_000_000) // 2001-09-09, not "now"
            val fixedClock =
                object : Clock {
                    override fun now(): Instant = fixedInstant
                }
            val repoWithFixedClock = MovieRepository(db, fixedClock)

            val result =
                repoWithFixedClock.addMovie(
                    title = "Already Seen It",
                    status = WatchStatus.WATCHED,
                )
            assertIs<Resource.Success<String>>(result)

            val details = db.movieDetailsDao().getByMediaId(result.data)
            assertEquals(fixedInstant, details?.watchedAt, "watchedAt must come from the injected Clock")
        }

    @Test
    fun addMovie_statusNotWatched_watchedAtIsNull() =
        runTest {
            // Every status other than WATCHED must leave watchedAt null on insert -- there is no
            // "watched" date to claim for a film that has not (yet) been watched.
            for (status in listOf(WatchStatus.WATCHLIST, WatchStatus.WATCHING, WatchStatus.ABANDONED)) {
                val result = repo.addMovie(title = "Status $status", status = status)
                assertIs<Resource.Success<String>>(result)

                val details = db.movieDetailsDao().getByMediaId(result.data)
                assertEquals(status, details?.status)
                assertNull(details?.watchedAt, "watchedAt must be null for status=$status")
            }
        }

    // ---- addMovie: validation rejects, each proving NOTHING was written -------------------

    @Test
    fun addMovie_blankTitle_rejectedAndPersistsNothing() =
        runTest {
            val result = repo.addMovie(title = "   ")
            assertIs<Resource.Error>(result)

            val allMediaItems = db.mediaItemDao().observeAll().first()
            assertTrue(allMediaItems.isEmpty())
            assertTrue(db.movieDetailsDao().getAll().isEmpty())
        }

    @Test
    fun addMovie_negativePurchasePrice_rejectedAndPersistsNothing() =
        runTest {
            val result = repo.addMovie(title = "Movie", purchasePrice = -0.01)
            assertIs<Resource.Error>(result)

            val allMediaItems = db.mediaItemDao().observeAll().first()
            assertTrue(allMediaItems.isEmpty())
            assertTrue(db.movieDetailsDao().getAll().isEmpty())
        }

    @Test
    fun addMovie_nonFinitePurchasePriceNaN_rejectedAndPersistsNothing() =
        runTest {
            // Double.NaN satisfies `purchasePrice < 0.0 == false` under IEEE 754, so a plain
            // negativity check alone would silently let it through -- see
            // MovieMetadataValidation's KDoc on why validatePurchasePrice is delegated rather than
            // reimplemented.
            val result = repo.addMovie(title = "Movie", purchasePrice = Double.NaN)
            assertIs<Resource.Error>(result)

            val allMediaItems = db.mediaItemDao().observeAll().first()
            assertTrue(allMediaItems.isEmpty())
            assertTrue(db.movieDetailsDao().getAll().isEmpty())
        }

    @Test
    fun addMovie_nonFinitePurchasePriceInfinity_rejectedAndPersistsNothing() =
        runTest {
            val result = repo.addMovie(title = "Movie", purchasePrice = Double.POSITIVE_INFINITY)
            assertIs<Resource.Error>(result)

            val allMediaItems = db.mediaItemDao().observeAll().first()
            assertTrue(allMediaItems.isEmpty())
            assertTrue(db.movieDetailsDao().getAll().isEmpty())
        }

    @Test
    fun addMovie_zeroRuntimeMinutes_rejectedAndPersistsNothing() =
        runTest {
            // 0 is never a valid runtime -- it must mean "unknown", represented by null, never by a
            // zero stand-in (see MovieDetailsEntity.runtimeMinutes's KDoc).
            val result = repo.addMovie(title = "Movie", runtimeMinutes = 0)
            assertIs<Resource.Error>(result)

            val allMediaItems = db.mediaItemDao().observeAll().first()
            assertTrue(allMediaItems.isEmpty())
            assertTrue(db.movieDetailsDao().getAll().isEmpty())
        }

    @Test
    fun addMovie_negativeRuntimeMinutes_rejectedAndPersistsNothing() =
        runTest {
            val result = repo.addMovie(title = "Movie", runtimeMinutes = -5)
            assertIs<Resource.Error>(result)

            val allMediaItems = db.mediaItemDao().observeAll().first()
            assertTrue(allMediaItems.isEmpty())
            assertTrue(db.movieDetailsDao().getAll().isEmpty())
        }

    @Test
    fun addMovie_releaseYearTooLow_rejectedAndPersistsNothing() =
        runTest {
            val result =
                repo.addMovie(title = "Movie", releaseYear = MovieMetadataValidation.MIN_RELEASE_YEAR - 1)
            assertIs<Resource.Error>(result)

            val allMediaItems = db.mediaItemDao().observeAll().first()
            assertTrue(allMediaItems.isEmpty())
            assertTrue(db.movieDetailsDao().getAll().isEmpty())
        }

    @Test
    fun addMovie_releaseYearTooHigh_rejectedAndPersistsNothing() =
        runTest {
            val result =
                repo.addMovie(title = "Movie", releaseYear = MovieMetadataValidation.MAX_RELEASE_YEAR + 1)
            assertIs<Resource.Error>(result)

            val allMediaItems = db.mediaItemDao().observeAll().first()
            assertTrue(allMediaItems.isEmpty())
            assertTrue(db.movieDetailsDao().getAll().isEmpty())
        }

    // ---- addMovie: valid boundary values are ACCEPTED (positive controls for the rejects above) --

    @Test
    fun addMovie_releaseYearAtMinBoundary_accepted() =
        runTest {
            val result =
                repo.addMovie(title = "Oldest Surviving Film", releaseYear = MovieMetadataValidation.MIN_RELEASE_YEAR)
            assertIs<Resource.Success<String>>(result)

            val mediaItem = db.mediaItemDao().getById(result.data)
            assertEquals(MovieMetadataValidation.MIN_RELEASE_YEAR, mediaItem?.releaseYear)
        }

    @Test
    fun addMovie_releaseYearAtMaxBoundary_accepted() =
        runTest {
            val result =
                repo.addMovie(title = "Announced Future Film", releaseYear = MovieMetadataValidation.MAX_RELEASE_YEAR)
            assertIs<Resource.Success<String>>(result)

            val mediaItem = db.mediaItemDao().getById(result.data)
            assertEquals(MovieMetadataValidation.MAX_RELEASE_YEAR, mediaItem?.releaseYear)
        }

    @Test
    fun addMovie_runtimeMinutesExactlyOne_accepted() =
        runTest {
            val result = repo.addMovie(title = "Very Short Film", runtimeMinutes = 1)
            assertIs<Resource.Success<String>>(result)

            val details = db.movieDetailsDao().getByMediaId(result.data)
            assertEquals(1, details?.runtimeMinutes)
        }

    @Test
    fun addMovie_purchasePriceExactlyZero_accepted() =
        runTest {
            val result = repo.addMovie(title = "Free Screening", purchasePrice = 0.0)
            assertIs<Resource.Success<String>>(result)

            val mediaItem = db.mediaItemDao().getById(result.data)
            assertEquals(0.0, mediaItem?.purchasePrice)
        }

    // ---- updateMovieMetadata ----------------------------------------------------------------

    @Test
    fun updateMovieMetadata_happyPath_updatesBothTables() =
        runTest {
            val addResult =
                repo.addMovie(
                    title = "Original Title",
                    releaseYear = 1999,
                    purchasePrice = 14.99,
                    runtimeMinutes = 100,
                    status = WatchStatus.WATCHLIST,
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val result =
                repo.updateMovieMetadata(
                    mediaId = mediaId,
                    title = "Corrected Title",
                    releaseYear = 2000,
                    purchasePrice = 9.99,
                    runtimeMinutes = 120,
                    status = WatchStatus.WATCHING,
                )
            assertIs<Resource.Success<Unit>>(result)

            val mediaItem = db.mediaItemDao().getById(mediaId)
            assertEquals("Corrected Title", mediaItem?.title)
            assertEquals(2000, mediaItem?.releaseYear)
            assertEquals(9.99, mediaItem?.purchasePrice)

            val details = db.movieDetailsDao().getByMediaId(mediaId)
            assertEquals(120, details?.runtimeMinutes)
            assertEquals(WatchStatus.WATCHING, details?.status)
        }

    @Test
    fun updateMovieMetadata_unknownMediaId_returnsErrorAndWritesNothing() =
        runTest {
            val unknownId = newId()

            val result =
                repo.updateMovieMetadata(
                    mediaId = unknownId,
                    title = "Any Title",
                    status = WatchStatus.WATCHLIST,
                )
            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("not found"))

            assertNull(db.mediaItemDao().getById(unknownId))
            assertNull(db.movieDetailsDao().getByMediaId(unknownId))
        }

    // ---- updateMovieMetadata: watchedAt transition rules -------------------------------------

    @Test
    fun updateMovieMetadata_transitionToWatched_stampsNow() =
        runTest {
            // An advancing clock: addMovie's createdAt read is the first tick, so if the
            // WATCHED-transition stamp below reused that same instant instead of taking a fresh
            // Clock.now() read, this would still pass with a fixed clock but fail here.
            val clock = AdvancingClock(start = Instant.fromEpochMilliseconds(1_700_000_000_000))
            val repoWithClock = MovieRepository(db, clock)

            val addResult = repoWithClock.addMovie(title = "Watch Me", status = WatchStatus.WATCHLIST)
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data
            val createdAt = db.mediaItemDao().getById(mediaId)?.createdAt

            val result =
                repoWithClock.updateMovieMetadata(
                    mediaId = mediaId,
                    title = "Watch Me",
                    status = WatchStatus.WATCHED,
                )
            assertIs<Resource.Success<Unit>>(result)

            val details = db.movieDetailsDao().getByMediaId(mediaId)
            assertTrue(details?.watchedAt != null, "watchedAt must be stamped on the WATCHED transition")
            assertTrue(details?.watchedAt != createdAt, "watchedAt must be a fresh Clock.now() read, not a reused one")
        }

    @Test
    fun updateMovieMetadata_reSavingAlreadyWatched_preservesOriginalWatchedAt() =
        runTest {
            // The assertion that actually matters: the clock keeps advancing on every now() call,
            // so if re-saving while WATCHED bumped watchedAt to a new read, this would be directly
            // detectable rather than coincidentally passing.
            val clock = AdvancingClock(start = Instant.fromEpochMilliseconds(1_700_000_000_000))
            val repoWithClock = MovieRepository(db, clock)

            val addResult = repoWithClock.addMovie(title = "Already Watched", status = WatchStatus.WATCHLIST)
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            repoWithClock.updateMovieMetadata(
                mediaId = mediaId,
                title = "Already Watched",
                status = WatchStatus.WATCHED,
            )
            val firstWatchedAt = db.movieDetailsDao().getByMediaId(mediaId)?.watchedAt
            assertTrue(firstWatchedAt != null)

            val result =
                repoWithClock.updateMovieMetadata(
                    mediaId = mediaId,
                    title = "Already Watched (edited)",
                    runtimeMinutes = 150,
                    status = WatchStatus.WATCHED,
                )
            assertIs<Resource.Success<Unit>>(result)

            val details = db.movieDetailsDao().getByMediaId(mediaId)
            assertEquals(firstWatchedAt, details?.watchedAt, "re-saving while WATCHED must not bump watchedAt")
        }

    @Test
    fun updateMovieMetadata_transitionAwayFromWatched_clearsWatchedAt() =
        runTest {
            val addResult = repo.addMovie(title = "Reopened", status = WatchStatus.WATCHLIST)
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data
            repo.updateMovieMetadata(mediaId = mediaId, title = "Reopened", status = WatchStatus.WATCHED)
            assertTrue(db.movieDetailsDao().getByMediaId(mediaId)?.watchedAt != null)

            val result =
                repo.updateMovieMetadata(
                    mediaId = mediaId,
                    title = "Reopened",
                    status = WatchStatus.WATCHING,
                )
            assertIs<Resource.Success<Unit>>(result)

            val details = db.movieDetailsDao().getByMediaId(mediaId)
            assertEquals(WatchStatus.WATCHING, details?.status)
            assertNull(details?.watchedAt, "moving away from WATCHED must clear watchedAt")
        }

    // ---- observeMovieDetail / observeAllMoviesWithDetails: type gate against books ----------

    @Test
    fun observeMovieDetail_emitsMovieForInsertedMovie() =
        runTest {
            val addResult =
                repo.addMovie(
                    title = "Inception",
                    releaseYear = 2010,
                    runtimeMinutes = 148,
                    status = WatchStatus.WATCHLIST,
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val detail = repo.observeMovieDetail(mediaId).first { it != null }
            assertEquals("Inception", detail?.item?.title)
            assertEquals(2010, detail?.item?.releaseYear)
            assertEquals(mediaId, detail?.details?.mediaId)
            assertEquals(148, detail?.details?.runtimeMinutes)
        }

    @Test
    fun observeMovieDetail_emitsNullWhenIdBelongsToBook() =
        runTest {
            // Same type gate BookRepository.observeBookDetail applies in reverse: an id that
            // resolves to a MediaItemEntity of the WRONG type must never be shown as if it were the
            // right one.
            val bookRepo = BookRepository(db)
            val bookResult = bookRepo.addBook(title = "Not A Movie", format = BookFormat.PHYSICAL)
            assertIs<Resource.Success<String>>(bookResult)
            val bookId = bookResult.data

            val detail = repo.observeMovieDetail(bookId).first()
            assertNull(detail)
        }

    @Test
    fun observeAllMoviesWithDetails_returnsOnlyMoviesNotBooks() =
        runTest {
            val movieResult = repo.addMovie(title = "A Real Movie", status = WatchStatus.WATCHLIST)
            assertIs<Resource.Success<String>>(movieResult)

            val bookRepo = BookRepository(db)
            val bookResult = bookRepo.addBook(title = "A Real Book", format = BookFormat.PHYSICAL)
            assertIs<Resource.Success<String>>(bookResult)

            val allMovies = repo.observeAllMoviesWithDetails().first { it.isNotEmpty() }
            assertEquals(1, allMovies.size)
            assertEquals("A Real Movie", allMovies.single().item.title)
            assertTrue(allMovies.none { it.item.id == bookResult.data })
        }
}
