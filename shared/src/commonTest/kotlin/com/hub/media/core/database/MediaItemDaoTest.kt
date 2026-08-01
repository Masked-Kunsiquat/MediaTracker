package com.hub.media.core.database

import com.hub.media.core.util.newId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class MediaItemDaoTest {

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
    fun insertAndGetById_returnsInsertedItem() = runTest {
        val item = sampleMediaItem(title = "Project Hail Mary")

        db.mediaItemDao().insert(item)

        assertEquals(item, db.mediaItemDao().getById(item.id))
    }

    @Test
    fun getById_unknownId_returnsNull() = runTest {
        assertNull(db.mediaItemDao().getById(newId()))
    }

    @Test
    fun observeAll_emitsInsertedRow() = runTest {
        assertTrue(db.mediaItemDao().observeAll().first().isEmpty())

        val item = sampleMediaItem()
        db.mediaItemDao().insert(item)

        val updated = db.mediaItemDao().observeAll().first { it.isNotEmpty() }
        assertEquals(listOf(item.id), updated.map { it.id })
    }

    @Test
    fun update_persistsChangedFields() = runTest {
        val item = sampleMediaItem(title = "Original Title")
        db.mediaItemDao().insert(item)

        val changed = item.copy(title = "Updated Title", purchasePrice = 14.99)
        db.mediaItemDao().update(changed)

        assertEquals(changed, db.mediaItemDao().getById(item.id))
    }

    @Test
    fun delete_removesItem() = runTest {
        val item = sampleMediaItem()
        db.mediaItemDao().insert(item)

        db.mediaItemDao().delete(item)

        assertNull(db.mediaItemDao().getById(item.id))
    }
}
