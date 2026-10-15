package com.hub.media.ui

import androidx.room.execSQL
import androidx.room.useWriterConnection
import com.hub.media.core.database.AppDatabase

/**
 * Makes every read of `media_items` fail, for real, until [restoreMediaItems] -- so an edit
 * ViewModel's load path meets a genuine database exception rather than a fake that might not
 * behave like one. Renaming the table (rather than closing the database) is what lets a test then
 * put it back and prove that Retry recovers.
 */
internal suspend fun AppDatabase.breakMediaItems() {
    useWriterConnection { it.execSQL("ALTER TABLE media_items RENAME TO media_items_hidden") }
}

/** Undoes [breakMediaItems]. */
internal suspend fun AppDatabase.restoreMediaItems() {
    useWriterConnection { it.execSQL("ALTER TABLE media_items_hidden RENAME TO media_items") }
}
