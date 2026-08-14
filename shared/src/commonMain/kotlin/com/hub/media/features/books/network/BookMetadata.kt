package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider

/**
 * Provider-agnostic book metadata returned by a [BookMetadataProvider] lookup.
 *
 * Every field beyond [title] and [provider] is nullable/defaulted because public book catalogs
 * are inconsistent about what they populate for a given ISBN (per AGENTS.md §7, "missing API
 * metadata fields" is an explicit edge case to handle, not fail on).
 *
 * @property title Book title. The one field a lookup treats as required — a response with no
 *   usable title is surfaced as [com.hub.media.core.util.Resource.Error] instead of this type.
 * @property authors Author display names, best-effort. May be empty if the provider omitted
 *   authors or a secondary author-name fetch failed.
 * @property releaseYear Four-digit publication year, parsed leniently from whatever date format
 *   the provider returned.
 * @property pageCount Page count, if the provider reports one.
 * @property isbn The ISBN the lookup was performed with (echoed back for convenience).
 * @property coverImageUrl A remote URL to fetch the cover image bytes from, if known.
 * @property provider Which catalog this metadata came from.
 * @property externalId The provider-native identifier — for Open Library specifically, the
 *   **edition** key (e.g. `/books/OL33891995M`), since an ISBN identifies a printing. A Google
 *   Books volume id otherwise.
 * @property workKey The provider's **work** key (e.g. `/works/OL27482W`) — the abstract book that
 *   this edition is one printing of, when the provider models the distinction and the response
 *   exposed it. Null for providers that don't (Google Books has no work concept).
 *
 *   Nothing reads this yet; it is captured at ingestion because it cannot be recovered afterwards
 *   without another rate-limited crawl over the library. See
 *   [IdentifierProvider.OPEN_LIBRARY_WORK] for what it unlocks.
 */
public data class BookMetadata(
    val title: String,
    val authors: List<String> = emptyList(),
    val releaseYear: Int? = null,
    val pageCount: Int? = null,
    val isbn: String? = null,
    val coverImageUrl: String? = null,
    val provider: IdentifierProvider,
    val externalId: String? = null,
    val workKey: String? = null,
)
