package com.hub.media.core.database

import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.WatchLogEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The year in review's reads carry each title's stored cover (#229), so a highlight page can show it
 * without a second query. A title with no cover reads back `null`.
 */
class StatsDaoCoversTest {
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

    private val from = t("2025-01-01T00:00:00Z")
    private val to = t("2026-01-01T00:00:00Z")

    private suspend fun book(
        id: String,
        cover: String?,
    ) {
        db.mediaItemDao().insert(sampleMediaItem(id = id, title = id, coverImageHash = cover))
        db.bookDetailsDao().insert(sampleBookDetails(mediaId = id))
        db.readThroughDao().insert(
            ReadThroughEntity(
                "r-$id",
                id,
                1,
                ReadingStatus.FINISHED,
                finishedAt = t("2025-03-10T12:00:00Z"),
                rating = 8,
            ),
        )
        db.readingSessionDao().insert(
            sampleReadingSession(
                mediaId = id,
                timestampStart = t("2025-03-09T10:00:00Z"),
                timestampEnd = t("2025-03-09T11:00:00Z"),
                durationSeconds = 3_600,
                readThroughId = "r-$id",
            ),
        )
    }

    @Test
    fun theBookReads_carryTheBooksCover_orNull() =
        runTest {
            book("covered", "abc.jpg")
            book("bare", null)
            val expected = mapOf("covered" to "abc.jpg", "bare" to null)

            val stats = db.statsDao()
            assertEquals(
                expected,
                stats.observeTimedSessions(from, to).first().associate { it.mediaId to it.coverImageHash },
            )
            assertEquals(
                expected,
                stats
                    .observeFinishedBooks(from, to, ReadingStatus.FINISHED)
                    .first()
                    .associate { it.mediaId to it.coverImageHash },
            )
            assertEquals(
                expected,
                stats.observeRatedItems(from, to, MediaType.BOOK).first().associate { it.mediaId to it.coverImageHash },
            )
        }

    @Test
    fun aFilmViewing_carriesItsPoster() =
        runTest {
            db.mediaItemDao().insert(
                sampleMediaItem(id = "f", title = "Arrival", type = MediaType.MOVIE, coverImageHash = "poster.jpg"),
            )
            db.movieWriteDao().insertViewing(
                WatchLogEntity(id = "w", mediaId = "f", watchedAt = t("2025-05-01T20:00:00Z"), rating = 9),
            )

            assertEquals(
                listOf("poster.jpg"),
                db
                    .statsDao()
                    .observeViewings(from, to, MediaType.MOVIE)
                    .first()
                    .map { it.coverImageHash },
            )
            assertEquals(
                listOf("poster.jpg"),
                db
                    .statsDao()
                    .observeRatedItems(from, to, MediaType.MOVIE)
                    .first()
                    .map { it.coverImageHash },
            )
        }
}
