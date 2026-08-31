package com.hub.media.features.tv.network

import com.hub.media.core.network.RequestPacer
import com.hub.media.core.network.TmdbCredential
import com.hub.media.core.network.networkJson
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.warn
import com.hub.media.features.tv.network.dto.TmdbAuthenticationDto
import com.hub.media.features.tv.network.dto.TmdbMovieDetailsDto
import com.hub.media.features.tv.network.dto.TmdbSearchResponseDto
import com.hub.media.features.tv.network.dto.TmdbSeasonDetailsDto
import com.hub.media.features.tv.network.dto.TmdbShowDetailsDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/** Base for every TMDB v3 endpoint this app calls. */
internal const val TMDB_BASE_URL: String = "https://api.themoviedb.org/3"

/** Log tag for this client's adoption sites (ROADMAP Task 15 Phase C). */
private const val TAG = "TmdbClient"

/**
 * Statuses TMDB answers with when it is objecting to the *credential* rather than the request:
 * `401` for a missing, malformed or revoked one, `404` when a v3 key is syntactically fine but
 * unknown. Distinguished from ordinary failures because the remedy is completely different -- the
 * user has to go and fix something in Settings, not retry.
 */
private val CREDENTIAL_REJECTION_STATUS_CODES = setOf(401)

/** TMDB's documented ceiling on how many items one `append_to_response` may carry. */
public const val MAX_APPENDED_SEASONS: Int = 20

/**
 * A show plus whichever seasons came back with it from [TmdbClient.showWithSeasons].
 *
 * @property seasons Keyed by season number. Contains only regular seasons -- season 0 is never
 *   requested -- and only those TMDB actually returned.
 * @property missingSeasonNumbers Regular seasons the show declares but this response does not carry:
 *   either beyond [MAX_APPENDED_SEASONS], or dropped because their payload would not decode. Empty
 *   for the overwhelming majority of shows. A caller that needs them must fetch each with
 *   [TmdbClient.seasonDetails]; one that does not can ignore this and still hold a coherent show.
 */
public data class TmdbShowWithSeasons(
    public val show: TmdbShowDetailsDto,
    public val seasons: Map<Int, TmdbSeasonDetailsDto>,
) {
    public val missingSeasonNumbers: List<Int>
        get() =
            show.seasons
                .map { it.seasonNumber }
                .filter { it >= 1 && it !in seasons }
                .sorted()
}

/**
 * Client for the TMDB v3 API (AGENTS.md §4: primary source for films and TV).
 *
 * ### The credential is required, and read per request
 * Unlike [com.hub.media.features.books.network.GoogleBooksClient], where a key is an optional
 * upgrade over tolerated keyless access, TMDB refuses anonymous requests outright. A null from
 * [credentialProvider] is therefore not a degraded mode but a definite "cannot answer", surfaced as
 * [Resource.Error] rather than an exception -- the app remains fully usable without one, because
 * films and shows can be entered by hand (AGENTS.md §1, offline-first).
 *
 * It is read *per request*, never captured at construction, for the same reason the Google Books key
 * is: this client is built once at [com.hub.media.ui.AppContainer] construction and outlives every
 * visit to Settings, so a captured value would keep sending a credential the user had just cleared.
 *
 * ### Nothing here may log a URL
 * A v3 credential travels as an `api_key` **query parameter**, so the request URL contains the
 * secret. This app has an in-app log viewer and a log export, which is exactly where a logged URL
 * would end up. So this class logs status codes, endpoint *names*, and identifiers -- never a URL,
 * never a query string, and never the credential. [TmdbCredential.ApiKey]'s KDoc records the same
 * rule at the other end.
 *
 * ### Pacing
 * [pacer] is optional and absent by default, matching [com.hub.media.features.books.network.OpenLibraryClient]:
 * the interactive paths (a user searching for a show) issue one request and should not pay an
 * interval, while a bulk pass hands one in. See `tmdbPacer`.
 *
 * @param client Shared Ktor client.
 * @param credentialProvider Suspending source of the raw stored credential, or `null` if the user
 *   has not supplied one. The raw string is classified by [TmdbCredential.of] on every request.
 * @param pacer Optional rate limiter for bulk callers. Must not be shared with an interactive path.
 * @param logger Log sink. **No credential and no URL may reach it.**
 */
