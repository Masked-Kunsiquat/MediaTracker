package com.hub.media.core.database

import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.entities.WatchThroughEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** [com.hub.media.core.database.dao.StatsDao.observeRatedItems] (#232): what a rating is dated by. */
class StatsDaoRatedItemsTest {
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun t(s: String) = Instant.parse(s)

    private val march = t("2025-03-01T00:00:00Z") to t("2025-04-01T00:00:00Z")

    private suspend fun rated(
        type: MediaType,
        from: Instant?,
        to: Instant?,
    ) = db.statsDao().observeRatedItems(from, to, type).first()

    private suspend fun book(id: String) = db.mediaItemDao().insert(sampleMediaItem(id = id, title = id))

    @Test
    fun aFinishedRead_isDatedByItsFinish_andAnUnratedOneIsNeverReturned() =
        runTest {
            book("b")
            db.readThroughDao().insert(
                ReadThroughEntity(
                    "r1",
                    "b",
                    1,
                    ReadingStatus.FINISHED,
                    finishedAt = t("2025-03-10T12:00:00Z"),
                    rating = 8,
                ),
            )
            db.readThroughDao().insert(
                ReadThroughEntity("r2", "b", 2, ReadingStatus.FINISHED, finishedAt = t("2025-03-11T12:00:00Z")),
            )
            assertEquals(listOf(8), rated(MediaType.BOOK, march.first, march.second).map { it.rating })
        }

    @Test
    fun aDnfRead_isDatedByItsLatestSession_elseByItsStart() =
        runTest {
            book("b")
            db.readThroughDao().insert(
                ReadThroughEntity("r1", "b", 1, ReadingStatus.DNF, startedAt = t("2025-02-01T12:00:00Z"), rating = 4),
            )
            db.readingSessionDao().insert(
                sampleReadingSession(
                    mediaId = "b",
                    timestampStart = t("2025-03-20T10:00:00Z"),
                    timestampEnd = t("2025-03-20T11:00:00Z"),
                    readThroughId = "r1",
                ),
            )
            book("c")
            db.readThroughDao().insert(
                ReadThroughEntity("r9", "c", 1, ReadingStatus.DNF, startedAt = t("2025-03-05T12:00:00Z"), rating = 2),
            )
            val inMarch = rated(MediaType.BOOK, march.first, march.second)
            assertEquals(t("2025-03-20T11:00:00Z"), inMarch.first { it.mediaId == "b" }.at)
            assertEquals(t("2025-03-05T12:00:00Z"), inMarch.first { it.mediaId == "c" }.at)
        }

    @Test
    fun periodEdges_includeFrom_andExcludeTo() =
        runTest {
            db.movieWriteDao().insertMediaItem(sampleMediaItem(id = "f", title = "Arrival", type = MediaType.MOVIE))
            db.movieWriteDao().insertViewing(WatchLogEntity("v1", "f", watchedAt = march.first, rating = 10))
            db.movieWriteDao().insertViewing(WatchLogEntity("v2", "f", watchedAt = march.second, rating = 2))
            assertEquals(listOf(10), rated(MediaType.MOVIE, march.first, march.second).map { it.rating })
        }

    @Test
    fun aWatchThrough_isDatedByItsLatestViewing_andADatelessOne_isAllTimeOnly() =
        runTest {
            db.tvWriteDao().insertMediaItem(sampleMediaItem(id = "s", title = "Chernobyl", type = MediaType.TV_SHOW))
            db.tvWriteDao().insertEpisodes(
                listOf(EpisodeEntity(id = "e1", mediaId = "s", seasonNumber = 1, episodeNumber = 1)),
            )
            db.watchThroughDao().insert(WatchThroughEntity("w1", "s", 1, rating = 9))
            db.watchThroughDao().insert(WatchThroughEntity("w2", "s", 2, abandoned = true, rating = 3))
            db.movieWriteDao().insertViewing(
                WatchLogEntity(
                    "l1",
                    "s",
                    episodeId = "e1",
                    watchedAt = t("2025-02-27T20:00:00Z"),
                    watchThroughId = "w1",
                ),
            )
            db.movieWriteDao().insertViewing(
                WatchLogEntity(
                    "l2",
                    "s",
                    episodeId = "e1",
                    watchedAt = t("2025-03-02T20:00:00Z"),
                    watchThroughId = "w1",
                ),
            )
            assertEquals(
                listOf(9 to t("2025-03-02T20:00:00Z")),
                rated(MediaType.TV_SHOW, march.first, march.second).map { it.rating to it.at },
            )
            assertEquals(setOf(9, 3), rated(MediaType.TV_SHOW, null, null).map { it.rating }.toSet())
        }

    @Test
    fun eachTab_seesOnlyItsOwnType_andEpisodeViewingsAreNotFilmRatings() =
        runTest {
            book("b")
            db.readThroughDao().insert(
                ReadThroughEntity(
                    "r1",
                    "b",
                    1,
                    ReadingStatus.FINISHED,
                    finishedAt = t("2025-03-10T12:00:00Z"),
                    rating = 8,
                ),
            )
            db.movieWriteDao().insertMediaItem(sampleMediaItem(id = "f", title = "Arrival", type = MediaType.MOVIE))
            db.movieWriteDao().insertViewing(
                WatchLogEntity("v1", "f", watchedAt = t("2025-03-10T12:00:00Z"), rating = 6),
            )
            assertEquals(listOf("b"), rated(MediaType.BOOK, null, null).map { it.mediaId })
            assertEquals(listOf("f"), rated(MediaType.MOVIE, null, null).map { it.mediaId })
            assertEquals(emptyList(), rated(MediaType.TV_SHOW, null, null))
        }
}
