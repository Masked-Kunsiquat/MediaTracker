package com.hub.media.features.books.network.dto

import kotlinx.serialization.Serializable

/**
 * Response shape of `GET https://openlibrary.org/works/{work_id}/editions.json`.
 *
 * Modeled after the Open Library "editions" endpoint, which returns a paginated list of edition
 * records for a given work.
 */
@Serializable
internal data class OpenLibraryWorkEditionsResponseDto(
    val size: Int? = null,
    val entries: List<OpenLibraryEditionDto>? = null,
)
