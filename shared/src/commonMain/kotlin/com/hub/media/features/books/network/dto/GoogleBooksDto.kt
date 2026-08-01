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

@Serializable
internal data class GoogleBooksImageLinksDto(
    val thumbnail: String? = null,
)
