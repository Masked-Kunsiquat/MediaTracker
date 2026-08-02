package com.hub.media.features.books.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess

private const val OPEN_LIBRARY_COVERS_ISBN_URL = "https://covers.openlibrary.org/b/isbn"

/**
 * Last-resort ISBN-keyed Open Library cover probe (ROADMAP Task 6 Phase E), used only when
 * *both* [OpenLibraryClient] and [GoogleBooksClient] failed to surface a cover for a given ISBN.
 *
 * [OpenLibraryClient]'s own KDoc documents why it never uses the ISBN-keyed cover URL directly:
 * `covers.openlibrary.org/b/isbn/{isbn}-L.jpg` renders a "no cover" placeholder *image* (HTTP 200)
 * for many editions instead of a real 404, so a plain fetch of that URL can't tell "real cover"
 * apart from "placeholder" ahead of actually downloading and inspecting it. Appending
 * `?default=false` changes that: Open Library then returns a genuine 404 instead of the
 * placeholder, making the URL safely probeable with nothing more than a status-code check.
 *
 * ### Why this isn't just folded into [OpenLibraryClient]
 * This probe is ISBN-keyed, not cover-id-keyed, and Open Library rate-limits ISBN/OCLC/LCCN-keyed
 * cover lookups to 100 requests per IP per 5 minutes — unlike the ID-keyed
 * `covers.openlibrary.org/b/id/{id}-L.jpg` URLs [OpenLibraryClient] normally uses, which are not
 * rate-limited. Keeping this as a separate, explicitly-last-resort step (wired into
 * [FallbackBookMetadataProvider] only as the third fallback after both providers' normal lookups)
 * keeps that rate-limited request path opt-in and easy to keep out of any bulk/loop context — a
 * per-book "re-fetch cover" affordance (see
 * [com.hub.media.features.books.domain.RefetchCoverUseCase]) only ever issues one such request at
 * a time, but a bulk backfill across a whole library would need its own throttling before ever
 * calling this repeatedly (ROADMAP Task 6 Phase E explicitly defers bulk backfill for this reason).
 */
public class OpenLibraryIsbnCoverProbe(private val client: HttpClient) {

    /**
     * Probes `covers.openlibrary.org/b/isbn/{isbn}-L.jpg?default=false` for [isbn].
     *
     * @return The probed URL if the response is a 2xx success (a real cover exists), or `null` on
     *   a 404 (no cover), any other non-success status, or a network/parse failure. Never throws.
     */
    public suspend fun probeCoverUrl(isbn: String): String? {
        val url = "$OPEN_LIBRARY_COVERS_ISBN_URL/$isbn-L.jpg?default=false"
        return try {
            val response = client.get(url)
            if (response.status.isSuccess()) url else null
        } catch (e: Exception) {
            null
        }
    }
}
