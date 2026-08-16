package com.hub.media.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/** Filename for the single production SQLite database (AGENTS.md §1), shared across platforms. */
internal const val APP_DATABASE_FILE_NAME = "media_tracker.db"

/**
 * Android [DatabaseFactory]: resolves the database file via `Context.getDatabasePath`, the
 * app-private per-app database directory Android provisions automatically, and wires the
 * KSP-generated [AppDatabaseConstructor] explicitly as the builder factory (matching the
 * pattern already used by the in-memory test builders) instead of relying on reflection.
 *
 * @param context Any [Context]; the application context is captured to avoid leaking an
 *   Activity/Service context into the long-lived database instance.
 */
public actual class DatabaseFactory(
    private val context: Context,
) {
    // Resolved once and reused by both create() and databaseFilePath() so the two can never
    // disagree about where the live database file lives.
    private val dbFile: File by lazy { context.applicationContext.getDatabasePath(APP_DATABASE_FILE_NAME) }

    public actual fun create(): RoomDatabase.Builder<AppDatabase> {
        val appContext = context.applicationContext
        return Room.databaseBuilder<AppDatabase>(
            context = appContext,
            name = dbFile.absolutePath,
            factory = { AppDatabaseConstructor.initialize() },
        )
    }

    public actual fun databaseFilePath(): String = dbFile.absolutePath
}
