package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.util.Resource
import com.hub.media.features.books.network.dto.OpenLibraryAuthorDto
import com.hub.media.features.books.network.dto.OpenLibraryAuthorRefDto
import com.hub.media.features.books.network.dto.OpenLibraryEditionDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess

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
public class OpenLibraryClient(private val client: HttpClient) : BookMetadataProvider {

    override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> {
        return try {
            val response = client.get("$OPEN_LIBRARY_BASE_URL/isbn/$isbn.json")
            if (!response.status.isSuccess()) {
                return Resource.Error(
                    "Open Library request failed with status ${response.status.value} for ISBN $isbn",
                )
            }

            val dto = try {
                response.body<OpenLibraryEditionDto>()
            } catch (e: Exception) {
                return Resource.Error("Open Library returned malformed JSON for ISBN $isbn", e)
            }

            val title = dto.title
            if (title.isNullOrBlank()) {
                return Resource.Error("Open Library response for ISBN $isbn is missing a title")
            }

            val coverId = dto.covers?.firstOrNull()
            val coverImageUrl = coverId?.let { "$OPEN_LIBRARY_COVERS_BASE_URL/id/$it-L.jpg" }
            val releaseYear = dto.publishDate?.let { parseYear(it) }
            val authors = resolveAuthorNames(dto.authors.orEmpty())

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
                ),
            )
        } catch (e: Exception) {
            Resource.Error("Open Library lookup failed for ISBN $isbn: ${e.message}", e)
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
            if (!response.status.isSuccess()) return null
            response.body<OpenLibraryAuthorDto>().name?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}

/** Extracts a plausible 4-digit publication year (1500-2099) from a free-form date string. */
internal fun parseYear(rawDate: String): Int? = FOUR_DIGIT_YEAR_REGEX.find(rawDate)?.value?.toIntOrNull()
