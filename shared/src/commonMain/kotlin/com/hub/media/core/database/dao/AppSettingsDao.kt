package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hub.media.core.database.entities.AppSettingEntity
import kotlinx.coroutines.flow.Flow

/**
 * Raw key-value DAO backing `app_settings` (schema v4, ROADMAP Task 7 Phase A). Not intended for
 * direct use outside [com.hub.media.features.settings.data.SettingsRepository] — see
 * [AppSettingEntity]'s KDoc for why the key-value shape was chosen, and that repository for the
 * typed accessors that hide these raw string reads/writes from the rest of the app.
 *
 * `key` is backtick-quoted in every query below because it is a SQL keyword in SQLite (and would
 * otherwise be ambiguous/rejected in some contexts) — [AppSettingEntity.key]'s generated column name
 * is still the plain identifier `key`.
 */
@Dao
public interface AppSettingsDao {

    /**
     * Inserts or replaces [setting] by its [AppSettingEntity.key] — the only write this table ever
     * needs, since every setting is a single upserted row (no separate insert-then-update distinction
     * for a key-value table).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(setting: AppSettingEntity)

    /** One-shot fetch of [key]'s current [AppSettingEntity], or null if never set. */
    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    public suspend fun getByKey(key: String): AppSettingEntity?

    /** Reactive stream of [key]'s [AppSettingEntity], emitting null if never set (or cleared). */
    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    public fun observeByKey(key: String): Flow<AppSettingEntity?>

    /** Removes [key]'s row entirely, reverting reads of it to null (never-set). */
    @Query("DELETE FROM app_settings WHERE `key` = :key")
    public suspend fun deleteByKey(key: String)
}
