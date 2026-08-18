package com.hub.media.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Request timeout budget for all outbound calls (metadata + cover image downloads). */
private const val REQUEST_TIMEOUT_MILLIS = 15_000L
private const val CONNECT_TIMEOUT_MILLIS = 15_000L
private const val SOCKET_TIMEOUT_MILLIS = 15_000L

/**
 * `User-Agent` sent on every outbound request.
 *
 * Open Library's API guidelines (https://openlibrary.org/developers/api) ask automated clients to
 * "add a HEADER that specifies a `User-Agent` string with (a) the name of your application and
 * (b) your contact email or phone number", and rate-limit on exactly that basis: **1 request per
 * second for unidentified traffic, 3 per second for identified traffic.** Until this header
 * existed every request this app made — including Task 14's bulk backfill crawl, which issues
 * hundreds — sat in the 1 req/s bucket.
 *
 * The contact is the **repository URL, deliberately not a personal email address**: the guidelines
 * want a channel to reach the operator, GitHub issues is one, and a `User-Agent` is broadcast to
 * every host the app talks to and logged by each of them. Putting the maintainer's personal inbox
 * in there would leak it far more widely than the policy requires. Swap it for an address if Open
 * Library ever objects to a URL.
 *
 * No version number, on purpose: it would duplicate `[versions] app` in `gradle/libs.versions.toml`
 * with nothing to keep the copy honest, and the identification policy does not ask for one.
 */
public const val USER_AGENT: String = "MediaTracker (+https://github.com/Masked-Kunsiquat/MediaTracker)"

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
public val networkJson: Json =
    Json {
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
public fun createHttpClient(engine: HttpClientEngine? = null): HttpClient =
    if (engine != null) {
        HttpClient(engine) { configureHttpClient() }
    } else {
        HttpClient { configureHttpClient() }
    }

private fun HttpClientConfig<*>.configureHttpClient() {
    // We map status codes manually via Resource.Error rather than letting Ktor throw on
    // non-2xx responses.
    expectSuccess = false

    // Identify the app on every request, not just Open Library's: see USER_AGENT. Ktor otherwise
    // sends its own "Ktor client" default, which identifies the HTTP library rather than the
    // application and buys nothing under Open Library's identification policy.
    install(UserAgent) {
        agent = USER_AGENT
    }

    install(ContentNegotiation) {
        json(networkJson)
    }

    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
    }
}
