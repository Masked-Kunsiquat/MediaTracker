package com.hub.media.features.books.network

import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.warn
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException

private const val OPEN_LIBRARY_COVERS_ISBN_URL = "https://covers.openlibrary.org/b/isbn"

/** [com.hub.media.core.util.Logger] tag for every log call this file makes. */
private const val TAG = "OpenLibraryIsbnCoverProbe"

/** Fallback wait when a 429/5xx carries no (or an unparsable) `Retry-After` header. */
private val DEFAULT_RETRY_AFTER: Duration = OPEN_LIBRARY_COVER_QUOTA_WINDOW

/**
 * Outcome of [OpenLibraryIsbnCoverProbe.probeCoverUrl] (ROADMAP Task 14 Phase A). Replaces the
 * probe's original bare `String?` return: a plain nullable collapsed "no cover for this ISBN"
 * (404) and "the provider is refusing right now" (429/5xx/local quota exhaustion) into the same
 * `null`, which is actively wrong for a bulk caller that loops over many ISBNs — a temporary
 * refusal would have permanently marked every remaining book coverless instead of being retried
 * once the quota resets. Callers that only care about "did we get a cover" can still treat every
 * non-[Found] case uniformly (as [FallbackBookMetadataProvider] does for the single-book
 * interactive paths), but a caller that needs to tell the difference — namely
 * [com.hub.media.features.books.domain.BulkBackfillUseCase] — now can.
 */
public sealed class CoverProbeResult {
    /** A real cover exists; [url] is the probed, directly-fetchable image URL. */
    public data class Found(public val url: String) : CoverProbeResult()

    /**
     * The provider affirmatively confirmed no cover exists for this ISBN (a real 404, thanks to
     * `?default=false` suppressing Open Library's usual placeholder-image response), OR the
     * request could not be completed at all (network/TLS failure) — see this class's KDoc on the
     * network-failure branch for why those two cases remain indistinguishable here, same as before
     * this type existed.
     */
    public data object NotFound : CoverProbeResult()

