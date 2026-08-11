package com.hub.media.features.books.network

import com.hub.media.core.util.Resource
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.warn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess

/**
 * Downloads raw cover image bytes from a [BookMetadata.coverImageUrl]. The resulting bytes are
 * expected to be handed to [com.hub.media.core.storage.LocalImageStorageManager] for
 * content-addressed local storage per AGENTS.md §4.
 */
/** Log tag for this client's adoption sites (ROADMAP Task 15 Phase C). */
private const val TAG = "CoverImageDownloader"

public class CoverImageDownloader(
    private val client: HttpClient,
    private val logger: Logger = AppLogger,
) {

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
            // The url is a provider cover endpoint, not library content -- the same category as the
            // isbn it is usually built from.
            logger.warn(TAG, e) { "Cover image download failed for $url" }
            Resource.Error("Cover image download failed for $url: ${e.message}", e)
        }
    }
}
