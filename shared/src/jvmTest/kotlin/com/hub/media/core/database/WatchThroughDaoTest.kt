package com.hub.media.core.database

import com.hub.media.core.database.entities.WatchThroughEntity
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Watch-throughs (#231): every show has #1, ticks land in the latest, and only an empty latest after #1 goes. */
class WatchThroughDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TVShowRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun show(episodes: Int = 2): String =
        (
            repo.addShow(
                title = "S",
                seasons = listOf(SeasonQuickFill(1, episodeCount = episodes)),
            ) as Resource.Success
        ).data

    @Test
    fun addingAShow_createsRunOne() =
        runTest {
            val id = show()
            assertEquals(listOf(1), db.watchThroughDao().getForMedia(id).map { it.number })
        }

    @Test
    fun aTick_landsInTheLatestRun() =
        runTest {
            val id = show()
            val e = db.episodeDao().getByMediaIdAndSeason(id, 1).first()
            db.tvWriteDao().markEpisodesWatchedAt(mapOf(e.id to Instant.parse("2025-01-01T12:00:00Z")))
            val run = db.watchThroughDao().getLatest(id)!!
            assertEquals(
                run.id,
                db
                    .watchLogDao()
                    .observeForEpisode(e.id)
                    .first()
                    .single()
                    .watchThroughId,
            )
        }

    @Test
    fun removable_onlyLatest_notOne_noViewings() =
        runTest {
            val id = show()
            val one = db.watchThroughDao().getLatest(id)!!
            assertEquals(false, db.tvWriteDao().removeRun(one.id), "#1 never goes")
            val two = WatchThroughEntity(newId(), id, 2)
            db.watchThroughDao().insert(two)
            assertEquals(true, db.tvWriteDao().removeRun(two.id))
        }
}
