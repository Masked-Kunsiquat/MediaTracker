package com.hub.media.core.database

import com.hub.media.core.database.dao.ViewingRow
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchLogEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The one read behind the Films and Shows tabs (#217): each viewing with its title, the runtime of
 * the film or episode watched, and whether it was a rewatch.
 */
class ViewingsDaoTest {
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

    private var nextLog = 0

    private suspend fun film(
        id: String,
        title: String,
        runtimeMinutes: Int?,
    ) {
        db.movieWriteDao().insertMediaItem(sampleMediaItem(id = id, title = title, type = MediaType.MOVIE))
        db.movieWriteDao().insertMovieDetails(MovieDetailsEntity(mediaId = id, runtimeMinutes = runtimeMinutes))
    }

    private suspend fun show(id: String) {
        db.tvWriteDao().insertMediaItem(sampleMediaItem(id = id, title = "Severance", type = MediaType.TV_SHOW))
        db.tvWriteDao().insertTVDetails(TVDetailsEntity(mediaId = id))
        db.tvWriteDao().insertEpisodes(
            listOf(
                EpisodeEntity(id = "e1", mediaId = id, seasonNumber = 1, episodeNumber = 1, runtimeMinutes = 45),
                EpisodeEntity(id = "e2", mediaId = id, seasonNumber = 1, episodeNumber = 2, runtimeMinutes = null),
            ),
        )
    }

    private suspend fun watch(
        mediaId: String,
        atSeconds: Long,
        episodeId: String? = null,
        id: String = "log-${nextLog++}",
    ) {
        db.movieWriteDao().insertViewing(WatchLogEntity(id = id, mediaId = mediaId, episodeId = episodeId, watchedAt = at(atSeconds)))
    }

    private suspend fun viewings(
        type: MediaType,
        from: Long? = null,
        to: Long? = null,
    ) = db.statsDao().observeViewings(from?.let(::at), to?.let(::at), type).first().sortedBy { it.watchedAt }

    @Test
    fun filmsAndShows_areSeparate_andCarryTheirRuntime() =
        runTest {
            film("f1", "Arrival", runtimeMinutes = 116)
            film("f2", "Mystery Film", runtimeMinutes = null)
            show("s1")
            watch("f1", 1_000)
            watch("f2", 1_100)
            watch("s1", 1_200, episodeId = "e1")
            watch("s1", 1_300, episodeId = "e2")

            assertEquals(
                listOf(
                    ViewingRow(at(1_000), "f1", null, "Arrival", 116, false),
                    ViewingRow(at(1_100), "f2", null, "Mystery Film", null, false),
                ),
                viewings(MediaType.MOVIE),
            )
            assertEquals(listOf(45, null), viewings(MediaType.TV_SHOW).map { it.runtimeMinutes })
            assertEquals(listOf("e1", "e2"), viewings(MediaType.TV_SHOW).map { it.episodeId })
        }

    @Test
    fun theBounds_includeTheStart_andExcludeTheEnd() =
        runTest {
            film("f1", "Arrival", 116)
            watch("f1", 999)
            watch("f1", 1_000)
            watch("f1", 2_000)

            assertEquals(listOf(at(1_000)), viewings(MediaType.MOVIE, 1_000, 2_000).map { it.watchedAt })
            assertEquals(3, viewings(MediaType.MOVIE).size, "no bounds is All time")
        }

    @Test
    fun aSecondViewing_isARewatch_evenWhenTheFirstIsBeforeThePeriod() =
        runTest {
            film("f1", "Arrival", 116)
            watch("f1", 500) // before the period
            watch("f1", 1_500)

            assertEquals(listOf(true), viewings(MediaType.MOVIE, 1_000, 2_000).map { it.isRewatch })
            assertEquals(listOf(false, true), viewings(MediaType.MOVIE).map { it.isRewatch })
        }

    @Test
    fun twoViewingsAtTheSameInstant_makeExactlyOneRewatch() =
        runTest {
            film("f1", "Arrival", 116)
            watch("f1", 1_000, id = "a")
            watch("f1", 1_000, id = "b")

            assertEquals(1, viewings(MediaType.MOVIE).count { it.isRewatch })
        }

    @Test
    fun aRewatchIsPerEpisode_notPerShow() =
        runTest {
            show("s1")
            watch("s1", 1_000, episodeId = "e1")
            watch("s1", 1_100, episodeId = "e2") // a new episode: not a rewatch
            watch("s1", 1_200, episodeId = "e1") // episode 1 again: a rewatch

            assertEquals(listOf(false, false, true), viewings(MediaType.TV_SHOW).map { it.isRewatch })
        }
}
