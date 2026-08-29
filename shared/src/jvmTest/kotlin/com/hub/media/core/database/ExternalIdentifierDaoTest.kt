package com.hub.media.core.database

import com.hub.media.core.database.entities.IdentifierProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalIdentifierDaoTest {
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    /** Composite PK (mediaId, provider): re-inserting the same pair must replace, not conflict. */
    @Test
    fun insert_conflictingCompositeKey_replacesExistingRow() =
        runTest {
            val media = sampleMediaItem()
            db.mediaItemDao().insert(media)

            db.externalIdentifierDao().insert(
                sampleExternalIdentifier(mediaId = media.id, provider = IdentifierProvider.ISBN, externalId = "OLD"),
            )
            db.externalIdentifierDao().insert(
                sampleExternalIdentifier(mediaId = media.id, provider = IdentifierProvider.ISBN, externalId = "NEW"),
            )

            val all = db.externalIdentifierDao().observeForMedia(media.id).first()
            assertEquals(1, all.size)
            assertEquals("NEW", all.single().externalId)
        }

    @Test
    fun observeForMedia_emitsRowsForDifferentProviders() =
        runTest {
            val media = sampleMediaItem()
            db.mediaItemDao().insert(media)

            db.externalIdentifierDao().insert(sampleExternalIdentifier(media.id, IdentifierProvider.ISBN, "isbn-1"))
            db.externalIdentifierDao().insert(
                sampleExternalIdentifier(media.id, IdentifierProvider.OPEN_LIBRARY, "ol-1"),
            )

            assertEquals(
                2,
                db
                    .externalIdentifierDao()
                    .observeForMedia(media.id)
                    .first()
                    .size,
            )
        }

    @Test
    fun cascadeDelete_removesIdentifiersWhenMediaItemDeleted() =
        runTest {
            val media = sampleMediaItem()
            db.mediaItemDao().insert(media)
            db.externalIdentifierDao().insert(sampleExternalIdentifier(media.id))

            db.mediaItemDao().delete(media)

            assertTrue(
                db
                    .externalIdentifierDao()
                    .observeForMedia(media.id)
                    .first()
                    .isEmpty(),
            )
            assertNull(db.externalIdentifierDao().getByKey(media.id, IdentifierProvider.ISBN))
        }
}
