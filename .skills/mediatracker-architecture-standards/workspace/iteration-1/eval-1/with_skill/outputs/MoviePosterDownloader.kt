package com.hub.media.features.movies.network

import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.warn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads a movie poster from [url] and saves it to local content-addressed storage.
 *
 * This implementation follows the protocol in AGENTS.md §4:
 * 1. Download image bytes via Ktor.
 * 2. Save using [LocalImageStorageManager.saveImage], which computes the SHA-256 hash.
 * 3. The storage manager automatically deduplicates by skipping writes if the file exists.
 *
 * Per AGENTS.md §5, failures in non-essential assets like posters do not crash the app or
 * fail parent workflows; this function logs the error and returns null instead.
 *
 * @param url Public URL of the image to download.
 * @param httpClient Shared Ktor client configured with the app's User-Agent.
 * @param imageStorage Content-addressed manager for local image storage.
 * @param logger Optional logger for failure reporting; defaults to [AppLogger].
 * @return The relative filename (hash + .jpg) on success, or null on any download or I/O failure.
 */
public suspend fun downloadAndStoreMoviePoster(
    url: String,
    httpClient: HttpClient,
    imageStorage: LocalImageStorageManager,
    logger: Logger = AppLogger,
): String? {
    val tag = "MoviePosterDownloader"
    return try {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            logger.warn(tag) { "Movie poster download failed with status ${response.status.value} for $url" }
            return null
        }

        val bytes = response.body<ByteArray>()
        if (bytes.isEmpty()) {
            logger.warn(tag) { "Movie poster download returned empty bytes for $url" }
            return null
        }

        imageStorage.saveImage(bytes)
            .onFailure { logger.warn(tag, it) { "Failed to save movie poster to local storage for $url" } }
            .getOrNull()
    } catch (e: CancellationException) {
        throw e // Required for structured concurrency
    } catch (e: Exception) {
        logger.warn(tag, e) { "Unexpected error downloading/storing movie poster from $url" }
        null
    }
}
