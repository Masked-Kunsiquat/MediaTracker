package com.hub.media.core.database

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookDetailsDaoTest {
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGetByMediaId_returnsInsertedDetails() =
        runTest {
            val media = sampleMediaItem()
            db.mediaItemDao().insert(media)
            val details = sampleBookDetails(mediaId = media.id)

            db.bookDetailsDao().insert(details)

            assertEquals(details, db.bookDetailsDao().getByMediaId(media.id))
        }

    /** Edge case required by AGENTS.md §7: a 0-page book must round-trip exactly, not as null. */
    @Test
    fun zeroPageBook_isStoredAndReadBackExactly() =
        runTest {
            val media = sampleMediaItem()
            db.mediaItemDao().insert(media)
            val details = sampleBookDetails(mediaId = media.id, totalPages = 0)

            db.bookDetailsDao().insert(details)

            assertEquals(0, db.bookDetailsDao().getByMediaId(media.id)?.totalPages)
        }

    @Test
    fun cascadeDelete_removesBookDetailsWhenMediaItemDeleted() =
        runTest {
            val media = sampleMediaItem()
            db.mediaItemDao().insert(media)
            db.bookDetailsDao().insert(sampleBookDetails(mediaId = media.id))

            db.mediaItemDao().delete(media)

            assertNull(db.bookDetailsDao().getByMediaId(media.id))
        }
}
