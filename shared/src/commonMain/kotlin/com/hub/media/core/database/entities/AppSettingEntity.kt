package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single app-wide preference, stored as a `(key, value)` pair (schema v4, ROADMAP Task 7 Phase A).
 *
 * ### Why a key-value table rather than a single-row typed settings table
 * Two shapes were weighed for the app's first settings store:
 * 1. **A single-row typed table** — one row, one column per setting (e.g. `weekStartDay INTEGER`) —
 *    mirrors how [BookDetailsEntity] models a book's fields directly, and gives compile-time typing
 *    with no string keys anywhere.
 * 2. **A key-value table** (this entity) — one row per setting, [key] as the primary key, [value]
 *    always stored as `TEXT`.
 *
 * The key-value shape was chosen because **every future setting this store will ever hold requires
 * only an `INSERT`/`UPDATE` of a new row, never a schema migration**, which matters specifically
 * because of AGENTS.md §8's Room Schema Freeze Rule: once this phase ships, adding the next setting
 * (or the one after that) under the typed-table shape would mean a new migration *per setting* —
 * exactly the kind of schema churn a settings store exists to avoid needing for something as small
 * as "add one more preference." The key-value shape pays for this once, here, and never again for a
 * new *scalar* setting. The trade-off is real and deliberately accepted: no `NOT NULL`/type
 * constraint at the SQL level for an individual setting's value (a caller could in principle store
 * "not a number" under a key [SettingsRepository][com.hub.media.features.settings.data.SettingsRepository]
 * expects to parse as an [Int]), and every read requires a runtime string-key lookup instead of a
 * typed column reference. Both are contained: [key]s are never exposed to UI/ViewModel code directly
 * — [com.hub.media.features.settings.data.SettingsRepository] is the sole owner of every key string
 * and the sole place that parses a stored value back to its typed form, so a malformed value can only
 * ever originate from that one file, not from arbitrary call sites.
 *
 * ### Persistence
 * [key] is the primary key (one row per setting, upserted in place — see
 * [com.hub.media.core.database.dao.AppSettingsDao.upsert]). [value] is always `TEXT NOT NULL`; a
 * future non-string-typed setting stores its stringified form (e.g. an [Int] preference as its
 * decimal string) and parses it back on read — see
 * [com.hub.media.features.settings.data.SettingsRepository] for the accessors that hide this.
 *
 * No settings exist yet with defined semantics: this table is added in schema v4 purely so it
 * *can* hold one, unblocking ROADMAP Task 7 Phase B's week-start-day preference without that phase
 * needing its own schema migration. `MIGRATION_3_4` creates this table empty for every upgrading
 * database — there is nothing to backfill (AGENTS.md §8's Room Schema Freeze Rule: this table never
 * existed before v4, so there is no pre-v4 data to migrate into it).
 */
@Entity(tableName = "app_settings")
public data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
