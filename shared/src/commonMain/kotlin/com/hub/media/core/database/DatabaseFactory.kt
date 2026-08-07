package com.hub.media.core.database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Platform entry point for obtaining a [RoomDatabase.Builder] for the production [AppDatabase]
 * (as opposed to [inMemoryAppDatabaseBuilder] in commonTest, which is throwaway/in-memory).
 *
 * Android needs a real `android.content.Context` to resolve the database file via
 * `Context.getDatabasePath`; the JVM target needs nothing beyond a plain file path. Since a
 * single `expect`/`actual` function must share one signature across all platforms, this follows
 * the official Room KMP pattern instead: an `expect class` with a no-arg [create] method, where
 * each platform's `actual class` declares whatever constructor it needs for its own platform
 * handle. No platform types (e.g. `Context`) leak into this file or any other commonMain code.
 */
public expect class DatabaseFactory {
    /** Builds a [RoomDatabase.Builder] pointed at this platform's persistent database file. */
    public fun create(): RoomDatabase.Builder<AppDatabase>

    /**
     * The absolute path of this platform's persistent database file -- the same path [create]
     * points its [RoomDatabase.Builder] at. Exposed for ROADMAP Task 8 Phase C (`.sqlite`
     * backup/restore): [com.hub.media.ui.AppContainer] wires this into
     * [com.hub.media.features.portability.domain.DefaultDatabaseBackupUseCase]/
     * [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase], which need the
     * real on-disk file location to `VACUUM INTO` a backup snapshot and to swap a validated
     * replacement into place, respectively.
     */
    public fun databaseFilePath(): String
}

/**
 * Applies the shared production configuration to a platform-supplied [builder] and builds the
 * database: the bundled SQLite driver (AGENTS.md §1 "single local SQLite database" — no
 * separate native driver dependency to manage per platform), query execution on
 * [Dispatchers.IO] so database work never runs on the caller's dispatcher, and every tested
 * schema [Migration][androidx.room.migration.Migration] (currently [MIGRATION_1_2],
 * [MIGRATION_2_3], [MIGRATION_3_4], and [MIGRATION_4_5]). Both platform [DatabaseFactory] actuals
 * (android/jvm) route through this single function, so a migration only ever needs to be
 * registered here once.
 */
public fun buildAppDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()
