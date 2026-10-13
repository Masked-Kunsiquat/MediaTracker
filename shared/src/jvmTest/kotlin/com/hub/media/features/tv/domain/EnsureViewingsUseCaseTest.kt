package com.hub.media.features.tv.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.testAppDatabase
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
import kotlin.test.assertIs
import kotlin.time.Instant

/** [EnsureViewingsUseCase] (#90): the startup backfill gives every watched episode a viewing, once. */
class EnsureViewingsUseCaseTest {
    private val t1 = Instant.parse("2024-03-03T12:00:00Z")
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
    }

    @AfterTest
    fun tearDown() = db.close()

    /** A show of one quick-filled season; returns its media id. */
    private suspend fun show(): String {
        val r = TVShowRepository(db).addShow(title = "S", seasons = listOf(SeasonQuickFill(1, episodeCount = 1)))
        assertIs<Resource.Success<String>>(r)
        return r.data
    }

    /** An episode written straight to the table with `watchedAt` and no viewing -- a pre-#90 row. */
    private suspend fun legacyWatched(mediaId: String): String {
        val e = EpisodeEntity(newId(), mediaId, 1, 2, watchedAt = t1)
        db.tvWriteDao().insertEpisodes(listOf(e))
        return e.id
    }

    private suspend fun viewings(id: String) = db.watchLogDao().observeForEpisode(id).first()

    @Test
    fun execute_createsOneViewingPerWatchedEpisodeWithout() =
        runTest {
            val id = legacyWatched(show())
            assertEquals(Resource.Success(1), EnsureViewingsUseCase(db).execute())
            assertEquals(listOf(t1), viewings(id).map { it.watchedAt })
        }

    @Test
    fun execute_twice_isIdempotent() =
        runTest {
            val id = legacyWatched(show())
            EnsureViewingsUseCase(db).execute()
            assertEquals(Resource.Success(0), EnsureViewingsUseCase(db).execute())
            assertEquals(1, viewings(id).size)
        }

    @Test
    fun execute_ignoresUnwatchedAndAlreadyLogged() =
        runTest {
            val mediaId = show()
            val unwatched =
                db
                    .episodeDao()
                    .getByMediaIdAndSeason(mediaId, 1)
                    .single()
                    .id
            val logged = legacyWatched(mediaId)
            db.tvWriteDao().addViewing(logged, t1)

            assertEquals(Resource.Success(0), EnsureViewingsUseCase(db).execute())
            assertEquals(0, viewings(unwatched).size)
            assertEquals(1, viewings(logged).size)
        }
}
