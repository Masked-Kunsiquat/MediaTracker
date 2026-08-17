package com.hub.media.features.settings.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Key a user-supplied Google Books API key is persisted under via [SettingsRepository] (schema v4
 * `app_settings`, no new migration needed -- exactly [WeekStartDay]'s and the log-verbosity
 * preference's convention; see [WeekStartDay]'s KDoc for why the key-value store already covers
 * this without a schema change).
 *
 * The key itself is a credential, not a preference: unlike [WeekStartDay] or the log-verbosity
 * setting, its value must never appear in a log line, crash report, or KDoc example anywhere it is
 * threaded through the app. Nothing about the *name* of the setting is sensitive -- only the value
 * read back out of [SettingsRepository] under it.
 */
private const val KEY_GOOGLE_BOOKS_API_KEY = "google_books_api_key"

/**
 * Reactive current Google Books API key, or `null` if the user has never supplied one, has since
 * cleared it, or supplied a blank/whitespace-only value (see [setGoogleBooksApiKey] for why the
 * latter can't be told apart from "unset").
 *
 * ### The key is optional
 * A `null` here is not an error state -- it means "no user-supplied key", and callers are expected
 * to fall back to whatever unauthenticated/rate-limited behavior the Google Books client already has
 * without one. Nothing about this accessor forces a caller to have a key before it can proceed.
 *
 * ### Never log the emitted value
 * The whole point of this wrapper existing (rather than call sites reading
 * [SettingsRepository.observeString] directly) is to keep the raw key contained to one file's worth
 * of call sites. Whatever collects this `Flow` downstream must keep treating the value as a
 * credential -- log its presence/absence (e.g. `key != null`) if that's useful, never the value
 * itself.
 */
public fun SettingsRepository.observeGoogleBooksApiKey(): Flow<String?> =
    observeString(KEY_GOOGLE_BOOKS_API_KEY).map { it.toApiKeyOrNull() }

/** One-shot fetch of the current Google Books API key; see [observeGoogleBooksApiKey] for the null/blank rules. */
public suspend fun SettingsRepository.getGoogleBooksApiKey(): String? =
    getString(KEY_GOOGLE_BOOKS_API_KEY).toApiKeyOrNull()

/**
 * Persists [value] as the Google Books API key, trimmed first (users paste keys with trailing
 * whitespace from wherever they copied it).
 *
 * A blank or whitespace-only [value] clears the key instead of storing an empty string. Storing `""`
 * would create a second "present but empty" state that [observeGoogleBooksApiKey]/
 * [getGoogleBooksApiKey] would then have to treat as equivalent to `null` anyway (a row written by
 * any other path must not be able to produce a key that reads back as present-but-unusable) -- so
 * this setter refuses to create that state in the first place, deleting the row via [clear] instead.
 */
public suspend fun SettingsRepository.setGoogleBooksApiKey(value: String) {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        clear(KEY_GOOGLE_BOOKS_API_KEY)
    } else {
        setString(KEY_GOOGLE_BOOKS_API_KEY, trimmed)
    }
}

/**
 * Removes the stored Google Books API key entirely, reverting [observeGoogleBooksApiKey]/
 * [getGoogleBooksApiKey] to null.
 */
public suspend fun SettingsRepository.clearGoogleBooksApiKey() {
    clear(KEY_GOOGLE_BOOKS_API_KEY)
}

/**
 * Collapses a raw stored value to `null` for both the never-set case ([this] itself is `null`) and a
 * blank/whitespace-only stored value -- the latter should be unreachable via [setGoogleBooksApiKey],
 * but a row written by any other path (a future migration, direct DB edit, etc.) must not be able to
 * produce a "present but empty" key that callers would otherwise have to special-case.
 */
private fun String?.toApiKeyOrNull(): String? = this?.trim()?.ifBlank { null }
