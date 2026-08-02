package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.util.Resource
import com.hub.media.features.books.network.dto.GoogleBooksImageLinksDto
import com.hub.media.features.books.network.dto.GoogleBooksResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess

private const val GOOGLE_BOOKS_VOLUMES_URL = "https://www.googleapis.com/books/v1/volumes"

/**
 * [BookMetadataProvider] backed by the keyless Google Books volumes search API
 * (`GET /volumes?q=isbn:{isbn}`), the fallback book metadata source per AGENTS.md §4.
 */
public class GoogleBooksClient(private val client: HttpClient) : BookMetadataProvider {

    override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> {
        return try {
            val response = client.get(GOOGLE_BOOKS_VOLUMES_URL) {
                parameter("q", "isbn:$isbn")
            }
            if (!response.status.isSuccess()) {
                return Resource.Error(
                    "Google Books request failed with status ${response.status.value} for ISBN $isbn",
                )
            }

            val dto = try {
                response.body<GoogleBooksResponseDto>()
            } catch (e: Exception) {
                return Resource.Error("Google Books returned malformed JSON for ISBN $isbn", e)
            }

            val item = dto.items?.firstOrNull()
                ?: return Resource.Error("Google Books returned no results for ISBN $isbn")
            val volumeInfo = item.volumeInfo
                ?: return Resource.Error("Google Books result for ISBN $isbn is missing volumeInfo")

            val title = volumeInfo.title
            if (title.isNullOrBlank()) {
                return Resource.Error("Google Books result for ISBN $isbn is missing a title")
            }

            val coverImageUrl = volumeInfo.imageLinks?.largestAvailableUrl()?.let { toHttps(it) }
            val releaseYear = volumeInfo.publishedDate?.let { parseYear(it) }

            Resource.Success(
                BookMetadata(
                    title = title,
                    authors = volumeInfo.authors.orEmpty(),
                    releaseYear = releaseYear,
                    pageCount = volumeInfo.pageCount,
                    isbn = isbn,
                    coverImageUrl = coverImageUrl,
                    provider = IdentifierProvider.GOOGLE_BOOKS,
                    externalId = item.id,
                ),
            )
        } catch (e: Exception) {
            Resource.Error("Google Books lookup failed for ISBN $isbn: ${e.message}", e)
        }
    }
}

/** Upgrades an `http://` image URL to `https://`; leaves other schemes untouched. */
internal fun toHttps(url: String): String =
    if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

/**
 * Selects the largest image URL Google actually provided (ROADMAP Task 6 Phase E), preferring
 * [GoogleBooksImageLinksDto.extraLarge] > [GoogleBooksImageLinksDto.large] >
 * [GoogleBooksImageLinksDto.medium] > [GoogleBooksImageLinksDto.small] >
 * [GoogleBooksImageLinksDto.thumbnail] > [GoogleBooksImageLinksDto.smallThumbnail]. Google Books
 * volumes are inconsistent about which sizes they populate — falling straight to `null` the moment
 * a preferred field is absent would throw away a perfectly good smaller image, so this walks the
 * whole chain rather than only checking the top of it.
 */
internal fun GoogleBooksImageLinksDto.largestAvailableUrl(): String? =
    extraLarge ?: large ?: medium ?: small ?: thumbnail ?: smallThumbnail
