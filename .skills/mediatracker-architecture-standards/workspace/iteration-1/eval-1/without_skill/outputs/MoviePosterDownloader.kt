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

/** Log tag for poster download operations. */
private const val TAG = "MoviePosterDownloader"

/**
 * Downloads a movie poster from [url] and saves it to [imageStorage] using content-addressing
 * (SHA-256 hash) per AGENTS.md §4.
 *
 * This implementation adheres to the "Cover/Poster Storage Protocol":
 * 1. Download image bytes via Ktor.
 * 2. Compute SHA-256 of raw bytes (handled by [LocalImageStorageManager.saveImage]).
 * 3. Save to app storage as `<hash>.jpg` (handled by [LocalImageStorageManager.saveImage]).
 * 4. Skip writing if file already exists (automatic deduplication) (handled by [LocalImageStorageManager.saveImage]).
 *
 * By design, poster failures do not throw exceptions but return null, allowing the caller to
 * proceed with degraded success (e.g., adding the movie without a poster) as per AGENTS.md §5.
 *
 * @param url The public URL of the movie poster.
 * @param httpClient Ktor client for downloading the image. MUST be configured with
 *   the standard MediaTracker USER_AGENT.
 * @param imageStorage Local storage manager for saving the image.
 * @param logger Logger for reporting errors.
 * @return The locally saved filename (e.g., "<sha256>.jpg") on success, or null if the
 *   download or save failed.
 */
public suspend fun downloadAndSaveMoviePoster(
    url: String,
    httpClient: HttpClient,
    imageStorage: LocalImageStorageManager,
    logger: Logger = AppLogger,
): String? {
    return try {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            logger.warn(TAG) { "Movie poster download failed with status ${response.status.value} for $url" }
            return null
        }

        val bytes = response.body<ByteArray>()
        if (bytes.isEmpty()) {
            logger.warn(TAG) { "Movie poster download returned an empty body for $url" }
            return null
        }

        imageStorage.saveImage(bytes)
            .onFailure { logger.warn(TAG, it) { "Movie poster save failed for $url" } }
            .getOrNull()
    } catch (e: CancellationException) {
        // Propagate cancellation to support structured concurrency.
        throw e
    } catch (e: Exception) {
        logger.warn(TAG, e) { "Unexpected error during movie poster download/save for $url" }
        null
    }
}
