package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.info
import com.hub.media.core.util.warn
import com.hub.media.features.books.network.dto.OpenLibraryAuthorDto
import com.hub.media.features.books.network.dto.OpenLibraryAuthorRefDto
import com.hub.media.features.books.network.dto.OpenLibraryEditionDto
import com.hub.media.features.books.network.dto.OpenLibraryWorkDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

private const val OPEN_LIBRARY_BASE_URL = "https://openlibrary.org"
private const val OPEN_LIBRARY_COVERS_BASE_URL = "https://covers.openlibrary.org/b"
private const val MAX_AUTHOR_LOOKUPS = 3
private val FOUR_DIGIT_YEAR_REGEX = Regex("""\b(1[5-9]\d{2}|20\d{2})\b""")

/**
 * [BookMetadataProvider] backed by the Open Library ISBN edition API
 * (`GET /isbn/{isbn}.json`), the primary book metadata source per AGENTS.md §4.
 *
 * Author names are not embedded in the edition payload — only `/authors/{key}` refs are. This
 * client makes a secondary fetch per author (capped at [MAX_AUTHOR_LOOKUPS]) to resolve display
 * names, and tolerates individual author-fetch failures: a name that can't be resolved is simply
 * omitted rather than failing the whole lookup, since author names are not required for a
 * successful [BookMetadata] result — a lookup can legitimately resolve with an empty author list.
 *
 * Cover URL policy: Open Library exposes numeric cover ids on the edition record, which resolve
 * to a stable image at `covers.openlibrary.org/b/id/{id}-L.jpg`. Open Library also supports an
 * ISBN-keyed cover URL (`.../b/isbn/{isbn}-L.jpg`) that works even when the edition has no
 * `covers` list, but that URL renders a "no cover" placeholder image for many editions rather
 * than a real 404, so it can't be reliably distinguished from a genuine cover ahead of a real
 * download. To keep the contract predictable (a non-null [BookMetadata.coverImageUrl] should be
 * a real, provider-confirmed image), this client only sets [BookMetadata.coverImageUrl] when the
 * edition payload has an explicit cover id.
 */
/** Log tag for this client's adoption sites (ROADMAP Task 15 Phase C). */
private const val TAG = "OpenLibraryClient"