    /**
     * The provider is refusing right now, not confirming an absence: a 429 (Open Library's
     * cover-quota rate limit), a 5xx (transient server trouble), or this device's own local quota
     * tracking ([OpenLibraryCoverRateLimiter]) already being exhausted before any request was even
     * sent. [retryAfter] estimates how long the refusal is expected to last. A caller MUST NOT
     * treat this the same as [NotFound] when it can act on the difference (i.e. pause and retry
     * later rather than writing off the book as coverless).
     */
    public data class RateLimited(public val retryAfter: Duration) : CoverProbeResult()
}

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
 * rate-limited.
 *
 * ### Rate limiting (ROADMAP Task 14 Phase A)
 * Every call goes through [rateLimiter] first ([OpenLibraryCoverRateLimiter.tryAcquire]) — if the
 * shared quota is already exhausted, this returns [CoverProbeResult.RateLimited] without ever
 * issuing the HTTP request. The single [rateLimiter] instance is meant to be shared by every
 * caller of this probe across the app (interactive re-fetch, add-by-ISBN, and the bulk backfill —
 * see [OpenLibraryCoverRateLimiter]'s KDoc for why a bulk-only limiter would be wrong), which is
 * why it is an injected dependency rather than private state on this class: two different
 * [OpenLibraryIsbnCoverProbe] instances constructed with the *same* [rateLimiter] correctly share
 * one budget, while two instances each with their own default limiter would not.
 *
 * @param rateLimiter Shared quota tracker. Defaults to `null`, in which case a fresh
 *   [OpenLibraryCoverRateLimiter] is constructed using this same probe's [clock] -- see [clock]'s
 *   KDoc for why the two must never be allowed to disagree about "now".
 * @param clock Source of "now", used both to turn a `Retry-After` HTTP-date header into a
 *   [Duration] (see [parseRetryAfter]) and, when [rateLimiter] is left at its default, to
 *   construct that default [OpenLibraryCoverRateLimiter]. Defaults to [Clock.System], matching
 *   [com.hub.media.features.books.data.BookRepository]'s injected-[Clock] convention. Production
 *   callers never need to override this -- it exists so tests can pin the same fixed instant this
 *   probe and its [rateLimiter] both compute "now" from, which is what makes the resulting
 *   `retryAfter` deterministic and consistent between the two. A caller that supplies its *own*
 *   [rateLimiter] (the normal production case -- see this class's KDoc on why every caller is
 *   expected to share one instance) is responsible for constructing that limiter with the same
 *   [Clock] it passes here; this default-construction path only covers the case where no
 *   [rateLimiter] is supplied at all.
 * @param logger Where a swallowed network/TLS failure is recorded (ROADMAP Task 15 -- see
 *   [probeCoverUrl]'s "On the network-failure branch being silent" section). Defaults to
 *   [AppLogger], matching [clock]'s real-default-overridable-by-tests convention.
 */
public class OpenLibraryIsbnCoverProbe(
    private val client: HttpClient,
    rateLimiter: OpenLibraryCoverRateLimiter? = null,
    private val clock: Clock = Clock.System,
    private val logger: Logger = AppLogger,
) {
    // Threading `clock` through here (rather than each defaulting to Clock.System independently)
    // is what keeps this probe and its rate limiter from disagreeing about "now" when a caller
    // (namely a test) injects a non-system clock but leaves `rateLimiter` at its default -- see
    // this constructor's KDoc. `rateLimiter` can't default to `OpenLibraryCoverRateLimiter(clock =
    // clock)` directly (a parameter's default value can't reference a later-declared parameter),
    // hence the nullable-parameter-plus-elvis indirection instead.
    private val rateLimiter: OpenLibraryCoverRateLimiter = rateLimiter ?: OpenLibraryCoverRateLimiter(clock = clock)

    /**
     * Probes `covers.openlibrary.org/b/isbn/{isbn}-L.jpg?default=false` for [isbn].
     *
     * Issues a `HEAD`, not a `GET`: this is a pure status check, and a `GET` against a cover URL
     * buffers the entire image (tens of KB) into memory only for it to be discarded — wasteful for
     * a probe whose whole answer is the status code. Verified against the live service that
     * `covers.openlibrary.org` answers `HEAD` with the same status a `GET` would, for both the
     * cover-exists and the `?default=false` no-cover cases.
     *
     * @return [CoverProbeResult.Found] on a 2xx, [CoverProbeResult.NotFound] on a 404 (or a
     *   network failure — see that case's KDoc), or [CoverProbeResult.RateLimited] on a 429, a 5xx,
     *   or immediately if [rateLimiter] reports the shared quota is already exhausted. Never
     *   throws, with the deliberate exception of [CancellationException] (see below).
     *
     * ### On the network-failure branch being silent (updated, ROADMAP Task 15)
     * A thrown exception (TLS failure, DNS failure, timeout, connection reset, etc.) still *returns*
     * [CoverProbeResult.NotFound], indistinguishable from a confirmed "no cover" 404 to the caller --
     * that part is unchanged, and still the right call: unlike a 429/5xx, a network-level failure
     * carries no `Retry-After` signal and no confirmation that *this specific device* is the one
     * being throttled, so folding it into [CoverProbeResult.RateLimited] instead would risk pausing a
     * bulk backfill over what might be a one-off local network blip rather than a real quota problem.
     * [NotFound] (skip and move on) remains the safer of the two imperfect choices for the *return
     * value*.
     *
     * What changed: this codebase originally had no logging facility at all, so the exception itself
     * was discarded with nothing recorded anywhere -- "confirmed absent" and "the request never
     * completed" left literally the same trace. This class's own KDoc named itself as the first catch
     * block that should adopt one once it existed. It now does: [logger] records the failure at
     * [com.hub.media.core.util.LogLevel.WARN], with the ISBN (an edition identifier, not personal
     * content -- see [com.hub.media.core.util.Logger]'s KDoc for the identifier rule this follows)
     * and the [Throwable] itself, *before* folding the outcome to [CoverProbeResult.NotFound] below.
     * The return value a caller sees is unchanged; only diagnosability improved -- a real network/TLS
     * failure is now distinguishable from a genuine "no cover" 404 in the device's own logs, even
     * though the two still collapse to the same [CoverProbeResult] for callers that can't act on the
     * difference anyway (see [CoverProbeResult]'s own KDoc).
     */
    public suspend fun probeCoverUrl(isbn: String): CoverProbeResult {
        when (val outcome = rateLimiter.tryAcquire()) {
            is RateLimitOutcome.Denied -> return CoverProbeResult.RateLimited(outcome.retryAfter)
            RateLimitOutcome.Allowed -> Unit
        }

        val url = "$OPEN_LIBRARY_COVERS_ISBN_URL/$isbn-L.jpg?default=false"
        return try {
            val response = client.head(url)
            when {
                response.status.isSuccess() -> CoverProbeResult.Found(url)
                response.status == HttpStatusCode.TooManyRequests -> {
                    val retryAfter = parseRetryAfter(response, clock) ?: DEFAULT_RETRY_AFTER
                    rateLimiter.recordServerRefusal(retryAfter)
                    CoverProbeResult.RateLimited(retryAfter)
                }
                response.status.value >= 500 -> {
                    // Same as the 429 branch above: a 5xx is the server telling us it's not
                    // healthy right now, so the shared limiter must learn about it too -- without
                    // this, the very next probe (for a different ISBN, possibly milliseconds
                    // later) would sail straight through local rate limiting and hit a server we
                    // already know is refusing.
                    val retryAfter = parseRetryAfter(response, clock) ?: DEFAULT_RETRY_AFTER
                    rateLimiter.recordServerRefusal(retryAfter)
                    CoverProbeResult.RateLimited(retryAfter)
                }
                else -> CoverProbeResult.NotFound
            }
        } catch (e: CancellationException) {
            // Must be caught before the broad `Exception` below and rethrown: coroutine
            // cancellation propagates as an exception, so swallowing it would break structured
            // concurrency -- a caller whose scope was cancelled mid-probe would carry on as though
            // the probe had simply found no cover.
            throw e
        } catch (e: Exception) {
            // See "On the network-failure branch being silent" above -- the return value stays
            // NotFound, but the failure is now diagnosable via logger, unlike before ROADMAP Task 15.
            logger.warn(TAG, e) { "cover probe request failed for isbn=$isbn" }
            CoverProbeResult.NotFound
        }
    }
}

/**
 * Parses a `Retry-After` header in either of RFC 7231 §7.1.3's two forms: the numeric-seconds
 * `delay-seconds` form, or the HTTP-date form (e.g. `Wed, 21 Oct 2015 07:28:00 GMT`). Open
 * Library's actual header format for this is unverified against the live service (no documented
 * contract), so both are handled rather than assuming numeric-only.
 *
 * The HTTP-date form is resolved against [clock] -- the same clock instance [rateLimiter] uses
 * for its own "now" -- so the [Duration] this returns and the moment [rateLimiter] later computes
 * its `blockedUntil` from can't disagree about what "now" was. A date already in the past (clock
 * skew, or a server sending a stale header) is floored to [Duration.ZERO] rather than going
 * negative.
 *
 * An unparsable (neither form) or absent header returns `null`, and the call site falls back to
 * [DEFAULT_RETRY_AFTER] rather than this function guessing.
 */
private fun parseRetryAfter(response: HttpResponse, clock: Clock): Duration? {
    val header = response.headers[HttpHeaders.RetryAfter]?.trim() ?: return null

    header.toLongOrNull()?.let { delaySeconds -> return delaySeconds.takeIf { it >= 0 }?.seconds }

    return try {
        val target = Instant.fromEpochMilliseconds(header.fromHttpToGmtDate().timestamp)
        (target - clock.now()).coerceAtLeast(Duration.ZERO)
    } catch (e: IllegalStateException) {
        // fromHttpToGmtDate() throws IllegalStateException (via error(...)) when none of its
        // known formats match -- that's this function's "not a date either" signal, same as
        // toLongOrNull() returning null above.
        null
    }
}
