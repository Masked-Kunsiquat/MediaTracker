package com.hub.media.features.books.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response shape of `GET https://openlibrary.org/isbn/{isbn}.json` or
 * `GET https://openlibrary.org/books/{edition_key}.json` (an Open Library "edition" record).
 * Only the fields [OpenLibraryClient][com.hub.media.features.books.network.OpenLibraryClient]
 * and [OpenLibrarySearchClient] need are modeled; everything else is dropped via `ignoreUnknownKeys`.
 */
@Serializable
internal data class OpenLibraryEditionDto(
    val title: String? = null,
    @SerialName("number_of_pages") val numberOfPages: Int? = null,
    @SerialName("publish_date") val publishDate: String? = null,
    val authors: List<OpenLibraryAuthorRefDto>? = null,
    val covers: List<Int>? = null,
    val key: String? = null,
    /**
     * The work(s) this edition is a printing of, e.g. `[{"key": "/works/OL27482W"}]`. Open
     * Library's model hangs authorship off the *work*, so this is the only route to an author for
     * the many editions whose own record omits `authors` entirely — see
     * [com.hub.media.features.books.network.OpenLibraryClient].
     */
    val works: List<OpenLibraryWorkRefDto>? = null,
    /**
     * 10-digit ISBN, when available in the edition record. Falls back to [isbn13] if not present.
     */
    @SerialName("isbn_10")
    val isbn10: List<String>? = null,
    /**
     * 13-digit ISBN, when available in the edition record. Prefer over [isbn10] when both are
     * present, matching the resolution order in
     * [com.hub.media.features.books.network.OpenLibrarySearchClient.resolveEditionToIsbn].
     */
    @SerialName("isbn_13")
    val isbn13: List<String>? = null,
)

/** An author reference embedded in an edition response, e.g. `{"key": "/authors/OL26320A"}`. */
@Serializable
internal data class OpenLibraryAuthorRefDto(
    val key: String? = null,
)

/** A work reference embedded in an edition response, e.g. `{"key": "/works/OL27482W"}`. */
@Serializable
internal data class OpenLibraryWorkRefDto(
    val key: String? = null,
)

/**
 * Response shape of `GET https://openlibrary.org/works/{key}.json`.
 *
 * Note the author references are nested **one level deeper than the edition's** — a work says
 * `[{"author": {"key": "/authors/OL26320A"}, "type": {...}}]` where an edition says
 * `[{"key": "/authors/OL26320A"}]`. Verified against the live API; modelling it with the
 * edition's flat [OpenLibraryAuthorRefDto] silently parses every key as null.
 */
@Serializable
internal data class OpenLibraryWorkDto(
    val authors: List<OpenLibraryWorkAuthorRefDto>? = null,
)

/** One entry of a work's `authors` array — see [OpenLibraryWorkDto] for the nesting. */
@Serializable
internal data class OpenLibraryWorkAuthorRefDto(
    val author: OpenLibraryAuthorRefDto? = null,
)

/** Response shape of `GET https://openlibrary.org/authors/{key}.json`. */
@Serializable
internal data class OpenLibraryAuthorDto(
    val name: String? = null,
)