public class OpenLibraryClient(
    private val client: HttpClient,
    private val logger: Logger = AppLogger,
) : BookMetadataProvider {
    override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> {
        return try {
            val response = client.get("$OPEN_LIBRARY_BASE_URL/isbn/$isbn.json")
            if (!response.status.isSuccess()) {
                // Logged for the same reason the thrown-exception path below is: this is the primary
                // provider on the add-book flow, and a status failure is the one failure mode that
                // used to leave no trace at all. A real Open Library outage produced a user-visible
                // error and an empty log, which is precisely backwards.
                logger.warn(TAG) {
                    "Open Library lookup returned ${response.status.value} for isbn=$isbn"
                }
                return Resource.Error(
                    "Open Library request failed with status ${response.status.value} for ISBN $isbn",
                )
            }

            val dto =
                try {
                    response.body<OpenLibraryEditionDto>()
                } catch (e: CancellationException) {
                    // Same rethrow as the outer catch below -- a cancelled deserialization is not a
                    // malformed-JSON failure.
                    throw e
                } catch (e: Exception) {
                    logger.warn(TAG, e) { "Open Library returned malformed JSON for isbn=$isbn" }
                    return Resource.Error("Open Library returned malformed JSON for ISBN $isbn", e)
                }

            val title = dto.title
            if (title.isNullOrBlank()) {
                return Resource.Error("Open Library response for ISBN $isbn is missing a title")
            }

            val coverId = dto.covers?.firstOrNull()
            val coverImageUrl = coverId?.let { "$OPEN_LIBRARY_COVERS_BASE_URL/id/$it-L.jpg" }
            val releaseYear = dto.publishDate?.let { parseYear(it) }
            val workKey = dto.works?.firstOrNull()?.key
            val authors = resolveAuthors(dto.authors.orEmpty(), workKey)

            Resource.Success(
                BookMetadata(
                    title = title,
                    authors = authors,
                    releaseYear = releaseYear,
                    pageCount = dto.numberOfPages,
                    isbn = isbn,
                    coverImageUrl = coverImageUrl,
                    provider = IdentifierProvider.OPEN_LIBRARY,
                    externalId = dto.key,
                    workKey = workKey,
                ),
            )
        } catch (e: CancellationException) {
            // Rethrown ahead of the Exception catch: on JVM CancellationException *is* an Exception,
            // so swallowing it here would both break structured concurrency and log a spurious WARN
            // every time a screen is closed mid-lookup.
            throw e
        } catch (e: Exception) {
            // WARN, not ERROR: an offline device is the ordinary case here, and a failed lookup
            // that the app recovers from by falling back to Google Books is not a fault to shout
            // about. Same level, and the same isbn= identifier, as OpenLibraryIsbnCoverProbe.
            logger.warn(TAG, e) { "Open Library lookup failed for isbn=$isbn" }
            Resource.Error("Open Library lookup failed for ISBN $isbn: ${e.message}", e)
        }
    }

    /**
     * Resolves author display names, falling back to the **work** when the edition record has no
     * author references of its own.
     *
     * Open Library models authorship on the work, not the printing —
     * ["Work fields / Edition fields"](https://openlibrary.org/about/work_edition) puts
     * author/creator at work level and leaves editions carrying printing-specific contributors
     * (editors, illustrators). Plenty of edition records omit `authors` outright: the Mariner 75th
     * Anniversary Hobbit (`9780547928227`) has no `authors` key at all, while its work
     * `/works/OL27482W` names J.R.R. Tolkien. Reading only the edition made every such book land
     * with no author and, because nothing actually *failed*, nothing was logged either — an
     * absence is not an error, so Task 15 Phase C's adoption had nothing to report.
     *
     * Edition authors still win when present: they are the more specific record, and preferring
     * them preserves the behaviour every already-ingested book was added with. The work is
     * consulted only to fill a gap, which also keeps the extra round-trip off the common path.
     */
    private suspend fun resolveAuthors(
        editionRefs: List<OpenLibraryAuthorRefDto>,
        workKey: String?,
    ): List<String> {
        val fromEdition = resolveAuthorNames(editionRefs)
        if (fromEdition.isNotEmpty() || workKey == null) return fromEdition
        // Traced because the original bug was invisible precisely *because* nothing failed: an
        // edition with no authors is not an error, so the silent path had nothing to report and
        // the book simply arrived blank. An INFO line here is what makes the fallback observable
        // when it works, not only when it breaks. Key, not name -- see the log-privacy rule above.
        logger.info(TAG) { "Edition carries no authors; falling back to work $workKey" }
        return resolveAuthorNames(fetchWorkAuthorRefs(workKey))
    }

    /**
     * Fetches a work's author references. Returns empty on any failure — a missing author is not
     * worth failing an otherwise good book lookup over, exactly as [fetchAuthorName] treats one.
     */
    private suspend fun fetchWorkAuthorRefs(workKey: String): List<OpenLibraryAuthorRefDto> {
        return try {
            val response = client.get("$OPEN_LIBRARY_BASE_URL$workKey.json")
            if (!response.status.isSuccess()) {
                logger.warn(TAG) {
                    "Open Library work lookup returned ${response.status.value} for key=$workKey"
                }
                return emptyList()
            }
            // A work nests its refs one level deeper than an edition does -- see OpenLibraryWorkDto.
            response
                .body<OpenLibraryWorkDto>()
                .authors
                .orEmpty()
                .mapNotNull { it.author }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, e) { "Open Library work lookup failed for key=$workKey" }
            emptyList()
        }
    }

    /**
     * Resolves display names for up to [MAX_AUTHOR_LOOKUPS] author refs via secondary
     * `/authors/{key}.json` fetches. Any individual failure (network error, malformed JSON,
     * non-2xx status, missing name) silently drops that author rather than failing the lookup.
     */
    private suspend fun resolveAuthorNames(refs: List<OpenLibraryAuthorRefDto>): List<String> {
        val names = mutableListOf<String>()
        for (ref in refs.take(MAX_AUTHOR_LOOKUPS)) {
            val key = ref.key ?: continue
            fetchAuthorName(key)?.let { names.add(it) }
        }
        return names
    }

    private suspend fun fetchAuthorName(key: String): String? {
        return try {
            val response = client.get("$OPEN_LIBRARY_BASE_URL$key.json")
            if (!response.status.isSuccess()) {
                // The other half of the silent drop, and the likelier half: a non-2xx answer
                // returns here without ever throwing, so the catch below never sees it. Status
                // codes are explicitly loggable under the identifier rule.
                logger.warn(TAG) {
                    "Open Library author lookup returned ${response.status.value} for key=$key"
                }
                return null
            }
            response.body<OpenLibraryAuthorDto>().name?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            // Rethrown ahead of the Exception catch below: this method already returns null for an
            // unresolved author, so a swallowed cancellation would look identical to "no such
            // author" instead of propagating like every other cancellation in this file.
            throw e
        } catch (e: Exception) {
            // Was a silent drop -- the one swallow in this file that discarded its cause entirely,
            // returning null with nothing recorded anywhere. It is also the one a user actually
            // notices: an author that never arrives is exactly the "why has this book got no
            // author?" symptom, and until now nothing explained it. The Open Library author key is
            // public catalogue data, an identifier of the same kind as the isbn the rule already
            // permits -- not the author name itself, which is library content and stays out.
            logger.warn(TAG, e) { "Open Library author lookup failed for key=$key" }
            null
        }
    }
}

/** Extracts a plausible 4-digit publication year (1500-2099) from a free-form date string. */
internal fun parseYear(rawDate: String): Int? = FOUR_DIGIT_YEAR_REGEX.find(rawDate)?.value?.toIntOrNull()
