package com.hub.media.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.hub.media.core.database.converters.Converters
import com.hub.media.core.database.dao.BookDetailsDao
import com.hub.media.core.database.dao.BookWriteDao
import com.hub.media.core.database.dao.ExternalIdentifierDao
import com.hub.media.core.database.dao.MediaItemDao
import com.hub.media.core.database.dao.ReadingSessionDao
import com.hub.media.core.database.dao.StatsDao
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.ReadingSessionEntity

/**
 * The single local SQLite database for the app (AGENTS.md §1: "single local SQLite database,
 * zero required external cloud sync"). Schema is exported to `shared/schemas` per the
 * `room { schemaDirectory(...) }` config in shared/build.gradle.kts so future migrations can be
 * verified against a real history.
 *
 * Version 1 froze at `v0.1.0` (AGENTS.md §8). Version 2 (ROADMAP Task 5 pre-phase) makes
 * [ReadingSessionEntity.durationSeconds] nullable — see its KDoc — via [Migration_1_2]
 * (`Migrations.kt`), wired in by [com.hub.media.core.database.buildAppDatabase].
 *
 * [StatsDao] (ROADMAP Task 5 stats layer) was added on top of version 2 without a further
 * version bump: registering a new DAO changes the Kotlin-visible query surface, not the exported
 * schema, which is derived solely from `@Entity`-annotated tables — no table, column, or index
 * changed, so the schema hash is unaffected.
 */
@Database(
    entities = [
        MediaItemEntity::class,
        BookDetailsEntity::class,
        ExternalIdentifierEntity::class,
        ReadingSessionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun bookDetailsDao(): BookDetailsDao
    abstract fun externalIdentifierDao(): ExternalIdentifierDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun bookWriteDao(): BookWriteDao
    abstract fun statsDao(): StatsDao
}

/**
 * Room KMP's `@ConstructedBy` expect/actual entry point. The Room KSP compiler plugin
 * generates the platform `actual object` bodies for the android/jvm targets automatically —
 * do not hand-write actuals for this declaration.
 */
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
