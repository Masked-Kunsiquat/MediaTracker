package com.hub.media.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.hub.media.core.database.converters.Converters
import com.hub.media.core.database.dao.BookDetailsDao
import com.hub.media.core.database.dao.ExternalIdentifierDao
import com.hub.media.core.database.dao.MediaItemDao
import com.hub.media.core.database.dao.ReadingSessionDao
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.ReadingSessionEntity

/**
 * The single local SQLite database for the app (AGENTS.md §1: "single local SQLite database,
 * zero required external cloud sync"). Version 1 — schema is exported to `shared/schemas` per
 * the `room { schemaDirectory(...) }` config in shared/build.gradle.kts so future migrations
 * can be verified against a real history.
 */
@Database(
    entities = [
        MediaItemEntity::class,
        BookDetailsEntity::class,
        ExternalIdentifierEntity::class,
        ReadingSessionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun bookDetailsDao(): BookDetailsDao
    abstract fun externalIdentifierDao(): ExternalIdentifierDao
    abstract fun readingSessionDao(): ReadingSessionDao
}

/**
 * Room KMP's `@ConstructedBy` expect/actual entry point. The Room KSP compiler plugin
 * generates the platform `actual object` bodies for the android/jvm targets automatically —
 * do not hand-write actuals for this declaration.
 */
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
