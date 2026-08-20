package com.hub.media.features.movies.network

import com.hub.media.core.network.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

private const val TMDB_BASE_URL = "https://api.themoviedb.org/3/"

/**
 * TMDB API client implementation following MediaTracker architecture standards.
 *
 * Adheres to:
 * - **Networking Standards**: Uses the shared Ktor engine and serialization config via [createHttpClient].
 * - **Identification**: Inherits the [USER_AGENT] via the shared factory, ensuring rate-limit compliance.
 * - **Secure Credentials**: The API key is passed into the factory at runtime, never hardcoded (AGENTS.md §5).
 */
public class TmdbClient(
    private val client: HttpClient
) {
    // API methods would be implemented here using the injected HttpClient.
}

/**
 * Specialized factory for the TMDB HttpClient.
 *
 * It uses the core [createHttpClient] as a base—preserving the shared JSON configuration,
 * timeouts, and User-Agent—and adds TMDB-specific defaults.
 *
 * @param apiKey The TMDB API Read Access Token (v4). This must be provided by the
 * platform-specific entry point (e.g., via BuildConfig or a secure secret store).
 */
public fun createTmdbHttpClient(apiKey: String): HttpClient {
    return createHttpClient().config {
        defaultRequest {
            url(TMDB_BASE_URL)
            // Use the API Read Access Token for modern TMDB v4 authentication
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }
}
