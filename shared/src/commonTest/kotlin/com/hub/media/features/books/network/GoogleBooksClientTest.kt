package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.util.Resource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GoogleBooksClientTest {

    private fun MockRequestHandleScope.jsonResponse(
        json: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = json,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun happyPath_parsesFullMetadataAndUpgradesCoverUrlToHttps() = runTest {
        val responseJson = """
            {
              "kind": "books#volumes",
              "totalItems": 1,
              "items": [
                {
                  "id": "wrOQLV6xB-wC",
                  "volumeInfo": {
                    "title": "The Hobbit",
                    "authors": ["J.R.R. Tolkien"],
                    "publishedDate": "1997",
                    "pageCount": 310,
                    "imageLinks": {
                      "thumbnail": "http://books.google.com/books/content?id=wrOQLV6xB-wC&img=1"
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val engine = MockEngine { jsonResponse(responseJson) }
        val client = GoogleBooksClient(createHttpClient(engine))

        val result = client.fetchByIsbn("9780618968633")

        assertTrue(result is Resource.Success, "expected Success, got $result")
        val metadata = (result as Resource.Success).data
        assertEquals("The Hobbit", metadata.title)
        assertEquals(listOf("J.R.R. Tolkien"), metadata.authors)
        assertEquals(1997, metadata.releaseYear)
        assertEquals(310, metadata.pageCount)
        assertEquals(
            "https://books.google.com/books/content?id=wrOQLV6xB-wC&img=1",
            metadata.coverImageUrl,
        )
        assertEquals(IdentifierProvider.GOOGLE_BOOKS, metadata.provider)
        assertEquals("wrOQLV6xB-wC", metadata.externalId)
        assertEquals("9780618968633", metadata.isbn)
    }

    @Test
    fun zeroItems_returnsError() = runTest {
        val responseJson = """{"kind": "books#volumes", "totalItems": 0}"""
        val engine = MockEngine { jsonResponse(responseJson) }
        val client = GoogleBooksClient(createHttpClient(engine))

        val result = client.fetchByIsbn("0000000000")

        assertTrue(result is Resource.Error, "expected Error, got $result")
    }

    @Test
    fun missingVolumeInfoFields_returnsSuccessWithNulls() = runTest {
        val responseJson = """
            {
              "totalItems": 1,
              "items": [
                {
                  "id": "abc123",
                  "volumeInfo": {
                    "title": "Mystery Book"
                  }
                }
              ]
            }
        """.trimIndent()
        val engine = MockEngine { jsonResponse(responseJson) }
        val client = GoogleBooksClient(createHttpClient(engine))

        val result = client.fetchByIsbn("1234567890")

        assertTrue(result is Resource.Success, "expected Success, got $result")
        val metadata = (result as Resource.Success).data
        assertEquals("Mystery Book", metadata.title)
        assertTrue(metadata.authors.isEmpty())
        assertNull(metadata.releaseYear)
        assertNull(metadata.pageCount)
        assertNull(metadata.coverImageUrl)
    }

    @Test
    fun malformedJson_returnsError() = runTest {
        val engine = MockEngine { jsonResponse("{ not valid json ") }
        val client = GoogleBooksClient(createHttpClient(engine))

        val result = client.fetchByIsbn("9780618968633")

        assertTrue(result is Resource.Error, "expected Error, got $result")
    }
}
