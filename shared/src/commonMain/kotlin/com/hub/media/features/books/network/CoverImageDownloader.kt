package com.hub.media.features.books.network

import com.hub.media.core.util.Resource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess

/**
 * Downloads raw cover image bytes from a [BookMetadata.coverImageUrl]. The resulting bytes are
 * expected to be handed to [com.hub.media.core.storage.LocalImageStorageManager] for
 * content-addressed local storage per AGENTS.md §4.
 */
public class CoverImageDownloader(private val client: HttpClient) {

    /**
     * Downloads the image bytes at [url].
     *
     * @return [Resource.Success] with the raw bytes, or [Resource.Error] if the request failed,
     *   returned a non-2xx status, or the body was empty (an empty response is treated as a
     *   corrupt/missing image per AGENTS.md §7's "corrupt image byte arrays" edge case).
     */
    public suspend fun download(url: String): Resource<ByteArray> {
        return try {
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                return Resource.Error("Cover image download failed with status ${response.status.value} for $url")
            }

            val bytes = response.body<ByteArray>()
            if (bytes.isEmpty()) {
                return Resource.Error("Cover image download returned an empty body for $url")
            }

            Resource.Success(bytes)
        } catch (e: Exception) {
            Resource.Error("Cover image download failed for $url: ${e.message}", e)
        }
    }
}
