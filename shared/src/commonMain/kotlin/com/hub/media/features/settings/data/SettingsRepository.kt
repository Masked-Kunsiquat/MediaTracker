package com.hub.media.features.settings.data

import com.hub.media.core.database.dao.AppSettingsDao
import com.hub.media.core.database.entities.AppSettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Typed access to the app-wide `app_settings` key-value store (schema v4,
 * [AppSettingEntity]/[AppSettingsDao], ROADMAP Task 7 Phase A). Every consumer of this repository
 * works with typed values ([String]/[Int]/[Boolean]) and a `const val` key it defines itself — the
 * raw [AppSettingEntity] row shape and the fact that every value is physically stored as `TEXT`
 * (see [AppSettingEntity]'s KDoc for why) never leak past this file.
 *
 * ### Scope of this phase
 * No concrete setting is defined here yet — placed in the directory this project's blueprint
 * (AGENTS.md §6) reserves for `features/<feature>/data`, mirroring
 * [com.hub.media.features.books.data.BookRepository] and
 * [com.hub.media.features.stats.data.StatsRepository]'s package placement, even though "settings"
 * has no dedicated UI/domain layer yet. ROADMAP Task 7 Phase B is the first real consumer (a
 * week-start-day preference driving the Stats screen's period bounds) and is expected to add its
 * own small wrapper on top of [observeInt]/[setInt] (e.g. a `WeekStartDay` enum <-> `Int` mapping
 * behind a dedicated `const val` key) rather than route call sites through this repository's
 * generic accessors directly — this phase only guarantees the store *can* hold that setting, not
 * its semantics (AGENTS.md §8 Room Schema Freeze Rule: the whole point of the key-value shape is
 * that Phase B's setting needs no further migration to exist).
 *
 * @param dao Backing [AppSettingsDao].
 */
public class SettingsRepository(private val dao: AppSettingsDao) {

    /**
     * Reactive current [String] value stored under [key], or null if never set (or cleared via
     * [clear]).
     */
    public fun observeString(key: String): Flow<String?> =
        dao.observeByKey(key).map { it?.value }

    /** One-shot fetch of [key]'s current [String] value, or null if never set. */
    public suspend fun getString(key: String): String? = dao.getByKey(key)?.value

    /** Upserts [value] under [key]. */
    public suspend fun setString(key: String, value: String) {
        dao.upsert(AppSettingEntity(key = key, value = value))
    }

    /**
     * Reactive current [Int] value stored under [key], or null if never set OR if the stored value
     * fails to parse as an [Int] (a malformed value is treated the same as "unset" rather than
     * throwing — see [AppSettingEntity]'s KDoc on why no SQL-level type constraint backs a single
     * setting's value).
     */
    public fun observeInt(key: String): Flow<Int?> = observeString(key).map { it?.toIntOrNull() }

    /** One-shot fetch of [key]'s current [Int] value; see [observeInt] for the malformed-value rule. */
    public suspend fun getInt(key: String): Int? = getString(key)?.toIntOrNull()

    /** Upserts [value] under [key], stored as its decimal string form. */
    public suspend fun setInt(key: String, value: Int) = setString(key, value.toString())

    /**
     * Reactive current [Boolean] value stored under [key] ("true"/"false", case-insensitive), or
     * null if never set OR malformed — see [observeInt]'s malformed-value rule, applied identically
     * here via [String.toBooleanStrictOrNull].
     */
    public fun observeBoolean(key: String): Flow<Boolean?> =
        observeString(key).map { it?.toBooleanStrictOrNull() }

    /** One-shot fetch of [key]'s current [Boolean] value; see [observeBoolean]'s malformed-value rule. */
    public suspend fun getBoolean(key: String): Boolean? = getString(key)?.toBooleanStrictOrNull()

    /** Upserts [value] under [key], stored as `"true"`/`"false"`. */
    public suspend fun setBoolean(key: String, value: Boolean) = setString(key, value.toString())

    /** Removes [key] entirely, reverting every accessor above to null (never-set) for it. */
    public suspend fun clear(key: String) = dao.deleteByKey(key)
}
