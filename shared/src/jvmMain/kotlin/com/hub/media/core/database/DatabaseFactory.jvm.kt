package com.hub.media.core.database

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/** Directory name for this app's data under the user's home directory (JVM/desktop target). */
private const val APP_DATA_DIR_NAME = ".mediatracker"

/** Filename for the single production SQLite database (AGENTS.md §1), shared across platforms. */
private const val APP_DATABASE_FILE_NAME = "media_tracker.db"

/**
 * JVM [DatabaseFactory]: no platform handle is required (unlike Android's `Context`), so this
 * takes no constructor parameters. Writes to `~/.mediatracker/media_tracker.db`, creating the
 * directory on first use — this keeps the jvm target a real (if secondary) deployment target
 * rather than test-only scaffolding.
 */
public actual class DatabaseFactory {
    public actual fun create(): RoomDatabase.Builder<AppDatabase> {
        val appDataDir = File(System.getProperty("user.home"), APP_DATA_DIR_NAME)
        appDataDir.mkdirs()
        val dbFile = File(appDataDir, APP_DATABASE_FILE_NAME)
        return Room.databaseBuilder<AppDatabase>(
            name = dbFile.absolutePath,
            factory = { AppDatabaseConstructor.initialize() },
        )
    }
}
