package com.hub.media.features.tv.network

import com.hub.media.core.network.RequestPacer
import com.hub.media.core.network.TmdbCredential
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.warn
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
