package com.hub.media.features.tv.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The dated-mark, read-then-clear and conditional-restore writes behind the tick prompt and its
 * Undo (#131), against a real database.
 */
class UntickRestoreTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TVShowRepository
    private val t1 = Instant.parse("2024-03-03T12:00:00Z")
    private val t2 = Instant.parse("2026-10-12T12:00:00Z")

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun show(): String {
        val r = repo.addShow(title = "S", seasons = listOf(SeasonQuickFill(1, episodeCount = 2)))
        assertIs<Resource.Success<String>>(r)
        return r.data
    }

    private suspend fun firstEpisodeId(showId: String) =
        db
            .episodeDao()
            .getByMediaIdAndSeason(showId, 1)
            .first()
            .id

    @Test
    fun clearThenRestore_putsBackTheExactTimestamp() =
        runTest {
            val id = firstEpisodeId(show())
            db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to t1))

            val previous = db.tvWriteDao().clearEpisodeReturningPrevious(id)
            assertEquals(mapOf(id to t1), previous)
            assertNull(db.episodeDao().getById(id)!!.watchedAt)

            assertEquals(1, db.tvWriteDao().restoreAll(previous))
            assertEquals(t1, db.episodeDao().getById(id)!!.watchedAt)
        }

    @Test
    fun restore_neverOverwritesAReTick() =
        runTest {
            val id = firstEpisodeId(show())
            db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to t1))
            val previous = db.tvWriteDao().clearEpisodeReturningPrevious(id)
            db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to t2)) // re-ticked before Undo

            assertEquals(0, db.tvWriteDao().restoreAll(previous))
            assertEquals(t2, db.episodeDao().getById(id)!!.watchedAt)
        }

    @Test
    fun markEpisodesWatchedAt_neverOverwritesAnExistingDate() =
        runTest {
            val id = firstEpisodeId(show())
            db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to t1))

            assertEquals(0, db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to t2)))
            assertEquals(t1, db.episodeDao().getById(id)!!.watchedAt)
        }

    @Test
    fun clearSeason_returnsOnlyThePreviouslyWatched_andRestoresThem() =
        runTest {
            val showId = show()
            val (a, b) = db.episodeDao().getByMediaIdAndSeason(showId, 1)
            db.tvWriteDao().markEpisodesWatchedAt(mapOf(a.id to t1))

            val previous = db.tvWriteDao().clearSeasonReturningPrevious(showId, 1)
            assertEquals(mapOf(a.id to t1), previous)

            db.tvWriteDao().restoreAll(previous)
            assertEquals(t1, db.episodeDao().getById(a.id)!!.watchedAt)
            assertNull(db.episodeDao().getById(b.id)!!.watchedAt, "an episode that was never watched stays unwatched")
        }
}
