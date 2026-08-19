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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenLibraryWorkEditionsResolverTest {
    private fun MockRequestHandleScope.jsonResponse(
        json: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = json,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun fetchEditionsForWork_happyPath_mapsValidEditions() =
        runTest {
            val json =
                """
                {
                  "size": 3,
                  "entries": [
                    {
                      "key": "/books/OL1M",
                      "title": "Edition 1",
                      "isbn_13": ["9781234567890"],
                      "publish_date": "2020",
                      "publishers": ["Publisher A"],
                      "number_of_pages": 100,
                      "covers": [123]
                    },
                    {
                      "key": "/books/OL2M",
                      "title": "Edition 2 (no ISBN)"
                    },
                    {
                      "key": "/books/OL3M",
                      "title": "Edition 3",
                      "isbn_10": ["123456789X"],
                      "publish_date": "2021",
                      "publishers": ["Publisher B"]
                    }
                  ]
                }
                """.trimIndent()
            val engine = MockEngine { jsonResponse(json) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.fetchEditionsForWork("/works/OL1W")

            assertTrue(result is Resource.Success)
            val editions = (result as Resource.Success).data
            assertEquals(2, editions.size, "expected only the ISBN-backed editions to be kept")

            val e1 = editions.first { it.editionKey == "/books/OL1M" }
            assertEquals("Edition 1", e1.title)
            assertEquals("9781234567890", e1.isbn)
            assertEquals("Publisher A", e1.publisher)
            assertEquals("2020", e1.publishDate)
            assertEquals(100, e1.pageCount)
            assertEquals("https://covers.openlibrary.org/b/id/123-M.jpg", e1.coverThumbnailUrl)
            assertEquals(IdentifierProvider.OPEN_LIBRARY, e1.provider)

            val e3 = editions.first { it.editionKey == "/books/OL3M" }
            assertEquals("Edition 3", e3.title)
            assertEquals("123456789X", e3.isbn)
            assertEquals("Publisher B", e3.publisher)
        }

    @Test
    fun fetchEditionsForWork_emptyEntries_returnsEmptyList() =
        runTest {
            val json = """{"size": 0, "entries": []}"""
            val engine = MockEngine { jsonResponse(json) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.fetchEditionsForWork("/works/OL1W")

            assertTrue(result is Resource.Success)
            assertEquals(emptyList(), (result as Resource.Success).data)
        }
}
