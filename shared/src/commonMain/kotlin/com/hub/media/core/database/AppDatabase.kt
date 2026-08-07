package com.hub.media.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.hub.media.core.database.converters.Converters
import com.hub.media.core.database.dao.AppSettingsDao
import com.hub.media.core.database.dao.BookDetailsDao
import com.hub.media.core.database.dao.BookWriteDao
import com.hub.media.core.database.dao.ExternalIdentifierDao
import com.hub.media.core.database.dao.ImportWriteDao
import com.hub.media.core.database.dao.MediaItemDao
import com.hub.media.core.database.dao.ReadingSessionDao
import com.hub.media.core.database.dao.StatsDao
import com.hub.media.core.database.entities.AppSettingEntity
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.ReadingSessionEntity

/**
 * The current Room schema version, as a named constant rather than a bare literal in the
 * [Database] annotation below -- ROADMAP Task 8 Phase C (`.sqlite` backup/restore) needs this
 * exact number outside of Room's own annotation processing, to reject a restored file whose own
 * `PRAGMA user_version` (parsed directly from its raw header bytes by
 * [com.hub.media.core.database.parseSqliteHeader], no SQLite connection required) is newer than
 * this build understands, before Room ever tries to open it. Keeping one named constant (used both
 * here and by [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase]) means the
 * two can never silently drift apart the way two independent literals could.
 */
public const val APP_DATABASE_VERSION: Int = 5

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
 *
 * Version 3 (ROADMAP Task 6 Phase C) adds [BookDetailsEntity.status] and
 * [BookDetailsEntity.finishedAt] — see those properties' KDoc — via [MIGRATION_2_3]
 * (`Migrations.kt`), wired in by [com.hub.media.core.database.buildAppDatabase].
 *
 * Version 4 (ROADMAP Task 7 Phase A) adds [BookDetailsEntity.trackingMode] (see
 * [com.hub.media.core.database.entities.TrackingMode]'s KDoc) and the new [AppSettingEntity]
 * key-value settings table (see its KDoc for why that shape was chosen over a typed table), both
 * via [MIGRATION_3_4] (`Migrations.kt`), wired in by [com.hub.media.core.database.buildAppDatabase].
 *
 * [ImportWriteDao] (ROADMAP Task 8 Phase B, CSV import's single all-or-nothing write transaction)
 * was likewise added on top of version 4 without a further version bump -- same reasoning as
 * [StatsDao] above: a new DAO changes the Kotlin-visible query surface only, not the exported
 * schema (no `@Entity` was added, changed, or removed).
 *
 * Version 5 (ROADMAP Task 9 Phase A) adds [BookDetailsEntity.authors] -- see that property's KDoc
 * for the denormalized-column-vs-authors-table rationale -- via [MIGRATION_4_5] (`Migrations.kt`),
 * wired in by [com.hub.media.core.database.buildAppDatabase].
 */
@Database(
    entities = [
        MediaItemEntity::class,
        BookDetailsEntity::class,
        ExternalIdentifierEntity::class,
        ReadingSessionEntity::class,
        AppSettingEntity::class,
    ],
    version = APP_DATABASE_VERSION,
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
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun importWriteDao(): ImportWriteDao
}

/**
 * Room KMP's `@ConstructedBy` expect/actual entry point. The Room KSP compiler plugin
 * generates the platform `actual object` bodies for the android/jvm targets automatically —
 * do not hand-write actuals for this declaration.
 */
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
