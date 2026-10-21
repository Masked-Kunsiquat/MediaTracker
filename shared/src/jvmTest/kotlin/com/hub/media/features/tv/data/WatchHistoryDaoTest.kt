package com.hub.media.features.tv.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * #90: viewings are the truth. Every write must leave `episodes.watchedAt` equal to the episode's
 * latest viewing, or `null` with none -- [assertInvariant] checks exactly that after each one.
 */
class WatchHistoryDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TVShowRepository
    private val t0 = Instant.parse("2023-06-01T12:00:00Z")
    private val t1 = Instant.parse("2024-03-03T12:00:00Z")
    private val t2 = Instant.parse("2025-01-10T12:00:00Z")
    private val t3 = Instant.parse("2026-02-20T12:00:00Z")

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun season(count: Int): List<EpisodeEntity> {
        val r = repo.addShow(title = "S", seasons = listOf(SeasonQuickFill(1, episodeCount = count)))
        assertIs<Resource.Success<String>>(r)
        return db.episodeDao().getByMediaIdAndSeason(r.data, 1)
    }

    private val dao get() = db.tvWriteDao()

    private suspend fun viewings(id: String) = db.watchLogDao().observeForEpisode(id).first()

    private suspend fun watchedAt(id: String) = db.episodeDao().getById(id)?.watchedAt

    /** `watchedAt` is the latest viewing in the show's latest run (#231). */
    private suspend fun assertInvariant(id: String) {
        val latestRun = db.watchThroughDao().getLatest(db.episodeDao().getById(id)!!.mediaId)?.id
        assertEquals(
            viewings(id).filter { it.watchThroughId == latestRun }.maxOfOrNull { it.watchedAt },
            watchedAt(id),
            "watchedAt must be the latest viewing in the latest run",
        )
    }

    @Test
    fun tick_writesOneViewingPerEpisodeChanged_andNoneForAlreadyWatched() =
        runTest {
            val (e1, e2) = season(2)
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            assertEquals(1, dao.markEpisodesWatchedAt(mapOf(e1.id to t2, e2.id to t2)))
            assertEquals(
                listOf(t1),
                viewings(e1.id).map { it.watchedAt },
                "an already-watched episode gains no viewing",
            )
            assertEquals(listOf(t2), viewings(e2.id).map { it.watchedAt })
            assertInvariant(e1.id)
            assertInvariant(e2.id)
        }

    @Test
    fun rewatch_addsViewing_andMovesWatchedAtForward() =
        runTest {
            val e1 = season(1).single()
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            dao.addViewing(e1.id, t2)
            assertEquals(2, viewings(e1.id).size)
            assertEquals(t2, watchedAt(e1.id))
            assertInvariant(e1.id)
        }

    @Test
    fun backdatedRewatch_addsViewing_butKeepsWatchedAt() =
        runTest {
            val e1 = season(1).single()
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            dao.addViewing(e1.id, t0)
            assertEquals(2, viewings(e1.id).size)
            assertEquals(t1, watchedAt(e1.id))
            assertInvariant(e1.id)
        }

    @Test
    fun deleteMiddleViewing_thenRestore_keepsWatchedAt() =
        runTest {
            val e1 = season(1).single()
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            dao.addViewing(e1.id, t2)
            dao.addViewing(e1.id, t3)
            val middle = viewings(e1.id).single { it.watchedAt == t2 }

            val deleted = assertNotNull(dao.deleteViewing(middle.id))
            assertEquals(t3, watchedAt(e1.id))
            assertInvariant(e1.id)

            assertEquals(true, dao.restoreViewing(deleted))
            assertEquals(3, viewings(e1.id).size)
            assertEquals(t3, watchedAt(e1.id))
            assertInvariant(e1.id)
        }

    @Test
    fun deleteLastViewing_unticks() =
        runTest {
            val e1 = season(1).single()
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            dao.deleteViewing(viewings(e1.id).single().id)
            assertNull(watchedAt(e1.id))
            assertInvariant(e1.id)
        }

    @Test
    fun editDate_recomputes() =
        runTest {
            val e1 = season(1).single()
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            dao.addViewing(e1.id, t3)
            val latest = viewings(e1.id).single { it.watchedAt == t3 }
            dao.updateViewingDate(latest.id, t0)
            assertEquals(t1, watchedAt(e1.id))
            assertInvariant(e1.id)
        }

    @Test
    fun untick_returnsViewings_andRestoreAllPutsThemBack() =
        runTest {
            val e1 = season(1).single()
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            dao.addViewing(e1.id, t2)

            val previous = dao.clearEpisodeReturningPrevious(e1.id)
            assertEquals(setOf(t1, t2), previous.map { it.watchedAt }.toSet())
            assertNull(watchedAt(e1.id))
            assertInvariant(e1.id)

            assertEquals(1, dao.restoreAll(previous))
            assertEquals(2, viewings(e1.id).size)
            assertInvariant(e1.id)
        }

    @Test
    fun restoreAll_afterReTick_writesNothing() =
        runTest {
            val e1 = season(1).single()
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            val previous = dao.clearEpisodeReturningPrevious(e1.id)
            dao.markEpisodesWatchedAt(mapOf(e1.id to t3))

            assertEquals(0, dao.restoreAll(previous))
            assertEquals(listOf(t3), viewings(e1.id).map { it.watchedAt })
            assertInvariant(e1.id)
        }

    @Test
    fun restoreAll_afterSeasonShrink_skipsTheDeletedEpisode_andDoesNotThrow() =
        runTest {
            val (e1, e2, e3) = season(3)
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1, e2.id to t1, e3.id to t1))
            val previous = dao.clearSeasonReturningPrevious(e1.mediaId, 1)
            assertIs<Resource.Success<*>>(repo.setSeasonLength(e1.mediaId, 1, 2))

            assertEquals(2, dao.restoreAll(previous))
            assertEquals(t1, watchedAt(e1.id))
            assertEquals(t1, watchedAt(e2.id))
            assertNull(db.episodeDao().getById(e3.id), "positive control: E3 really was deleted")
        }

    @Test
    fun backfill_isIdempotent() =
        runTest {
            val base = season(1).single()
            val watched = EpisodeEntity(newId(), base.mediaId, 1, 2, watchedAt = t2)
            dao.insertEpisodes(listOf(watched))

            assertEquals(1, dao.ensureViewingsForWatchedEpisodes())
            assertEquals(0, dao.ensureViewingsForWatchedEpisodes())
            assertEquals(listOf(t2), viewings(watched.id).map { it.watchedAt })
            assertInvariant(watched.id)
        }

    @Test
    fun watchAgain_clearsTicks_andRemoveRestoresThem() =
        runTest {
            val (e1, e2) = season(2)
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1, e2.id to t2))
            val two = dao.startNextRun(e1.mediaId, newId())
            assertEquals(listOf(null, null), listOf(watchedAt(e1.id), watchedAt(e2.id)), "run #2 starts empty")
            assertEquals(2, viewings(e1.id).size + viewings(e2.id).size, "run #1's history is kept")
            assertTrue(dao.removeRun(two))
            assertEquals(listOf(t1, t2), listOf(watchedAt(e1.id), watchedAt(e2.id)), "ticks come back exactly")
        }

    @Test
    fun untickInRunTwo_keepsRunOneViewings() =
        runTest {
            val (e1) = season(1)
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            dao.startNextRun(e1.mediaId, newId())
            dao.markEpisodesWatchedAt(mapOf(e1.id to t2))
            val removed = dao.clearEpisodeReturningPrevious(e1.id)
            assertEquals(listOf(t2), removed.map { it.watchedAt })
            assertEquals(listOf(t1), viewings(e1.id).map { it.watchedAt })
            assertEquals(null, watchedAt(e1.id))
        }

    @Test
    fun deletingAnOlderRunsViewing_leavesTicksAlone() =
        runTest {
            val (e1) = season(1)
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            val old = viewings(e1.id).single()
            dao.startNextRun(e1.mediaId, newId())
            dao.markEpisodesWatchedAt(mapOf(e1.id to t2))
            dao.deleteViewing(old.id)
            assertEquals(t2, watchedAt(e1.id))
        }

    @Test
    fun restoreAll_afterItsRunWasRemoved_skipsQuietly() =
        runTest {
            val (e1) = season(1)
            val two = dao.startNextRun(e1.mediaId, newId())
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            val previous = dao.clearEpisodeReturningPrevious(e1.id)
            assertTrue(dao.removeRun(two))
            assertEquals(0, dao.restoreAll(previous))
            assertEquals(null, watchedAt(e1.id))
        }

    @Test
    fun backfill_writesIntoTheLatestRun() =
        runTest {
            val (e1) = season(1)
            val two = dao.startNextRun(e1.mediaId, newId())
            dao.markEpisodeWatchedIfUnwatched(e1.id, t1) // watched with no viewing, as an old library can be
            dao.ensureViewingsForWatchedEpisodes()
            assertEquals(two, viewings(e1.id).single().watchThroughId)
        }

    /** A later viewing in an older run never shows as this run's date (#231). */
    @Test
    fun aLaterViewingInAnOlderRun_staysOutOfTheCurrentTick() =
        runTest {
            val e1 = season(1).single()
            dao.markEpisodesWatchedAt(mapOf(e1.id to t3))
            dao.startNextRun(e1.mediaId, newId())
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            dao.addViewing(e1.id, t0)
            assertEquals(t1, watchedAt(e1.id))
            assertInvariant(e1.id)
        }
}
