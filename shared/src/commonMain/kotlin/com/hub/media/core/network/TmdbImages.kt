package com.hub.media.core.network

import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Base for TMDB's image CDN.
 *
 * Hardcoded rather than read from `GET /configuration`, which is where TMDB formally publishes it.
 * That endpoint costs a request per session to learn a value that has not changed in the API's
 * lifetime, and a poster is cosmetic — an app that spent a round trip before it could show one, and
 * that broke entirely if the call failed, would be worse off than one that hardcodes and is wrong
 * only if TMDB moves its CDN.
 *
 * If it ever does move, the symptom is posters silently not loading rather than anything corrupt,
 * because a failed download is already a non-event: [com.hub.media.features.tv.domain.FetchPosterUseCase]
 * leaves the row without a cover and the app keeps working.
 */
private const val TMDB_IMAGE_BASE_URL: String = "https://image.tmdb.org/t/p/"

/**
 * The poster width requested from TMDB.
 *
 * `w500` rather than `original`: originals run to several megabytes and this app stores every image
 * it downloads on the device (AGENTS.md §4, offline-first), so the choice is a per-title disk cost
 * across a whole library rather than a one-off. 500px is wider than any phone shows a poster at, so
 * it survives a detail screen and a tablet without being the largest thing in the database.
 *
 * Also one of TMDB's published sizes rather than an arbitrary number — the CDN serves a fixed set,
 * and an unlisted width is not resized, it 404s.
 */
private const val TMDB_POSTER_SIZE: String = "w500"

/**
 * A full URL for a TMDB `poster_path`, or `null` when there is no poster.
 *
 * The path arrives with its leading slash (`/abc123.jpg`) and is used as given. A blank path is
 * treated as absent: TMDB returns `""` as well as `null` for a title with no artwork, and building
 * a URL out of it would produce a request that can only 404.
 */
public fun tmdbPosterUrl(posterPath: String?): String? {
    val path = posterPath?.trim().orEmpty()
    if (path.isEmpty()) return null
    return TMDB_IMAGE_BASE_URL + TMDB_POSTER_SIZE + path
}

/**
 * The rate a library-wide pass downloads posters at.
 *
 * **This number is chosen, not derived, and that is the thing to know about it.** [RequestPacer]'s
 * KDoc says to derive an interval from a documented rate; `image.tmdb.org` publishes none, because it
 * is a CDN rather than the API. So this records a self-imposed ceiling in the same spirit as
 * [TMDB_REQUESTS_PER_SECOND] — which is itself well under TMDB's own stated figure — rather than
 * pretending to a contract that does not exist.
 *
 * Lower than the API rate on purpose. A poster is a few hundred kilobytes against an API response's
 * few kilobytes, so the polite unit here is bandwidth, not request count, and a sequential crawl on a
 * phone will not approach even this.
 */
public const val TMDB_IMAGE_REQUESTS_PER_SECOND: Int = 5

/**
 * A [RequestPacer] sized to [TMDB_IMAGE_REQUESTS_PER_SECOND], for a bulk pass's poster downloads.
 *
 * ### Why this is not the same pacer as [tmdbPacer]
 * The two hit different hosts with different budgets — `api.themoviedb.org` and `image.tmdb.org` —
 * and a single interval covering both would be wrong in whichever direction the shared number was
 * set: fast enough for the API is a flood of megabytes at the CDN, and gentle enough for the CDN
 * throttles metadata lookups that cost almost nothing. #140 called this out before either existed.
 *
 * The same exclusivity rule applies as ever: a pacer belongs to one crawl and must not be shared with
 * a user-facing path, or the crawl's sleeps land on a download someone is waiting to see.
 */
public fun tmdbImagePacer(
    clock: Clock = Clock.System,
    sleep: suspend (Duration) -> Unit = { delay(it) },
): RequestPacer =
    RequestPacer(
        minInterval = 1.seconds / TMDB_IMAGE_REQUESTS_PER_SECOND,
        clock = clock,
        sleep = sleep,
    )
