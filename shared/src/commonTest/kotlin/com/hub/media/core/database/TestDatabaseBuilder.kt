package com.hub.media.core.database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Platform-specific in-memory [AppDatabase] builder. JVM can build one with no platform
 * handle at all; Android requires a real `android.content.Context`, which only exists in
 * instrumented tests — see the androidUnitTest actual for why DAO tests don't execute there.
 */
internal expect fun inMemoryAppDatabaseBuilder(): RoomDatabase.Builder<AppDatabase>

/** Builds a throwaway in-memory [AppDatabase] wired with the bundled SQLite driver for tests. */
internal fun testAppDatabase(coroutineContext: CoroutineContext = Dispatchers.Default): AppDatabase =
    inMemoryAppDatabaseBuilder()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(coroutineContext)
        .build()