public class TmdbClient(
    private val client: HttpClient,
    private val credentialProvider: suspend () -> String?,
    private val pacer: RequestPacer? = null,
    private val logger: Logger = AppLogger,
) {
    /**
     * Issues one paced, authenticated GET and decodes it, or returns a [Resource.Error] describing
     * why not.
     *
     * @param path Endpoint path below [TMDB_BASE_URL], e.g. `/tv/1396`. Used in log lines, which is
     *   safe precisely because it is the path *without* the query string the credential lives in.
     * @param queryParams Extra query parameters. Never logged.
     */
    private suspend inline fun <reified T> getAuthenticated(
        path: String,
        queryParams: Map<String, String> = emptyMap(),
    ): Resource<T> {
        val raw = credentialProvider()
        if (raw == null) {
            // Not logged as a warning: having no credential is an ordinary state for this app, not
            // a fault. The screen that asked is responsible for saying so.
            return Resource.Error("No TMDB credential is set. Add one in Settings to look up films and shows.")
        }
        val credential = TmdbCredential.of(raw)

        return try {
            pacer?.acquire()
            val response: HttpResponse =
                client.get("$TMDB_BASE_URL$path") {
                    credential.applyTo(this)
                    queryParams.forEach { (key, value) -> parameter(key, value) }
                }

            if (!response.status.isSuccess()) {
                val status = response.status.value
                if (status in CREDENTIAL_REJECTION_STATUS_CODES) {
                    // Which credential *shape* was sent is worth recording and is not sensitive --
                    // it is the single most useful fact when someone reports "my key does not work",
                    // because pasting the wrong one of TMDB's two is the expected mistake.
                    logger.warn(TAG) {
                        "TMDB rejected the credential (${credential::class.simpleName}) with $status for $path"
                    }
                    return Resource.Error("TMDB rejected the credential. Check the key or token saved in Settings.")
                }
                logger.warn(TAG) { "TMDB returned $status for $path" }
                return Resource.Error("TMDB request failed with status $status")
            }

            Resource.Success(response.body<T>())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The exception's own message is not interpolated into the user-facing text: Ktor's
            // failures can include the request URL, which carries an api_key credential.
            logger.warn(TAG) { "TMDB request to $path failed (${e::class.simpleName})" }
            Resource.Error("Could not reach TMDB")
        }
    }

    /**
     * Asks TMDB whether the stored credential is usable, via `GET /authentication`.
     *
     * Exists because the alternative is a user saving a credential, seeing "A credential is saved",
     * and only discovering it is wrong the next time a search comes back empty-handed -- at which
     * point the cause is several steps behind them. One request converts that into an immediate
     * yes or no.
     *
     * Works for both credential shapes: the endpoint accepts a Bearer token and an `api_key` query
     * parameter alike, answering `200 {"success":true}` or `401` with TMDB's `status_code: 7`
     * ("Invalid API key"). Verified against the live API for a valid token, an invalid token and an
     * invalid key.
     *
     * Note what a success does **not** prove: that the credential will still be valid later, or that
     * any particular endpoint is permitted. It proves TMDB accepted it just now, which is the
     * question the user is actually asking when they press the button.
     *
     * The response body is ignored deliberately -- the status code already carries the answer, and
     * decoding a body would add a failure mode ("valid credential, unparseable envelope") that
     * cannot mean anything useful to a user.
     */
    public suspend fun verifyCredential(): Resource<Unit> =
        when (val result = getAuthenticated<TmdbAuthenticationDto>("/authentication")) {
            is Resource.Success -> Resource.Success(Unit)
            is Resource.Error -> result
        }

    /**
     * A show and its seasons' episodes in **one** request, via TMDB's `append_to_response`.
     *
     * ### Why this exists rather than a loop over [seasonDetails]
     * The obvious shape -- fetch the show, then one request per season -- costs `1 + n` round trips,
     * so a five-season show is six. `append_to_response` folds the same data into a single response
     * for the same total bytes, which on a phone is the difference between one round trip and six.
     *
     * ### Seasons are requested blind, and that is safe
     * The caller does not know how many seasons a show has until the response arrives, so this asks
     * for `season/1..`[MAX_APPENDED_SEASONS] unconditionally. Verified against the live API: TMDB
     * silently omits seasons that do not exist rather than erroring -- Chernobyl, a single-season
     * miniseries, answers `200` with only `season/1` appended.
     *
     * Season 0 is never requested. Specials are not created by this app's add-by-search path (#75,
     * revisited in #122), so fetching them would be bytes spent on rows nothing writes.
     *
     * ### The 20-season ceiling
     * TMDB caps `append_to_response` at 20 items. A show with more seasons than that comes back with
     * the first [MAX_APPENDED_SEASONS] appended and the rest absent -- **not** an error, and not
     * detectable from the appended keys alone, which is why [TmdbShowWithSeasons.missingSeasonNumbers]
     * reports them explicitly rather than leaving a caller to compare lists. Callers that need every
     * season must follow up with [seasonDetails] for those numbers.
     *
     * The response is decoded by hand because `append_to_response` puts its payload under *dynamic*
     * keys (`season/1`, `season/2`, ...) that no declared `@Serializable` class can name.
     */
    public suspend fun showWithSeasons(showId: Int): Resource<TmdbShowWithSeasons> {
        val appended = (1..MAX_APPENDED_SEASONS).joinToString(",") { "season/$it" }
        return when (
            val result =
                getAuthenticated<JsonObject>(
                    "/tv/$showId",
                    mapOf("append_to_response" to appended),
                )
        ) {
            is Resource.Error -> result
            is Resource.Success -> decodeShowWithSeasons(result.data)
        }
    }

    /**
     * Splits one `append_to_response` body into the show and its appended seasons.
     *
     * A season that fails to decode is dropped rather than failing the whole show: the show record
     * and every other season are still usable, and the alternative -- refusing to add a show because
     * one season's payload was malformed -- trades a complete failure for a partial one. Dropped
     * seasons reappear in [TmdbShowWithSeasons.missingSeasonNumbers], so a caller that cares is told.
     */
    private fun decodeShowWithSeasons(body: JsonObject): Resource<TmdbShowWithSeasons> =
        try {
            val show = networkJson.decodeFromJsonElement(TmdbShowDetailsDto.serializer(), body)
            val seasons =
                body
                    .entries
                    .mapNotNull { (key, value) ->
                        val number = key.removePrefix("season/").toIntOrNull()?.takeIf { key.startsWith("season/") }
                        if (number == null) {
                            null
                        } else {
                            runCatching {
                                number to networkJson.decodeFromJsonElement(TmdbSeasonDetailsDto.serializer(), value)
                            }.getOrNull()
                        }
                    }.toMap()
            Resource.Success(TmdbShowWithSeasons(show = show, seasons = seasons))
        } catch (e: Exception) {
            logger.warn(TAG) { "A TMDB show payload could not be decoded (${e::class.simpleName})" }
            Resource.Error("TMDB returned a show record this app could not read")
        }

    /** Searches shows by name. Interactive: one request, and normally unpaced. */
    public suspend fun searchShows(query: String): Resource<TmdbSearchResponseDto> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            // Spends no request, and no slice of the rate budget, learning that an empty query
            // returns nothing -- mirroring OpenLibrarySearchClient's handling of the same case.
            return Resource.Success(TmdbSearchResponseDto())
        }
        return getAuthenticated("/search/tv", mapOf("query" to trimmed))
    }

    /** Searches films by title. */
    public suspend fun searchMovies(query: String): Resource<TmdbSearchResponseDto> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Resource.Success(TmdbSearchResponseDto())
        return getAuthenticated("/search/movie", mapOf("query" to trimmed))
    }

    /**
     * One show's record, including its season list.
     *
     * Returns what TMDB says, unfiltered -- season 0 included when the show has one. Deciding which
     * seasons to act on is the caller's policy (#75 creates regular seasons only; #122 tracks
     * revisiting that), and a client that quietly dropped specials would make that decision
     * invisible at the point it is taken.
     */
    public suspend fun showDetails(showId: Int): Resource<TmdbShowDetailsDto> = getAuthenticated("/tv/$showId")

    /**
     * One season's episodes.
     *
     * This is the only endpoint that carries episode-level detail -- the show record's season list
     * gives counts but no titles -- so a backfill costs one request per season, not one per show.
     */
    public suspend fun seasonDetails(
        showId: Int,
        seasonNumber: Int,
    ): Resource<TmdbSeasonDetailsDto> = getAuthenticated("/tv/$showId/season/$seasonNumber")

    /** One film's record. */
    public suspend fun movieDetails(movieId: Int): Resource<TmdbMovieDetailsDto> = getAuthenticated("/movie/$movieId")
}
