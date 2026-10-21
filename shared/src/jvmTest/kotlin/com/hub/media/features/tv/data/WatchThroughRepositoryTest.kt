package com.hub.media.features.tv.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/** Watch again, Remove this watch-through, Abandon/Resume and ratings (#231). */
class WatchThroughRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var shows: TVShowRepository
    private lateinit var repo: WatchThroughRepository
    private val at = Instant.parse("2025-03-01T12:00:00Z")

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        shows = TVShowRepository(db)
        repo = WatchThroughRepository(db, includeSpecials = { false })
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun show(episodes: Int = 2): String =
        (shows.addShow(title = "S", seasons = listOf(SeasonQuickFill(1, episodeCount = episodes))) as Resource.Success)
            .data

    private suspend fun tickAll(id: String) =
        db.tvWriteDao().markEpisodesWatchedAt(db.episodeDao().getByMediaId(id).associate { it.id to at })

    private suspend fun runs(id: String) = db.watchThroughDao().getForMedia(id)

    @Test
    fun watchAgain_onlyWhenLatestIsCompleteOrAbandoned() =
        runTest {
            val id = show()
            assertIs<Resource.Error>(repo.watchAgain(id), "an unfinished run cannot be followed")
            tickAll(id)
            assertEquals(2, (repo.watchAgain(id) as Resource.Success).data.number)

            val dropped = show()
            repo.setAbandoned(dropped, true)
            assertIs<Resource.Success<*>>(repo.watchAgain(dropped), "an abandoned run can be followed")
        }

    @Test
    fun watchAgain_twice_createsOneRun() =
        runTest {
            val id = show()
            tickAll(id)
            val results = listOf(async { repo.watchAgain(id) }, async { repo.watchAgain(id) }).awaitAll()
            assertEquals(1, results.count { it is Resource.Success }, "$results")
            assertEquals(1, results.count { it is Resource.Error }, "$results")
            assertEquals(listOf(1, 2), runs(id).map { it.number })
        }

    @Test
    fun removeRun_refusedForOne_forRunWithViewings_andForOlderRun() =
        runTest {
            val id = show(episodes = 1)
            val one = runs(id).single()
            assertIs<Resource.Error>(repo.removeRun(one.id), "#1 never goes")
            tickAll(id)
            val two = (repo.watchAgain(id) as Resource.Success).data
            tickAll(id)
            assertIs<Resource.Error>(repo.removeRun(two.id), "a run with viewings stays")
            val three = (repo.watchAgain(id) as Resource.Success).data
            assertIs<Resource.Error>(repo.removeRun(two.id), "only the latest goes")
            assertIs<Resource.Success<Unit>>(repo.removeRun(three.id))
            assertEquals(listOf(1, 2), runs(id).map { it.number })
        }

    @Test
    fun setAbandoned_actsOnTheLatestRunOnly() =
        runTest {
            val id = show()
            tickAll(id)
            repo.watchAgain(id)
            repo.setAbandoned(id, true)
            assertEquals(listOf(false, true), runs(id).map { it.abandoned })
            repo.setAbandoned(id, false)
            assertFalse(runs(id).last().abandoned)
        }

    @Test
    fun setRating_validates() =
        runTest {
            val run = runs(show()).single()
            assertIs<Resource.Error>(repo.setRating(run.id, 0))
            assertIs<Resource.Error>(repo.setRating(run.id, 11))
            assertIs<Resource.Success<Unit>>(repo.setRating(run.id, 7))
            assertEquals(7, db.watchThroughDao().getById(run.id)!!.rating)
            assertIs<Resource.Success<Unit>>(repo.setRating(run.id, null))
            assertNull(db.watchThroughDao().getById(run.id)!!.rating)
        }
}
