package com.hub.media.ui

import android.content.Context
import com.hub.media.core.database.DatabaseFactory
import com.hub.media.core.database.buildAppDatabase
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.coverStorageDirectory

/**
 * Builds a production [AppContainer] from an Android [Context]: constructs the on-device
 * [AppDatabase][com.hub.media.core.database.AppDatabase] (via [DatabaseFactory]) and the cover
 * image store rooted at `filesDir/covers`, then wires them together.
 *
 * Intended to be called once per process (e.g. lazily from `Application` or a singleton holder)
 * and the result shared — this constructs a real database connection.
 */
public fun createAppContainer(context: Context): AppContainer {
    val database = buildAppDatabase(DatabaseFactory(context).create())
    val imageStorage = LocalImageStorageManager(coverStorageDirectory(context))
    return AppContainer(database = database, imageStorage = imageStorage)
}
