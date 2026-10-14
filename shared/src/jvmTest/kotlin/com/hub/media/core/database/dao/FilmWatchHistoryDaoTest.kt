package com.hub.media.core.database.dao

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Film viewings (#192): every [MovieWriteDao] write keeps a Watched film's `watchedAt` equal to its
 * latest viewing, and any other status at `null`, while the viewings themselves outlive status changes.
 */
class FilmWatchHistoryDaoTest {
    private val t1 = Instant.parse("2024-03-03T12:00:00Z")
    private val t2 = Instant.parse("2025-01-10T12:00:00Z")
    private lateinit var db: AppDatabase
    private val dao get() = db.movieWriteDao()

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun film(status: WatchStatus = WatchStatus.WATCHLIST): String {
        val id = (MovieRepository(db).addMovie(title = "Heat") as Resource.Success).data
        if (status != WatchStatus.WATCHLIST) dao.changeStatus(id, status, t1)
        return id
    }

    private suspend fun viewings(id: String) = db.watchLogDao().observeForFilm(id).first()

    private suspend fun details(id: String) = db.movieDetailsDao().getByMediaId(id)!!

    private suspend fun assertInvariant(id: String) {
        val d = details(id)
        val expected = if (d.status == WatchStatus.WATCHED) viewings(id).maxOfOrNull { it.watchedAt } else null
        assertEquals(expected, d.watchedAt)
    }

    @Test
    fun becomingWatched_addsOneViewingAtThatInstant() =
        runTest {
            val id = film()
            assertEquals(WatchStatus.WATCHLIST, dao.changeStatus(id, WatchStatus.WATCHED, t1))
            assertEquals(listOf(t1), viewings(id).map { it.watchedAt })
            assertNull(viewings(id).single().episodeId)
            assertInvariant(id)
        }

    @Test
    fun leavingWatched_keepsViewings_andClearsWatchedAt() =
        runTest {
            val id = film(WatchStatus.WATCHED)
            dao.changeStatus(id, WatchStatus.WATCHING, t2)
            assertEquals(1, viewings(id).size)
            assertNull(details(id).watchedAt)
        }

    @Test
    fun rewatchCycle_addsOneViewing() =
        runTest {
            val id = film(WatchStatus.WATCHED)
            dao.changeStatus(id, WatchStatus.WATCHING, t2)
            dao.changeStatus(id, WatchStatus.WATCHED, t2)
            assertEquals(listOf(t2, t1), viewings(id).map { it.watchedAt })
            assertEquals(t2, details(id).watchedAt)
        }

    @Test
    fun watchedToWatched_addsNothing() =
        runTest {
            val id = film(WatchStatus.WATCHED)
            dao.changeStatus(id, WatchStatus.WATCHED, t2)
            assertEquals(listOf(t1), viewings(id).map { it.watchedAt })
        }

    @Test
    fun addFilmViewing_onlyWhileWatched() =
        runTest {
            val id = film()
            assertFalse(dao.addFilmViewing(id, t2))
            dao.changeStatus(id, WatchStatus.WATCHED, t1)
            assertTrue(dao.addFilmViewing(id, t2))
            assertEquals(t2, details(id).watchedAt)
        }

    @Test
    fun deletingLastViewing_movesToWatchlist() =
        runTest {
            val id = film(WatchStatus.WATCHED)
            val deletion = assertNotNull(dao.deleteFilmViewing(viewings(id).single().id))
            assertTrue(deletion.demoted)
            assertEquals(WatchStatus.WATCHLIST, details(id).status)
            assertInvariant(id)
            assertTrue(dao.restoreFilmViewings(listOf(deletion.log), deletion.demoted))
            assertEquals(WatchStatus.WATCHED, details(id).status)
            assertEquals(t1, details(id).watchedAt)
        }

    @Test
    fun deletingOneOfTwo_keepsWatched_andRecomputes() =
        runTest {
            val id = film(WatchStatus.WATCHED)
            dao.addFilmViewing(id, t2)
            dao.deleteFilmViewing(viewings(id).first { it.watchedAt == t2 }.id)
            assertEquals(WatchStatus.WATCHED, details(id).status)
            assertEquals(t1, details(id).watchedAt)
        }

    @Test
    fun updateFilmViewingDate_recomputes() =
        runTest {
            val id = film(WatchStatus.WATCHED)
            assertTrue(dao.updateFilmViewingDate(viewings(id).single().id, t2))
            assertEquals(t2, details(id).watchedAt)
        }

    @Test
    fun restore_afterFilmDeleted_isNoOp() =
        runTest {
            val id = film(WatchStatus.WATCHED)
            val deletion = assertNotNull(dao.deleteFilmViewing(viewings(id).single().id))
            db.mediaItemDao().deleteById(id)
            assertFalse(dao.restoreFilmViewings(listOf(deletion.log), deletion.demoted))
        }

    @Test
    fun restoreFilmStatus_recomputes() =
        runTest {
            val id = film(WatchStatus.WATCHED)
            dao.changeStatus(id, WatchStatus.WATCHING, t2)
            assertTrue(dao.restoreFilmStatus(id, WatchStatus.WATCHED, expectedCurrent = WatchStatus.WATCHING))
            assertEquals(listOf(t1), viewings(id).map { it.watchedAt })
            assertEquals(t1, details(id).watchedAt)
        }

    @Test
    fun ensureViewings_backfillsLegacyWatchedFilm_once() =
        runTest {
            val id = film()
            dao.updateWatchStatusFields(id, WatchStatus.WATCHED, t1) // the legacy shape: no viewing
            assertEquals(1, dao.ensureViewingsForWatchedFilms())
            assertEquals(0, dao.ensureViewingsForWatchedFilms())
            assertEquals(listOf(t1), viewings(id).map { it.watchedAt })
        }

    @Test
    fun episodeViewingsAreNotFilmViewings() =
        runTest {
            val showId =
                (
                    TVShowRepository(db).addShow(
                        title = "S",
                        seasons = listOf(SeasonQuickFill(1, episodeCount = 1)),
                    ) as Resource.Success
                ).data
            val ep = db.episodeDao().getByMediaIdAndSeason(showId, 1).single()
            db.tvWriteDao().markEpisodesWatchedAt(mapOf(ep.id to t1))
            // Positive control: the episode's viewing exists, it just is not a film viewing.
            assertEquals(
                1,
                db
                    .watchLogDao()
                    .observeForEpisode(ep.id)
                    .first()
                    .size,
            )
            assertEquals(emptyList(), viewings(showId))
        }

    @Test
    fun restoreFilmStatus_toWatchedWithNoViewingsLeft_isRefused() =
        runTest {
            // Review finding: leave Watched, delete the only viewing, then Undo the status change.
            val id = film(WatchStatus.WATCHED)
            dao.changeStatus(id, WatchStatus.WATCHING, t2)
            dao.deleteFilmViewing(viewings(id).single().id)
            assertFalse(dao.restoreFilmStatus(id, WatchStatus.WATCHED, expectedCurrent = WatchStatus.WATCHING))
            assertEquals(WatchStatus.WATCHING, details(id).status)
            assertInvariant(id)
        }

    @Test
    fun restoreFilmStatus_afterALaterChoice_isRefused() =
        runTest {
            val id = film(WatchStatus.WATCHED)
            dao.changeStatus(id, WatchStatus.WATCHING, t2)
            dao.changeStatus(id, WatchStatus.WATCHLIST, t2)
            assertFalse(dao.restoreFilmStatus(id, WatchStatus.WATCHED, expectedCurrent = WatchStatus.WATCHING))
            assertEquals(WatchStatus.WATCHLIST, details(id).status)
        }

    @Test
    fun restoreFilmViewings_afterALaterWatchlistChoice_keepsIt() =
        runTest {
            // CodeRabbit (#193): delete one of two viewings (still Watched), choose Watchlist, then Undo.
            val id = film(WatchStatus.WATCHED)
            dao.addFilmViewing(id, t2)
            val deletion = assertNotNull(dao.deleteFilmViewing(viewings(id).first { it.watchedAt == t2 }.id))
            assertEquals(WatchStatus.WATCHED, details(id).status, "positive control: one viewing left, still Watched")
            dao.changeStatus(id, WatchStatus.WATCHLIST, t2)
            assertTrue(dao.restoreFilmViewings(listOf(deletion.log), deletion.demoted))
            assertEquals(
                WatchStatus.WATCHLIST,
                details(id).status,
                "the delete did not demote it, so Undo must not promote it",
            )
            assertEquals(2, viewings(id).size)
            assertInvariant(id)
        }
}
