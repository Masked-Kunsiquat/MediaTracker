package com.hub.media.ui

import android.content.Context
import com.hub.media.core.database.DatabaseFactory
import com.hub.media.core.database.buildAppDatabase
import com.hub.media.core.database.consumeRestoreMarker
import com.hub.media.core.database.selfHealDatabaseIfNeeded
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.LogFileStore
import com.hub.media.core.storage.coverStorageDirectory
import kotlinx.coroutines.runBlocking

/**
 * Builds a production [AppContainer] from an Android [Context]: constructs the on-device
 * [AppDatabase][com.hub.media.core.database.AppDatabase] (via [DatabaseFactory]) and the cover
 * image store rooted at `filesDir/covers`, then wires them together.
 *
 * Intended to be called once per process (e.g. lazily from `Application` or a singleton holder)
 * and the result shared — this constructs a real database connection. The returned
 * [AppContainer] owns this database (see [AppContainer]'s "Ownership" section) and closes it
 * from [AppContainer.close].
 *
 * ### Restore recovery, run before Room ever opens anything (ROADMAP Task 8 Phase C)
 * [selfHealDatabaseIfNeeded] and [consumeRestoreMarker] both do a single fast local-file check in
 * the overwhelmingly common case (no restore was ever attempted, or the marker from a completed
 * one was already consumed on a prior launch) -- a brief [runBlocking] here is deliberate rather
 * than restructuring this function and both of its call sites (`MediaTrackerApplication`'s lazy
 * property, `MainActivity.onCreate`) to be suspending, for what is normally sub-millisecond work
 * that must complete *before* [DatabaseFactory.create]/[buildAppDatabase] touches the database
 * file, not after.
 *
 * @param logFileStore The already-constructed persistent log store (ROADMAP Task 15 Phase B),
 *   forwarded straight into [AppContainer] -- see that constructor parameter's KDoc for why this
 *   function does not build its own [LogFileStore] the way it builds [imageStorage]/[database]:
 *   `MediaTrackerApplication.onCreate` must already have one (and have wired it into
 *   [com.hub.media.core.util.AppLogger]) before this function's lazy call site is ever reached.
 */
public fun createAppContainer(context: Context, logFileStore: LogFileStore): AppContainer {
    val databaseFactory = DatabaseFactory(context)
    val databaseFilePath = databaseFactory.databaseFilePath()
    val pendingRestoreMarker = runBlocking {
        selfHealDatabaseIfNeeded(databaseFilePath)
        consumeRestoreMarker(databaseFilePath)
    }
    val database = buildAppDatabase(databaseFactory.create())
    val imageStorage = LocalImageStorageManager(coverStorageDirectory(context))
    return AppContainer(
        database = database,
        imageStorage = imageStorage,
        databaseFilePath = databaseFilePath,
        logFileStore = logFileStore,
        pendingRestoreMarker = pendingRestoreMarker,
    )
}
