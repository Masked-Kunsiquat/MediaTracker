package com.hub.media.features.books.network

import com.hub.media.core.network.createHttpClient
import com.hub.media.core.util.Resource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class CoverImageDownloaderTest {
    @Test
    fun happyPath_returnsBytesRoundTrip() =
        runTest {
            val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02, 0x03)
            val engine =
                MockEngine { _ ->
                    respond(
                        content = imageBytes,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                    )
                }
            val downloader = CoverImageDownloader(createHttpClient(engine))

            val result = downloader.download("https://covers.openlibrary.org/b/id/6498519-L.jpg")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            assertTrue(imageBytes.contentEquals((result as Resource.Success).data))
        }

    @Test
    fun notFound_returnsError() =
        runTest {
            val engine = MockEngine { _ -> respondError(HttpStatusCode.NotFound) }
            val downloader = CoverImageDownloader(createHttpClient(engine))

            val result = downloader.download("https://covers.openlibrary.org/b/id/999999999-L.jpg")

            assertTrue(result is Resource.Error, "expected Error, got $result")
        }

    @Test
    fun emptyBody_returnsError() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = ByteArray(0),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                    )
                }
            val downloader = CoverImageDownloader(createHttpClient(engine))

            val result = downloader.download("https://covers.openlibrary.org/b/id/123-L.jpg")

            assertTrue(result is Resource.Error, "expected Error, got $result")
        }
}
