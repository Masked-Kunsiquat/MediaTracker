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
    // Requested ahead of its consumer, and the only field here that is: Phase B2's typed result
    // styling (author vs. title vs. collection) is driven by it. `author_key` used to sit alongside
    // it with no consumer at all, which quietly contradicted the argument this whole whitelist
    // rests on -- if the payload is worth trimming, it is worth trimming of our own dead fields too.
    "type",
    "title",
    "author_name",
    "first_publish_year",
    "cover_i",
    "cover_edition_key",
    "edition_count",
    "number_of_pages_median",
).joinToString(",")

/** Log tag for this client's adoption sites (ROADMAP Task 15 Phase C). */
private const val TAG = "OpenLibrarySearchClient"

/**
 * The exception's type name, which is the *only* part of a failure this file is allowed to log.
 *
 * **This is the one place in the codebase where a `Throwable` must not be handed to the logger.**
 * Every other adoption site passes the exception through, on the stated grounds (see [Logger]'s
 * identifier rule) that "exception text from this codebase's own network/DB/file-I/O layers never
 * embeds book content". That held while every URL this app built carried an ISBN. It does not hold
 * here: a search query *is* a title or an author name, it travels in the query string, and Ktor
 * puts the full URL in its exception messages —
 *
 *     Request timeout has expired [url=https://openlibrary.org/search.json?q=the+bell+jar&...]
 *
 * — so `logger.warn(TAG, e)` would write what the user is reading straight into the on-device log
 * file. Observed, not theorised. The type name plus the HTTP status is enough to tell an offline
 * device from a 429 from a parse failure, which is the entire diagnostic value the message had.
 *
 * The same reasoning is why the [Resource.Error]s in this file carry no `cause`: a caller that
 * logged one would reintroduce the leak from the other end.
 */
private fun Throwable.typeName(): String = this::class.simpleName ?: "unknown"

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
                // Exception type only, and no `throwable` argument -- see typeName().
                logger.warn(TAG) { "Open Library returned malformed JSON for a search (${e.typeName()})" }
                return Resource.Error("Open Library returned a malformed search response")
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
            // crashing. Exception type only, and no `throwable` argument -- see typeName().
            logger.warn(TAG) { "Open Library search failed (${e.typeName()})" }
            Resource.Error("Open Library search failed")
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
