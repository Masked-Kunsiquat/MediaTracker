package com.hub.media.features.books.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response shape of `GET https://openlibrary.org/search.json` (ROADMAP Task 9 Phase B1).
 *
 * Open Library states plainly that "the schema is not guaranteed to be stable, but most common
 * fields ... should be safe to depend on", so every field here is nullable/defaulted and unknown
 * keys are dropped via `ignoreUnknownKeys`. The response also echoes `q`, `offset`,
 * `documentation_url` and a snake_case `num_found` duplicate of [numFound]; none are modeled
 * because nothing needs them.
 */
@Serializable
internal data class OpenLibrarySearchResponseDto(
    val numFound: Int? = null,
    val start: Int? = null,
    val numFoundExact: Boolean? = null,
    val docs: List<OpenLibrarySearchDocDto>? = null,
)

/**
 * One result document from the search endpoint. These are **works**, not editions — `key` looks
 * like `/works/OL27482W` — which is the right granularity for a type-ahead: a user searching
 * "hobbit" wants the book once, not each of its 481 editions.
 *
 * [isbn] is deliberately **not** requested in the `fields` parameter and so is not modeled here.
 * A popular work's `isbn` array runs to hundreds of entries and dwarfs everything else in the
 * payload — the live response for "tolkien hobbit" was multiple kilobytes of ISBNs per doc. A
 * type-ahead that ships that on every keystroke would be indefensible; the ISBN needed to actually
 * add the book is fetched once, on selection.
 */
@Serializable
internal data class OpenLibrarySearchDocDto(
    val key: String? = null,
    val type: String? = null,
    val title: String? = null,
    @SerialName("author_name") val authorName: List<String>? = null,
    @SerialName("author_key") val authorKey: List<String>? = null,
    @SerialName("first_publish_year") val firstPublishYear: Int? = null,
    @SerialName("cover_i") val coverId: Int? = null,
    @SerialName("cover_edition_key") val coverEditionKey: String? = null,
    @SerialName("edition_count") val editionCount: Int? = null,
    @SerialName("number_of_pages_median") val numberOfPagesMedian: Int? = null,
)
