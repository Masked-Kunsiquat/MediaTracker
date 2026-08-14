package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.warn
import com.hub.media.features.books.network.dto.OpenLibrarySearchDocDto
import com.hub.media.features.books.network.dto.OpenLibrarySearchResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

private const val OPEN_LIBRARY_SEARCH_URL = "https://openlibrary.org/search.json"
private const val OPEN_LIBRARY_COVERS_BASE_URL = "https://covers.openlibrary.org/b"

/**
 * The `fields` whitelist sent on every search.
 *
 * Requesting fields explicitly is not an optimization detail, it is the difference between a
 * usable type-ahead and an unusable one. The default response returns every indexed field,
 * including an `isbn` array that runs to *hundreds* of entries for a popular work — the live
 * response for "tolkien hobbit" was kilobytes of ISBNs per document. On a phone, per keystroke,
 * that is indefensible. Everything listed here is what a dropdown row actually renders, plus the
 * two keys a selection needs to resolve later.
 */
private val SEARCH_FIELDS = listOf(
    "key",
    "type",
    "title",
    "author_name",
    "author_key",
    "first_publish_year",
    "cover_i",
    "cover_edition_key",
    "edition_count",
    "number_of_pages_median",
).joinToString(",")

/** Log tag for this client's adoption sites (ROADMAP Task 15 Phase C). */
private const val TAG = "OpenLibrarySearchClient"

/**
 * [BookSearchProvider] backed by Open Library's keyless search API
 * (`GET https://openlibrary.org/search.json`), for ROADMAP Task 9 Phase B1's title/author
 * type-ahead.
 *
 * Kept separate from [OpenLibraryClient] rather than bolted onto it: that class is a
 * [BookMetadataProvider] resolving one edition by ISBN, and it participates in the
 * [FallbackBookMetadataProvider] chain. Search has a different endpoint, a different response
 * shape, work-level rather than edition-level results, and explicitly must *not* fall through to
 * Google Books per keystroke. One class implementing both interfaces would inherit the chain's
 * behaviour by accident.
 *
 * **Cancellation is expected here, not exceptional.** A superseded keystroke cancels its request,
 * so every catch in this file rethrows [CancellationException] ahead of the general handler —
 * otherwise the log would fill with provider failures at typing speed, which is exactly the noise
 * Task 15 Phase C spent its time removing from the ISBN paths.
 */
public class OpenLibrarySearchClient(
    private val client: HttpClient,
    private val logger: Logger = AppLogger,
) : BookSearchProvider {

    override suspend fun searchByTitleOrAuthor(
        query: String,
        limit: Int,
    ): Resource<List<BookSearchResult>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            // Open Library answers an empty q with 200 and an empty docs list, so this is purely to
            // avoid spending a request (and a slice of the rate-limit budget) learning that.
            return Resource.Success(emptyList())
        }

        return try {
            val response = client.get(OPEN_LIBRARY_SEARCH_URL) {
                // Ktor URL-encodes these, which matters more here than on the ISBN path: this is
                // arbitrary user text, so spaces, ampersands and non-Latin scripts all arrive.
                parameter("q", trimmed)
                parameter("fields", SEARCH_FIELDS)
                parameter("limit", limit)
            }

            if (!response.status.isSuccess()) {
                // 429 is a genuinely likely status here rather than a theoretical one — a
                // type-ahead is the heaviest thing this app does to Open Library. The status code
                // is loggable under the identifier rule; the query is user content and is not.
                logger.warn(TAG) { "Open Library search returned ${response.status.value}" }
                return Resource.Error("Open Library search failed with status ${response.status.value}")
            }

            val dto = try {
                response.body<OpenLibrarySearchResponseDto>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, e) { "Open Library returned malformed JSON for a search" }
                return Resource.Error("Open Library returned a malformed search response", e)
            }

            // An empty docs list is a successful search that found nothing, not a failure: the
            // distinction is the whole reason the UI can say "no matches" instead of "search
            // failed", and users type prefixes that legitimately match nothing all the time.
            Resource.Success(dto.docs.orEmpty().mapNotNull { it.toSearchResult() })
        } catch (e: CancellationException) {
            // Rethrown ahead of the Exception catch: on JVM CancellationException *is* an
            // Exception, so swallowing it here would both break structured concurrency and report
            // a provider failure for every keystroke that supersedes an in-flight request.
            throw e
        } catch (e: Exception) {
            // WARN, not ERROR, for the same reason as OpenLibraryClient.fetchByIsbn: an offline
            // device is the ordinary case, and the UI recovers by showing nothing rather than
            // crashing.
            logger.warn(TAG, e) { "Open Library search failed" }
            Resource.Error("Open Library search failed: ${e.message}", e)
        }
    }
}

/**
 * Maps one search document to a [BookSearchResult], or null to drop it.
 *
 * A hit with no usable title is dropped rather than rendered as a blank row — the user cannot act
 * on a result they cannot read, and one missing title should not fail the whole search.
 */
private fun OpenLibrarySearchDocDto.toSearchResult(): BookSearchResult? {
    val title = title?.takeIf { it.isNotBlank() } ?: return null
    return BookSearchResult(
        title = title,
        // The index returns author names inline, so unlike the ISBN edition path this costs no
        // secondary /authors/{key} round-trip -- which is what makes search affordable per
        // keystroke at all. Blank entries are dropped; Open Library does emit the occasional one.
        authors = authorName.orEmpty().filter { it.isNotBlank() },
        firstPublishYear = firstPublishYear,
        // -M, not the -L used for stored covers: these are transient dropdown thumbnails that are
        // never downloaded or content-addressed, so the large variant would waste bandwidth on
        // rows the user scrolls straight past.
        coverThumbnailUrl = coverId?.let { "$OPEN_LIBRARY_COVERS_BASE_URL/id/$it-M.jpg" },
        editionCount = editionCount,
        medianPageCount = numberOfPagesMedian,
        provider = IdentifierProvider.OPEN_LIBRARY,
        workKey = key,
        coverEditionKey = coverEditionKey,
    )
}
