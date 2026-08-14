package com.hub.media.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The identification header is the whole point of [USER_AGENT] — Open Library rate-limits
 * unidentified traffic at 1 req/s and identified traffic at 3 req/s — so it is worth a test that
 * the header genuinely reaches the wire rather than trusting that the plugin is installed.
 */
class HttpClientFactoryTest {

    private fun capturingEngine(captured: MutableList<String?>) = MockEngine { request ->
        captured.add(request.headers[HttpHeaders.UserAgent])
        respond(content = "{}", status = HttpStatusCode.OK)
    }

    @Test
    fun everyRequest_carriesTheIdentifyingUserAgent() = runTest {
        val captured = mutableListOf<String?>()
        val client = createHttpClient(capturingEngine(captured))

        client.get("https://openlibrary.org/search.json")
        client.get("https://covers.openlibrary.org/b/id/1-L.jpg")

        assertEquals(listOf<String?>(USER_AGENT, USER_AGENT), captured.toList())
    }

    @Test
    fun userAgent_namesTheAppAndCarriesAContactChannel() {
        // Open Library asks for "(a) the name of your application and (b) your contact email or
        // phone number". Asserting the shape keeps a future edit from quietly dropping either half
        // back into the unidentified rate-limit bucket.
        assertTrue(USER_AGENT.startsWith("MediaTracker"), "expected the app name first: $USER_AGENT")
        assertTrue(
            USER_AGENT.contains("https://github.com/Masked-Kunsiquat/MediaTracker"),
            "expected a reachable contact channel: $USER_AGENT",
        )
    }

    @Test
    fun userAgent_doesNotLeakAPersonalEmailAddress() {
        // The contact is deliberately a repository URL. A User-Agent is broadcast to every host the
        // app talks to and logged by all of them, so this asserts the privacy decision rather than
        // leaving it to a comment nobody re-reads.
        assertTrue('@' !in USER_AGENT, "User-Agent should carry no email address: $USER_AGENT")
    }

}
