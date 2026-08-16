package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.warn
import com.hub.media.features.books.network.dto.GoogleBooksImageLinksDto
import com.hub.media.features.books.network.dto.GoogleBooksResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

private const val GOOGLE_BOOKS_VOLUMES_URL = "https://www.googleapis.com/books/v1/volumes"

/**
 * [BookMetadataProvider] backed by the keyless Google Books volumes search API
 * (`GET /volumes?q=isbn:{isbn}`), the fallback book metadata source per AGENTS.md §4.
 */
/** Log tag for this client's adoption sites (ROADMAP Task 15 Phase C). */
private const val TAG = "GoogleBooksClient"

public class GoogleBooksClient(
    private val client: HttpClient,
    private val logger: Logger = AppLogger,
) : BookMetadataProvider {
    override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> {
        return try {
            val response =
                client.get(GOOGLE_BOOKS_VOLUMES_URL) {
                    parameter("q", "isbn:$isbn")
                }
            if (!response.status.isSuccess()) {
                return Resource.Error(
                    "Google Books request failed with status ${response.status.value} for ISBN $isbn",
                )
            }

            val dto =
                try {
                    response.body<GoogleBooksResponseDto>()
                } catch (e: CancellationException) {
                    // Same rethrow as the outer catch below -- a cancelled deserialization is not a
                    // malformed-JSON failure.
                    throw e
                } catch (e: Exception) {
                    logger.warn(TAG, e) { "Google Books returned malformed JSON for isbn=$isbn" }
                    return Resource.Error("Google Books returned malformed JSON for ISBN $isbn", e)
                }

            val item =
                dto.items?.firstOrNull()
                    ?: return Resource.Error("Google Books returned no results for ISBN $isbn")
            val volumeInfo =
                item.volumeInfo
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
        } catch (e: CancellationException) {
            // Rethrown ahead of the Exception catch: on JVM CancellationException *is* an Exception,
            // so swallowing it here would both break structured concurrency and log a spurious WARN
            // every time a screen is closed mid-lookup.
            throw e
        } catch (e: Exception) {
            // The fallback provider: when this one fails too, the add/backfill genuinely has no
            // metadata to offer, so this is the entry that explains an empty result.
            logger.warn(TAG, e) { "Google Books lookup failed for isbn=$isbn" }
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
