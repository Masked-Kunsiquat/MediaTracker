package com.hub.media.features.books.network.dto

import kotlinx.serialization.Serializable

/** Response shape of `GET https://www.googleapis.com/books/v1/volumes?q=isbn:{isbn}`. */
@Serializable
internal data class GoogleBooksResponseDto(
    val totalItems: Int? = null,
    val items: List<GoogleBooksItemDto>? = null,
)

@Serializable
internal data class GoogleBooksItemDto(
    val id: String? = null,
    val volumeInfo: GoogleBooksVolumeInfoDto? = null,
)

@Serializable
internal data class GoogleBooksVolumeInfoDto(
    val title: String? = null,
    val authors: List<String>? = null,
    val publishedDate: String? = null,
    val pageCount: Int? = null,
    val imageLinks: GoogleBooksImageLinksDto? = null,
)

/**
 * Google Books' `imageLinks` object. Google returns whichever of these fields it has for a given
 * volume, roughly in ascending size order: [smallThumbnail] (~80px) < [thumbnail] (~128px) <
 * [small] < [medium] < [large] < [extraLarge]. Prior to ROADMAP Task 6 Phase E, only [thumbnail]
 * was declared here, so a volume that *did* offer a larger image was silently limited to the
 * smallest usable size — see [com.hub.media.features.books.network.largestAvailableUrl].
 */
@Serializable
internal data class GoogleBooksImageLinksDto(
    val smallThumbnail: String? = null,
    val thumbnail: String? = null,
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
    val extraLarge: String? = null,
)
