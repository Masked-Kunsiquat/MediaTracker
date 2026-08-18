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

/** Log tag for this client's adoption sites (ROADMAP Task 15 Phase C). */
private const val TAG = "GoogleBooksClient"

/**
 * Statuses Google Books answers with when it is the *credential* it objects to rather than the
 * request: `400` for a malformed/unknown key and `403` for one that is disabled, restricted to
 * other referrers/IPs, or has no Books API access. Both are indistinguishable from an ordinary
 * client error in the status code alone, which is exactly why a key being configured changes what
 * the log says about them -- see [GoogleBooksClient.fetchByIsbn].
 */
private val KEY_REJECTION_STATUS_CODES = setOf(400, 403)

/**
 * [BookMetadataProvider] backed by the Google Books volumes search API
 * (`GET /volumes?q=isbn:{isbn}`), the fallback book metadata source per AGENTS.md §4.
 *
 * ### The API key is optional, and stays optional
 * Google's documentation says public-data requests require an API key; in practice keyless requests
 * are tolerated, on an undocumented and demonstrably thin quota -- 429s have been observed against
 * it on a *single* request during an Open Library outage, which is the one moment the fallback has
 * to work (ROADMAP Task 13's Google-Books-key bullet). Supplying a key moves this client onto a
 * documented quota, so [apiKeyProvider] exists; it does not become a requirement. A `null` key means
 * exactly the keyless behavior this class has always had, and every call site that omits the
 * parameter keeps it.
 *
 * @param client Shared Ktor client.
 * @param apiKeyProvider Suspending source of the user-supplied Google Books API key, or `null` for
 *   no key (the default). Read per request rather than captured once at construction: the key lives
 *   in `app_settings` behind
 *   [com.hub.media.features.settings.data.getGoogleBooksApiKey], and this client is built once at
 *   [com.hub.media.ui.AppContainer] construction and outlives every visit to the Settings screen --
 *   a value captured up front would go stale the moment the user entered, changed, or cleared a key,
 *   and would keep sending a key they had just deleted.
 * @param logger Log sink. **The key's value must never reach it** -- this class logs only whether a
 *   key was in play (see [KEY_REJECTION_STATUS_CODES]'s use below), never the key itself, and the
 *   request URL that carries it is never logged either.
 */
public class GoogleBooksClient(
    private val client: HttpClient,
    private val apiKeyProvider: suspend () -> String? = { null },
    private val logger: Logger = AppLogger,
) : BookMetadataProvider {
    override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> {
        return try {
            val apiKey = apiKeyProvider()
            val response =
                client.get(GOOGLE_BOOKS_VOLUMES_URL) {
                    parameter("q", "isbn:$isbn")
                    // Omitted entirely when no key is configured, rather than sent empty: `key=`
                    // with a blank value is a *rejected* key to Google, so an empty parameter would
                    // turn the working keyless path into a 400. getGoogleBooksApiKey() already
                    // collapses blank to null for the same reason.
                    if (apiKey != null) {
                        parameter("key", apiKey)
                    }
                }
            if (!response.status.isSuccess()) {
                // This is the *fallback*, so a status failure here means the add genuinely has no
                // metadata to offer -- and it was the one failure mode in this class that logged
                // nothing. Observed for real: with Open Library down, Google Books answered 429 and
                // the log recorded only the primary's timeout, so two failures left one entry.
                //
                // The key-rejection hint matters because the failure is otherwise unattributable: a
                // user who has just pasted a key into Settings and starts getting 400s has no way to
                // tell a bad key from a bad ISBN, and the status code alone doesn't say. Only the
                // *presence* of a key is mentioned; its value is never logged (see the class KDoc).
                val keyHint =
                    if (apiKey != null && response.status.value in KEY_REJECTION_STATUS_CODES) {
                        " (an API key is configured -- Google rejects the request itself when the key is " +
                            "invalid, restricted, or not enabled for the Books API)"
                    } else {
                        ""
                    }
                logger.warn(TAG) {
                    "Google Books lookup returned ${response.status.value} for isbn=$isbn$keyHint"
                }
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
            //
            // LOGGING: throwable is omitted so a transport-level error (e.g. from Ktor or OkHttp)
            // cannot leak the API key into the log file if it were to embed the request URL
            // in its own message or string representation.
            logger.warn(TAG) { "Google Books lookup failed for isbn=$isbn (transport error)" }
            return Resource.Error("Google Books lookup failed for ISBN $isbn")
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
