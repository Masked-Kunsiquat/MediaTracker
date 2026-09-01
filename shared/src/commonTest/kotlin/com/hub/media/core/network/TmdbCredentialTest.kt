package com.hub.media.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The classification rule, and -- more importantly -- what each classification actually does to a
 * request. Asserting only the returned type would leave the interesting half untested: a credential
 * recognised correctly but applied to the wrong part of the request fails exactly the same way as
 * one recognised wrongly.
 */
class TmdbCredentialTest {
    /**
     * Deliberately *not* a structurally valid JWT, only one that starts like one.
     *
     * `TmdbCredential.of` classifies on the `eyJ` prefix alone, so three real base64 segments would
     * over-specify the fixture -- and a well-formed `header.payload.signature` string is exactly
     * what secret scanners match on, which failed CI once already. There is no secret in a fake
     * token, but a scanner cannot know that, and suppressing it in `.gitleaksignore` would mean a
     * permanent exception keyed to a commit SHA that rots on the next rebase.
     */
    private val jwt: String = "eyJ-fake-tmdb-read-access-token-for-tests"

    /**
     * A real TMDB v3 key is 32 hexadecimal characters. This fixture deliberately is not.
     *
     * `TmdbCredential.of` claims only the `eyJ` prefix and sends *everything else* as an `api_key`,
     * so the shape of a non-JWT value is irrelevant to what is being tested here -- and a 32-char
     * hex literal is exactly what a secret scanner treats as a key, which failed CI once already.
     * Being unmistakably fake is worth more than being realistic.
     */
    private val v3Key: String = "fake-v3-api-key-for-tests"

    @Test
    fun aJwtIsAReadAccessToken() {
        assertIs<TmdbCredential.ReadAccessToken>(TmdbCredential.of(jwt))
    }

    @Test
    fun aValueThatIsNotAJwtIsAnApiKey() {
        assertIs<TmdbCredential.ApiKey>(TmdbCredential.of(v3Key))
    }

    @Test
    fun surroundingWhitespaceIsTrimmedBeforeClassifying() {
        // Users paste from a web page, and a trailing newline must not turn a token into a key.
        val credential = TmdbCredential.of("  $jwt\n")
        assertIs<TmdbCredential.ReadAccessToken>(credential)
        assertEquals(jwt, credential.value, "the stored value must not keep the whitespace either")
    }

    /**
     * The documented fallback: anything the JWT test does not claim is sent as an api_key. Asserted
     * so the behaviour is a decision on record rather than an accident of the `if`.
     */
    @Test
    fun anUnrecognisableValueFallsBackToApiKey() {
        assertIs<TmdbCredential.ApiKey>(TmdbCredential.of("not-a-real-credential"))
    }

    @Test
    fun aReadAccessTokenTravelsAsABearerHeaderAndNotInTheUrl() =
        runTest {
            val engine = echoEngine()
            val client = createHttpClient(engine)

            client.get("https://api.themoviedb.org/3/tv/1396") {
                TmdbCredential.of(jwt).applyTo(this)
            }

            val request = engine.requestHistory.single()
            assertEquals("Bearer $jwt", request.headers[HttpHeaders.Authorization])
            assertNull(
                request.url.parameters["api_key"],
                "a read access token must not also be sent as a query parameter",
            )
        }

    @Test
    fun anApiKeyTravelsAsAQueryParameter() =
        runTest {
            val engine = echoEngine()
            val client = createHttpClient(engine)

            client.get("https://api.themoviedb.org/3/tv/1396") {
                TmdbCredential.of(v3Key).applyTo(this)
            }

            val request = engine.requestHistory.single()
            assertEquals(v3Key, request.url.parameters["api_key"])
            assertNull(
                request.headers[HttpHeaders.Authorization],
                "an api key must not also be sent as a bearer header",
            )
        }

    /**
     * The reason `TmdbClient` may never log a URL, pinned as a test so the constraint is visible
     * rather than only asserted in prose: with a v3 key the secret genuinely is in the URL.
     */
    @Test
    fun anApiKeyEndsUpInTheUrlWhichIsWhyUrlsAreNeverLogged() =
        runTest {
            val engine = echoEngine()
            val client = createHttpClient(engine)

            client.get("https://api.themoviedb.org/3/tv/1396") {
                TmdbCredential.of(v3Key).applyTo(this)
            }

            val requestUrl =
                engine.requestHistory
                    .single()
                    .url
                    .toString()
            assertTrue(
                v3Key in requestUrl,
                "if this ever stops being true, TmdbClient's no-URL-logging rule can be relaxed",
            )
        }

    private fun echoEngine() =
        MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
}
