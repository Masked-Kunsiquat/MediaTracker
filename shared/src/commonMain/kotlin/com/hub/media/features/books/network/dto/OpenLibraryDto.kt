package com.hub.media.features.books.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response shape of `GET https://openlibrary.org/isbn/{isbn}.json` (an Open Library "edition"
 * record). Only the fields [OpenLibraryClient][com.hub.media.features.books.network.OpenLibraryClient]
 * needs are modeled; everything else is dropped via `ignoreUnknownKeys`.
 */
@Serializable
internal data class OpenLibraryEditionDto(
    val title: String? = null,
    @SerialName("number_of_pages") val numberOfPages: Int? = null,
    @SerialName("publish_date") val publishDate: String? = null,
    val authors: List<OpenLibraryAuthorRefDto>? = null,
    val covers: List<Int>? = null,
    val key: String? = null,
)

/** An author reference embedded in an edition response, e.g. `{"key": "/authors/OL26320A"}`. */
@Serializable
internal data class OpenLibraryAuthorRefDto(
    val key: String? = null,
)

/** Response shape of `GET https://openlibrary.org/authors/{key}.json`. */
@Serializable
internal data class OpenLibraryAuthorDto(
    val name: String? = null,
)
