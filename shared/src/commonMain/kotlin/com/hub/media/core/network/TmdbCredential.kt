package com.hub.media.core.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a stored TMDB credential is put onto an outgoing request.
 *
 * ### Why this is a type and not an `if` at the call site
 * TMDB issues *two* credentials from the same page in a user's account settings, and they are
 * transported differently:
 *
 * | | v3 **API Key** | v4 **API Read Access Token** |
 * |---|---|---|
 * | Shape | 32 hexadecimal characters | a JWT (`eyJ...`), a few hundred characters |
 * | Sent as | an `api_key` query parameter | an `Authorization: Bearer` header |
 *
 * A user pastes whichever one they copied, and the two sit adjacent on the page with similar names,
 * so "pasted the other one" is the expected mistake rather than an unusual one. Both work against
 * the same v3 endpoints this app calls, so there is no reason to make the user care which they have
 * -- and every reason not to, since the failure mode for guessing wrong is a bare `401` with nothing
 * on screen explaining it.
 *
 * ### Recognition happens here, not in storage
 * [com.hub.media.features.settings.data.setTmdbCredential] stores whatever the user typed without
 * inspecting it. That is deliberate: a validating setter would turn "TMDB introduced a new
 * credential format" into "the app refuses to store a credential that works", which is a far worse
 * failure than one rejected request. Being wrong *here* costs a single 401; being wrong in the
 * setter makes the value unstorable.
 *
 * @see of for the classification rule and what happens to values matching neither shape.
 */
public sealed interface TmdbCredential {
    /** Applies this credential to [builder] in whatever way its shape requires. */
    public fun applyTo(builder: HttpRequestBuilder)

    /**
     * A v4 read access token, sent as `Authorization: Bearer <token>`.
     *
     * @property value The raw token. Never logged -- see [TmdbClient]'s logging rule.
     */
    public data class ReadAccessToken(
        public val value: String,
    ) : TmdbCredential {
        override fun applyTo(builder: HttpRequestBuilder) {
            builder.header(HttpHeaders.Authorization, "Bearer $value")
        }
    }

    /**
     * A v3 API key, sent as an `api_key` query parameter.
     *
     * This is the shape that puts a credential *in the URL*, which is why neither [TmdbClient] nor
     * anything below it may log a request URL: doing so would write the key into the log file the
     * Settings screen offers to display and export.
     *
     * @property value The raw key. Never logged, and never allowed into a logged URL.
     */
    public data class ApiKey(
        public val value: String,
    ) : TmdbCredential {
        override fun applyTo(builder: HttpRequestBuilder) {
            builder.parameter("api_key", value)
        }
    }

    public companion object {
        /**
         * The prefix every JWT starts with: `eyJ` is base64 for `{"`, the opening of the JSON
         * header object. It is not a TMDB-specific marker, which is the point -- it identifies the
         * *format*, so it keeps working if TMDB changes what it puts inside the token.
         */
        private const val JWT_PREFIX = "eyJ"

        /**
         * Classifies [raw] by shape.
         *
         * A value beginning with [JWT_PREFIX] is a [ReadAccessToken]; **everything else** is treated
         * as an [ApiKey]. That asymmetry is intentional rather than sloppy: the JWT test is a
         * positive identification of a specific format, whereas "32 hex characters" is a weak
         * pattern that a future key format could stop matching without ceasing to be an API key. So
         * the specific test guards the specific case, and the query-parameter path takes everything
         * it does not claim.
         *
         * The consequence, stated plainly: a typo, a truncated paste, or a credential from some
         * other service is sent as an `api_key` and comes back `401`. That is the correct outcome --
         * it is indistinguishable from a genuinely wrong key, which is what it is, and the remedy
         * ("check the credential you pasted") is identical either way.
         */
        public fun of(raw: String): TmdbCredential {
            val trimmed = raw.trim()
            return if (trimmed.startsWith(JWT_PREFIX)) {
                ReadAccessToken(trimmed)
            } else {
                ApiKey(trimmed)
            }
        }
    }
}

/**
 * TMDB's practical request ceiling, used to size [tmdbPacer].
 *
 * **This is not the same kind of number as [OPEN_LIBRARY_IDENTIFIED_REQUESTS_PER_SECOND].** Open
 * Library publishes an enforced rate. TMDB removed its hard limit (the old 40-requests-per-10-seconds
 * rule) and now describes roughly 50 requests per second as the point at which it will start
 * refusing -- a ceiling rather than a contract. [RequestPacer]'s KDoc says to derive an interval from
 * a documented rate rather than picking a number, so this records which of the two it is.
 */
public const val TMDB_CEILING_REQUESTS_PER_SECOND: Int = 50

/**
 * The rate the bulk TMDB paths are actually held to, deliberately well under
 * [TMDB_CEILING_REQUESTS_PER_SECOND].
 *
 * A background crawl has nothing to gain from running at a provider's ceiling, and the measurement
 * from #42 says so concretely: sequential round trips on a real device came out around 700ms, so a
 * sequential crawl reaches roughly 1.4 requests per second and never approaches even this reduced
 * figure. The pacer is therefore free insurance against a faster connection rather than a tax on
 * this one -- which is exactly the conclusion #42 reached about Open Library, and the reason to
 * prefer a number with headroom over one that hugs the limit.
 */
public const val TMDB_REQUESTS_PER_SECOND: Int = 20

/**
 * A [RequestPacer] sized to [TMDB_REQUESTS_PER_SECOND], for bulk passes over api.themoviedb.org.
 *
 * Same rule as [openLibraryIdentifiedPacer]: a pacer belongs to one crawl and must not be shared
 * with a user-facing path, or the crawl's sleeps land on latency someone is waiting on.
 */
public fun tmdbPacer(
    clock: Clock = Clock.System,
    sleep: suspend (Duration) -> Unit = { delay(it) },
): RequestPacer =
    RequestPacer(
        minInterval = 1.seconds / TMDB_REQUESTS_PER_SECOND,
        clock = clock,
        sleep = sleep,
    )
