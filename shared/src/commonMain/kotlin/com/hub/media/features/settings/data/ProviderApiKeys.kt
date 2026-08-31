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
 *
 * `internal`, not `private`: [com.hub.media.features.portability.domain.DefaultDatabaseBackupUseCase]
 * needs this exact row name to scrub the credential out of a staged `.sqlite` backup (a whole-file
 * `VACUUM INTO` snapshot would otherwise carry the plaintext key along in any backup the user
 * shares). That use case imports this constant rather than hardcoding the string a second time --
 * a duplicated literal is how a future rename of this setting silently stops the scrub from
 * matching, which is a credential leak, not a cosmetic bug.
 */
internal const val KEY_GOOGLE_BOOKS_API_KEY = "google_books_api_key"

/**
 * Key a user-supplied TMDB credential is persisted under. Same storage contract as
 * [KEY_GOOGLE_BOOKS_API_KEY] in every respect -- schema v4 `app_settings`, no migration, value is a
 * credential and must never be logged.
 *
 * ### One row for two credential shapes
 * TMDB issues two things from the same settings page, and users paste whichever they happened to
 * copy: a v3 **API key** (32 hex characters, sent as an `api_key` query parameter) and a v4 **read
 * access token** (a JWT, sent as an `Authorization: Bearer` header). This row holds either. Deciding
 * *which* one is stored -- and therefore how to send it -- is the client's job, not storage's, so
 * that a value which stops being recognisable never becomes unreadable data: see
 * `TmdbCredential` in `core/network`.
 *
 * Storing both shapes under one name is why the setting is named for the provider rather than for
 * the credential type. `tmdb_api_key` would have been the obvious name and is the wrong one -- a
 * user who saved a read access token under a row called "api key" is fine, but a *second* row added
 * later for the other shape would be a second credential to scrub, and forgetting it is a leak.
 */
internal const val KEY_TMDB_CREDENTIAL = "tmdb_credential"

/**
 * Every `app_settings` row whose value is a credential, and therefore must be deleted from a staged
 * `.sqlite` backup before the user can export it.
 *
 * ### Why this is a list and not two call sites
 * [com.hub.media.features.portability.domain.DefaultDatabaseBackupUseCase] scrubs what this list
 * holds. #75 recorded the hazard plainly -- the scrub is *not* automatic, so a second provider key
 * needs it wired explicitly, and missing that is a plaintext credential leak rather than a bug.
 * Wiring the second one as a second hardcoded constant would have left the same trap armed for the
 * third.
 *
 * So the scrub iterates this list, and adding a credential means adding it here -- one edit, in the
 * file where the constant is already being written, rather than a remembered edit in a use case two
 * packages away. A new credential that is never added to this list still leaks; nothing can make
 * that impossible. But it is now a single visible omission next to the constant it belongs to,
 * instead of an invisible one somewhere else.
 *
 * Order is irrelevant -- the scrub deletes every entry.
 */
internal val CREDENTIAL_SETTING_KEYS: List<String> =
    listOf(
        KEY_GOOGLE_BOOKS_API_KEY,
        KEY_TMDB_CREDENTIAL,
    )

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
 * Reactive current TMDB credential, or `null` if the user has never supplied one or has cleared it.
 *
 * Every rule on [observeGoogleBooksApiKey] applies unchanged: `null` is not an error state, and the
 * emitted value must never be logged -- log `credential != null` if presence is worth tracing.
 *
 * Unlike the Google Books key, a `null` here is not merely a fallback to unauthenticated access:
 * TMDB refuses anonymous requests outright, so callers get no metadata at all without one. That is
 * a reason for the *UI* to explain the consequence, not a reason for this accessor to treat absence
 * as exceptional -- the app is offline-first (AGENTS.md §1) and manual entry works with no
 * credential at all.
 */
public fun SettingsRepository.observeTmdbCredential(): Flow<String?> =
    observeString(KEY_TMDB_CREDENTIAL).map { it.toApiKeyOrNull() }

/** One-shot fetch of the current TMDB credential; see [observeTmdbCredential] for the null/blank rules. */
public suspend fun SettingsRepository.getTmdbCredential(): String? = getString(KEY_TMDB_CREDENTIAL).toApiKeyOrNull()

/**
 * Persists [value] as the TMDB credential, trimmed first -- see [setGoogleBooksApiKey] for why a
 * blank value clears the row rather than storing `""`.
 *
 * Deliberately does not validate the shape. A v3 key and a v4 token look nothing alike, and a
 * setter that rejected anything it did not recognise would turn "TMDB issued a new credential
 * format" into "the app refuses a credential that works". Recognition happens at send time, where
 * being wrong costs one failed request instead of making the value unstorable.
 */
public suspend fun SettingsRepository.setTmdbCredential(value: String) {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        clear(KEY_TMDB_CREDENTIAL)
    } else {
        setString(KEY_TMDB_CREDENTIAL, trimmed)
    }
}

/** Removes the stored TMDB credential entirely, reverting the accessors above to null. */
public suspend fun SettingsRepository.clearTmdbCredential() {
    clear(KEY_TMDB_CREDENTIAL)
}

/**
 * Collapses a raw stored value to `null` for both the never-set case ([this] itself is `null`) and a
 * blank/whitespace-only stored value -- the latter should be unreachable via [setGoogleBooksApiKey]
 * or [setTmdbCredential], but a row written by any other path (a future migration, direct DB edit,
 * etc.) must not be able to produce a "present but empty" key that callers would otherwise have to
 * special-case.
 */
private fun String?.toApiKeyOrNull(): String? = this?.trim()?.ifBlank { null }
