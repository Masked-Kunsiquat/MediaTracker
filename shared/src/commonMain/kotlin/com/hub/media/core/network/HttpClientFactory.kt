package com.hub.media.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Request timeout budget for all outbound calls (metadata + cover image downloads). */
private const val REQUEST_TIMEOUT_MILLIS = 15_000L
private const val CONNECT_TIMEOUT_MILLIS = 15_000L
private const val SOCKET_TIMEOUT_MILLIS = 15_000L

/**
 * The [Json] instance used for all Ktor content negotiation.
 *
 * - `ignoreUnknownKeys`: provider APIs (Open Library, Google Books) return many fields we
 *   don't model; unknown keys must not fail parsing.
 * - `isLenient`: tolerate minor deviations from strict JSON (e.g. unquoted-looking numeric edge
 *   cases some public APIs emit).
 * - `coerceInputValues`: fall back to declared defaults instead of failing when a field is the
 *   wrong type or `null` where not expected — public APIs are inconsistent about field presence.
 */
public val networkJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Builds a configured [HttpClient] shared by all provider clients in this module.
 *
 * Per AGENTS.md §4, no API keys are configured here — both Open Library and Google Books
 * (volumes lookup by ISBN) are public, keyless endpoints.
 *
 * @param engine Optional engine override. When null, the platform default engine (OkHttp on
 *   Android/JVM, wired via the `ktor-client-okhttp` dependency) is used. Passing a
 *   `MockEngine` here is what makes offline unit testing of the provider clients possible.
 */
public fun createHttpClient(engine: HttpClientEngine? = null): HttpClient {
    return if (engine != null) {
        HttpClient(engine) { configureHttpClient() }
    } else {
        HttpClient { configureHttpClient() }
    }
}

private fun HttpClientConfig<*>.configureHttpClient() {
    // We map status codes manually via Resource.Error rather than letting Ktor throw on
    // non-2xx responses.
    expectSuccess = false

    install(ContentNegotiation) {
        json(networkJson)
    }

    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
    }
}
