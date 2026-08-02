package com.hub.media.features.books.network

import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

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
     * Issues a `HEAD`, not a `GET`: this is a pure status check, and a `GET` against a cover URL
     * buffers the entire image (tens of KB) into memory only for it to be discarded — wasteful for
     * a probe whose whole answer is the status code. Verified against the live service that
     * `covers.openlibrary.org` answers `HEAD` with the same status a `GET` would, for both the
     * cover-exists and the `?default=false` no-cover cases.
     *
     * @return The probed URL if the response is a 2xx success (a real cover exists), or `null` on
     *   a 404 (no cover), any other non-success status, or a network failure. Never throws, with
     *   the deliberate exception of [CancellationException] (see below).
     *
     * ### On the network-failure branch being silent
     * A thrown exception (TLS failure, DNS failure, timeout, connection reset, etc.) is
     * deliberately indistinguishable from a confirmed "no cover" 404 here -- both just return
     * `null`. This is a real loss of information (a caller can't tell "this book genuinely has no
     * cover" from "we couldn't check"), but recording the exception would need a logging facility,
     * and `shared/` has none: there is no `Logger`/`Napier`/equivalent anywhere in
     * `commonMain`, and AGENTS.md §5 explicitly rules out adding third-party dependencies without
     * project sign-off. Swallowing to `null` also matches the existing sibling pattern in this
     * package -- [OpenLibraryClient.fetchAuthorName] silently drops a per-author lookup failure to
     * `null` the same way -- so this isn't a one-off oversight, it's this codebase's established
     * (if imperfect) convention for a "best-effort, never-throws" lookup. If/when `shared/` grows a
     * logging facility, this is the first catch block that should start using it.
     */
    public suspend fun probeCoverUrl(isbn: String): String? {
        val url = "$OPEN_LIBRARY_COVERS_ISBN_URL/$isbn-L.jpg?default=false"
        return try {
            val response = client.head(url)
            if (response.status.isSuccess()) url else null
        } catch (e: CancellationException) {
            // Must be caught before the broad `Exception` below and rethrown: coroutine
            // cancellation propagates as an exception, so swallowing it to `null` would break
            // structured concurrency -- a caller whose scope was cancelled mid-probe would carry
            // on as though the probe had simply found no cover.
            throw e
        } catch (e: Exception) {
            // See "On the network-failure branch being silent" above: no shared/ logging facility
            // exists to record `e` against, so a network/TLS failure and a genuine "no cover" are
            // indistinguishable to callers by design, not by accident.
            null
        }
    }
}
